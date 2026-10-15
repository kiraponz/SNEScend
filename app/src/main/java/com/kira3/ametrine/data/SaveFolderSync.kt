package com.kira3.ametrine.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import android.util.Log
import com.kira3.ametrine.emucore.EmulatorCore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Properties

/**
 * Keeps a copy of the games' battery saves in a folder the user can reach: `snes_savedata` in the
 * game folder, or a folder they picked ([SaveLocation]). The core's own files
 * ([EmulatorCore.memoryCardsDir], `<serial>.srm`) stay the working copy; the copies are named after
 * the game's file (`Super Metroid.srm` for `Super Metroid.sfc`), so another emulator finds them.
 *
 * A copy goes out whenever the core has written the save ([push]). Before a game boots, [pull]
 * takes in a copy that changed outside the app since it was last written, a save put in from a
 * computer or another emulator, say; the core's file goes to its backups first. What was last
 * written or read is remembered in `<serial>.sync` beside the save.
 *
 * Every function does blocking I/O, and does nothing when the folder is not set or not writable.
 */
class SaveFolderSync(
    context: Context,
    private val core: EmulatorCore,
    private val preferences: AppPreferences,
) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    /**
     * The folder the copies go to now, created if it is not there yet; null if there is none.
     * Synchronized like the rest, so two callers do not both create `snes_savedata` (the second
     * would get `snes_savedata (1)`).
     */
    @Synchronized
    fun folder(): SaveFolder? = runCatching {
        val location = runBlocking { preferences.saveLocation.first() }
        when (location.mode) {
            SaveLocation.Mode.Folder -> location.folder?.takeIf(::canWrite)?.let { tree ->
                SaveFolder(tree, DocumentsContract.getTreeDocumentId(tree))
            }
            SaveLocation.Mode.GameFolder -> runBlocking { preferences.gameFolder.first() }?.takeIf(::canWrite)?.let { tree ->
                val root = DocumentsContract.getTreeDocumentId(tree)
                val id = list(SaveFolder(tree, root))[GAME_FOLDER_SUBFOLDER]?.id
                    ?: DocumentsContract.createDocument(
                        contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(tree, root),
                        Document.MIME_TYPE_DIR,
                        GAME_FOLDER_SUBFOLDER,
                    )?.let(DocumentsContract::getDocumentId)
                id?.let { SaveFolder(tree, it) }
            }
        }
    }.onFailure { Log.w(TAG, "No save folder", it) }.getOrNull()

    /** Whether the app may write to the SAF tree [tree]. */
    fun canWrite(tree: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /** Keeps read and write access to [tree] across restarts. */
    fun takeAccess(tree: Uri) {
        contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    /** The name a SAF document [uri] (a game) shows, without its extension. */
    fun gameName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.let(::nameOf)

    /**
     * Before the game with [serial] boots: takes in the copies in the folder that changed outside
     * the app, then sends out what the folder lacks. [name] is the game's file name without its
     * extension.
     */
    @Synchronized
    fun pull(serial: String, name: String) = withFolder { folder ->
        val record = Record.load(core.memoryCardsDir, serial)
        val files = list(folder)
        for (ext in EXTENSIONS) {
            val copy = files["$name.$ext"] ?: continue
            val local = File(core.memoryCardsDir, "$serial.$ext")
            val known = record.isFor(folder, name) && record.has(ext)
            if (known && record.matches(ext, copy)) continue
            // Changed since the app last wrote or read it; or a folder the app has not used for this
            // game, where the newer of the two wins.
            if (known || !local.isFile || copy.modified > local.lastModified()) {
                val bytes = read(copy) ?: continue
                if (bytes.isEmpty() || bytes.size > MAX_SAVE_BYTES) continue
                BatteryFiles.backUp(local)
                if (!BatteryFiles.writeAtomically(local, bytes)) continue
                Log.i(TAG, "Took in ${copy.name} for $serial")
                record.set(folder, name, ext, copy, local)
            }
        }
        record.save()
        pushLocked(folder, serial, name)
    }

    /**
     * Sends the battery save of [serial] out to the folder where it changed. [name] is the game's
     * file name without its extension; null keeps the name it was last sent out under.
     */
    @Synchronized
    fun push(serial: String, name: String?) = withFolder { folder ->
        val copyName = name ?: Record.load(core.memoryCardsDir, serial).name ?: return@withFolder
        pushLocked(folder, serial, copyName)
    }

    /**
     * Sends out every save to the folder they go to now: under the name it went out under before,
     * else its game's in [names] (by serial, file names without the extension).
     */
    @Synchronized
    fun pushAll(names: Map<String, String>) = withFolder { folder ->
        core.memoryCardsDir.listFiles().orEmpty()
            .filter { it.name.endsWith(".srm") }
            .forEach { file ->
                val serial = file.name.removeSuffix(".srm")
                val name = Record.load(core.memoryCardsDir, serial).name ?: names[serial]
                name?.let { pushLocked(folder, serial, it) }
            }
    }

    /** Deletes the copies of the save of [serial], with the record of them. */
    @Synchronized
    fun delete(serial: String) {
        val record = Record.load(core.memoryCardsDir, serial)
        withFolder { folder ->
            val name = record.name ?: return@withFolder
            if (!record.isFor(folder, name)) return@withFolder
            val files = list(folder)
            for (ext in EXTENSIONS) {
                files["$name.$ext"]?.let { DocumentsContract.deleteDocument(contentResolver, it.uri) }
            }
        }
        record.file.delete()
    }

    private fun pushLocked(folder: SaveFolder, serial: String, name: String) {
        val record = Record.load(core.memoryCardsDir, serial)
        var files: Map<String, Entry>? = null
        for (ext in EXTENSIONS) {
            val local = File(core.memoryCardsDir, "$serial.$ext")
            if (!local.isFile) continue
            val listed = files ?: list(folder).also { files = it }
            val copy = listed["$name.$ext"]
            if (copy != null && record.isFor(folder, name) && record.matches(ext, copy) && record.localMatches(ext, local)) continue
            val written = write(folder, "$name.$ext", copy, local.readBytes()) ?: continue
            record.set(folder, name, ext, written, local)
        }
        record.save()
    }

    /** Writes [bytes] to [fileName] in [folder] through a temporary file, over [old] if it is there. */
    private fun write(folder: SaveFolder, fileName: String, old: Entry?, bytes: ByteArray): Entry? = runCatching {
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder.tree, folder.id)
        list(folder)["$fileName$TEMP_SUFFIX"]?.let { DocumentsContract.deleteDocument(contentResolver, it.uri) }
        val temp = DocumentsContract.createDocument(contentResolver, parent, MIME_TYPE, "$fileName$TEMP_SUFFIX")
            ?: return@runCatching null
        contentResolver.openOutputStream(temp, "wt")?.use { it.write(bytes) } ?: return@runCatching null
        old?.let { DocumentsContract.deleteDocument(contentResolver, it.uri) }
        val renamed = runCatching { DocumentsContract.renameDocument(contentResolver, temp, fileName) }.getOrNull()
        if (renamed == null) {
            // A provider that cannot rename: write the file itself.
            DocumentsContract.deleteDocument(contentResolver, temp)
            val target = DocumentsContract.createDocument(contentResolver, parent, MIME_TYPE, fileName) ?: return@runCatching null
            contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: return@runCatching null
        }
        list(folder)[fileName]
    }.onFailure { Log.w(TAG, "Could not write $fileName", it) }.getOrNull()

    private fun read(entry: Entry): ByteArray? = runCatching {
        contentResolver.openInputStream(entry.uri)?.use { it.readBytes() }
    }.onFailure { Log.w(TAG, "Could not read ${entry.name}", it) }.getOrNull()

    /** The documents in [folder], by name. */
    private fun list(folder: SaveFolder): Map<String, Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder.tree, folder.id)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
        val entries = mutableMapOf<String, Entry>()
        contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                entries[name] = Entry(
                    id = id,
                    uri = DocumentsContract.buildDocumentUriUsingTree(folder.tree, id),
                    name = name,
                    modified = if (cursor.isNull(2)) 0 else cursor.getLong(2),
                    size = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                )
            }
        }
        return entries
    }

    private inline fun withFolder(block: (SaveFolder) -> Unit) {
        val folder = folder() ?: return
        runCatching { block(folder) }.onFailure { Log.w(TAG, "Could not sync the saves", it) }
    }

    /** A folder the copies go to: the document [id] in the SAF tree [tree]. */
    data class SaveFolder(val tree: Uri, val id: String) {
        val key: String get() = "$tree|$id"
    }

    private data class Entry(val id: String, val uri: Uri, val name: String, val modified: Long, val size: Long)

    /** What the copies of one game's save held when the app last wrote or read them. */
    private class Record(val file: File, private val properties: Properties) {
        val name: String? get() = properties.getProperty("name")

        fun isFor(folder: SaveFolder, name: String) =
            properties.getProperty("folder") == folder.key && properties.getProperty("name") == name

        fun has(ext: String) = properties.getProperty("$ext.modified") != null

        fun matches(ext: String, copy: Entry) =
            properties.getProperty("$ext.modified") == copy.modified.toString() &&
                properties.getProperty("$ext.size") == copy.size.toString()

        fun localMatches(ext: String, local: File) =
            properties.getProperty("$ext.local") == "${local.lastModified()}/${local.length()}"

        fun set(folder: SaveFolder, name: String, ext: String, copy: Entry, local: File) {
            if (!isFor(folder, name)) {
                properties.clear()
                properties.setProperty("folder", folder.key)
                properties.setProperty("name", name)
            }
            properties.setProperty("$ext.modified", copy.modified.toString())
            properties.setProperty("$ext.size", copy.size.toString())
            properties.setProperty("$ext.local", "${local.lastModified()}/${local.length()}")
        }

        fun save() {
            if (properties.isEmpty) return
            val text = java.io.StringWriter().also { properties.store(it, null) }.toString()
            BatteryFiles.writeAtomically(file, text.toByteArray())
        }

        companion object {
            fun load(dir: File, serial: String): Record {
                val file = File(dir, "$serial$RECORD_EXTENSION")
                val properties = Properties()
                runCatching { file.reader().use(properties::load) }
                return Record(file, properties)
            }
        }
    }

    companion object {
        private const val TAG = "SaveFolderSync"

        /** The name the copies of a game's save get, from the game's [fileName]. */
        fun nameOf(fileName: String): String? = fileName.substringBeforeLast('.').trim().takeIf { it.isNotEmpty() }

        /** The folder in the game folder that [SaveLocation.Mode.GameFolder] keeps the saves in. */
        const val GAME_FOLDER_SUBFOLDER = "snes_savedata"

        /** The files of a save that are copied: the SRAM and, for a cartridge with one, the clock. */
        private val EXTENSIONS = listOf("srm", "rtc")
        private const val RECORD_EXTENSION = ".sync"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MIME_TYPE = "application/octet-stream"

        /** The largest SRAM a cartridge has is 512 KB (with SA-1 BW-RAM); anything bigger is not a save. */
        private const val MAX_SAVE_BYTES = 1024 * 1024
    }
}
