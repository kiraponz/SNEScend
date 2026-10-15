package com.kira3.ametrine.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.kira3.ametrine.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/** A game's battery save (the cartridge's SRAM), `<serial>.srm` in the core's save folder. */
data class MemoryCard(
    val name: String,
    val bytes: Long,
    val modified: Long,
) {
    /** The game the save belongs to. */
    val serial: String get() = name.substringBeforeLast('.')
}

enum class MemoryCardResult { Ok, Exists, Invalid, Failed, Full, Busy }

/** An earlier version of a battery save, kept when a game session first changed it. */
data class SaveBackup(val generation: Int, val bytes: Long, val modified: Long)

/** Where the copies of the saves go, and whether they can. */
data class SaveLocationState(
    val location: SaveLocation,
    /** The picked folder's name, for [SaveLocation.Mode.Folder]. */
    val folderName: String?,
    /** False when the folder is not set or the app may not write to it. */
    val writable: Boolean,
    val hasGameFolder: Boolean,
)

/**
 * The games' battery saves in `<dataDir>/saves`. The core reads a game's save when it boots and
 * writes it a second after the game changed it, under the game's serial, so these are plain files
 * that the app lists, copies in and out, restores from their backups, and deletes. (MesenCE keeps
 * a real-time clock beside the save, as `<serial>.rtc`, for the few cartridges that have one; it
 * goes with the save.) [SaveFolderSync] keeps a copy of each in the folder of [location].
 */
