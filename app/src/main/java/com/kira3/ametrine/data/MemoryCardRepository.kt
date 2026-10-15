package com.kira3.ametrine.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.kira3.ametrine.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/** A save that has an icon. SNES saves have none, so there are never any. */
class SaveIconChoice(val cardName: String, val saveName: String, val title: String?, val icon: Bitmap)

enum class MemoryCardResult { Ok, Exists, Invalid, Failed, Full, Busy }

/**
 * The games' battery saves in `<dataDir>/saves`. The core reads a game's save when it boots and
 * writes it when the game stops, under the game's serial, so these are plain files that the app
 * lists, copies in and out, and deletes. (MesenCE keeps a real-time clock beside the save, as
 * `<serial>.rtc`, for the few cartridges that have one; it goes with the save.)
 */
class MemoryCardRepository(
    context: Context,
    private val core: EmulatorCore,
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

    suspend fun delete(card: MemoryCard): MemoryCardResult = withContext(Dispatchers.IO) {
        if (gameRunning()) return@withContext MemoryCardResult.Busy
        File(core.memoryCardsDir, card.name).delete()
        File(core.memoryCardsDir, "${card.serial}$CLOCK_EXTENSION").delete()
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
     * Invalid if it is empty or larger than any cartridge's SRAM.
     */
    suspend fun import(uri: Uri, serial: String): MemoryCardResult = withContext(Dispatchers.IO) {
        if (gameRunning()) return@withContext MemoryCardResult.Busy
        core.memoryCardsDir.mkdirs()
        val target = File(core.memoryCardsDir, "$serial$SAVE_EXTENSION")
        val temp = File(core.memoryCardsDir, "$serial$SAVE_EXTENSION$TEMP_SUFFIX").apply { delete() }
        val result = runCatching {
            val input = contentResolver.openInputStream(uri) ?: return@runCatching MemoryCardResult.Failed
            input.use { temp.outputStream().use { out -> it.copyTo(out) } }
            when {
                temp.length() == 0L || temp.length() > MAX_SAVE_BYTES -> MemoryCardResult.Invalid
                // A clock from another save would be wrong for this one.
                temp.renameTo(target) -> MemoryCardResult.Ok.also { File(core.memoryCardsDir, "$serial$CLOCK_EXTENSION").delete() }
                else -> MemoryCardResult.Failed
            }
        }.getOrDefault(MemoryCardResult.Failed)
        temp.delete()
        changed()
        result
    }

    /** The icons of the games' saves. SNES saves have none. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun saveIcons(size: Int, fill: Float, serial: String? = null): List<SaveIconChoice> = emptyList()

    /** The icon of one save. SNES saves have none. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun saveIcon(cardName: String, saveName: String, size: Int, fill: Float): Bitmap? = null

    /** Whether a game is running in the emulation process, which writes its save when it stops. */
    private fun gameRunning(): Boolean = isGameProcessRunning(appContext)

    /** Lists the saves again, after something else changed the folder. */
    fun refresh() = changed()

    private fun changed() {
        changes.value += 1
    }

    private companion object {
        const val SAVE_EXTENSION = ".srm"
        const val CLOCK_EXTENSION = ".rtc"
        const val TEMP_SUFFIX = ".tmp"

        /** The largest SRAM a cartridge has is 512 KB (with SA-1 BW-RAM); anything bigger is not a save. */
        const val MAX_SAVE_BYTES = 1024L * 1024
    }
}