class MemoryCardRepository(
    context: Context,
    private val core: EmulatorCore,
    private val preferences: AppPreferences,
    private val sync: SaveFolderSync,
) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val changes = MutableStateFlow(0)

    val cards: Flow<List<MemoryCard>> = changes.map { list() }

    suspend fun list(): List<MemoryCard> = withContext(Dispatchers.IO) {
        core.memoryCardsDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(SAVE_EXTENSION) }
            .map { MemoryCard(it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.modified }
    }

    val location: Flow<SaveLocationState> = combine(preferences.saveLocation, preferences.gameFolder, changes) { location, gameFolder, _ ->
        withContext(Dispatchers.IO) {
            SaveLocationState(
                location = location,
                folderName = location.folder?.let(::folderName),
                writable = sync.folder() != null,
                hasGameFolder = gameFolder != null,
            )
        }
    }

    /**
     * Keeps the copies of the saves in [mode]'s folder from now on: [folder] (a SAF tree the user
     * picked) for [SaveLocation.Mode.Folder]. The saves go there at once, named after their games
     * in [names] (file names without the extension, by serial).
     */
    suspend fun setLocation(mode: SaveLocation.Mode, folder: Uri?, names: Map<String, String>) = withContext(Dispatchers.IO) {
        folder?.let(sync::takeAccess)
        preferences.setSaveLocation(mode, folder)
        // The running game sends its save to the folder it started with; it reaches this one on the next start.
        if (!gameRunning()) sync.pushAll(names)
        changed()
    }

    /** Takes write access to the game folder [tree], which older versions did not ask for. */
    suspend fun allowGameFolder(tree: Uri, names: Map<String, String>) = withContext(Dispatchers.IO) {
        sync.takeAccess(tree)
        if (!gameRunning()) sync.pushAll(names)
        changed()
    }

    suspend fun delete(card: MemoryCard): MemoryCardResult = withContext(Dispatchers.IO) {
        if (gameRunning()) return@withContext MemoryCardResult.Busy
        sync.delete(card.serial)
        BatteryFiles.delete(File(core.memoryCardsDir, card.name))
        BatteryFiles.delete(File(core.memoryCardsDir, "${card.serial}$CLOCK_EXTENSION"))
        changed()
        MemoryCardResult.Ok
    }

    /** The earlier versions of [card], newest first. */
    suspend fun backups(card: MemoryCard): List<SaveBackup> = withContext(Dispatchers.IO) {
        val file = File(core.memoryCardsDir, card.name)
        (1..BatteryFiles.BACKUPS).mapNotNull { generation ->
            BatteryFiles.backup(file, generation).takeIf { it.isFile }?.let { SaveBackup(generation, it.length(), it.lastModified()) }
        }
    }

    /** Makes [backup] the save of [card] again; the save it replaces becomes the newest backup. */
    suspend fun restore(card: MemoryCard, backup: SaveBackup, name: String?): MemoryCardResult = withContext(Dispatchers.IO) {
        if (gameRunning()) return@withContext MemoryCardResult.Busy
        val file = File(core.memoryCardsDir, card.name)
        val bytes = runCatching { BatteryFiles.backup(file, backup.generation).readBytes() }.getOrNull()
            ?: return@withContext MemoryCardResult.Failed
        BatteryFiles.backUp(file)
        if (!BatteryFiles.writeAtomically(file, bytes)) return@withContext MemoryCardResult.Failed
        // A clock from another moment would be wrong for this save.
        BatteryFiles.delete(File(core.memoryCardsDir, "${card.serial}$CLOCK_EXTENSION"))
        sync.push(card.serial, name)
        changed()
        MemoryCardResult.Ok
    }

    /** The file name to offer when [card] is saved out, after the game's [title] when it is known. */
    fun exportName(card: MemoryCard, title: String? = null): String =
        (title?.replace('/', '_')?.trim()?.takeIf { it.isNotEmpty() } ?: card.serial) + SAVE_EXTENSION

    /** Copies [card] to [uri]. */
    suspend fun export(card: MemoryCard, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val source = File(core.memoryCardsDir, card.name)
        runCatching {
            val output = contentResolver.openOutputStream(uri, "wt") ?: return@runCatching false
            output.use { source.inputStream().use { input -> input.copyTo(it) } }
            true
        }.getOrDefault(false)
    }

    /**
     * Makes the save file at [uri] the battery save of the game with [serial], over the one it has.
     * [name] is the game's file name without the extension, for the copy in the save folder.
     * Invalid if it is empty or larger than any cartridge's SRAM.
     */
    suspend fun import(uri: Uri, serial: String, name: String?): MemoryCardResult = withContext(Dispatchers.IO) {
        if (gameRunning()) return@withContext MemoryCardResult.Busy
        core.memoryCardsDir.mkdirs()
        val target = File(core.memoryCardsDir, "$serial$SAVE_EXTENSION")
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                // One byte more than a save can have is enough to tell it is not one.
                val buffer = ByteArray(MAX_SAVE_BYTES + 1)
                var length = 0
                while (length < buffer.size) {
                    val n = input.read(buffer, length, buffer.size - length)
                    if (n < 0) break
                    length += n
                }
                buffer.copyOf(length)
            }
        }.getOrNull() ?: return@withContext MemoryCardResult.Failed
        if (bytes.isEmpty() || bytes.size > MAX_SAVE_BYTES) return@withContext MemoryCardResult.Invalid
        BatteryFiles.backUp(target)
        if (!BatteryFiles.writeAtomically(target, bytes)) return@withContext MemoryCardResult.Failed
        // A clock from another save would be wrong for this one.
        BatteryFiles.delete(File(core.memoryCardsDir, "$serial$CLOCK_EXTENSION"))
        sync.push(serial, name)
        changed()
        MemoryCardResult.Ok
    }

    /** Whether a game is running in the emulation process, which writes its save when it stops. */
    private fun gameRunning(): Boolean = isGameProcessRunning(appContext)

    private fun folderName(tree: Uri): String? = runCatching {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    /** Lists the saves again, after something else changed the folder. */
    fun refresh() = changed()

    private fun changed() {
        changes.value += 1
    }

    private companion object {
        const val SAVE_EXTENSION = ".srm"
        const val CLOCK_EXTENSION = ".rtc"

        /** The largest SRAM a cartridge has is 512 KB (with SA-1 BW-RAM); anything bigger is not a save. */
        const val MAX_SAVE_BYTES = 1024 * 1024
    }
}
