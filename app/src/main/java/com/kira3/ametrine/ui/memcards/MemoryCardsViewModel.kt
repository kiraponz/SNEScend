package com.kira3.ametrine.ui.memcards

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kira3.ametrine.AmetrineApplication
import com.kira3.ametrine.data.LibraryRepository
import com.kira3.ametrine.data.MemoryCard
import com.kira3.ametrine.data.MemoryCardRepository
import com.kira3.ametrine.data.MemoryCardResult
import com.kira3.ametrine.data.SaveBackup
import com.kira3.ametrine.data.SaveFolderSync
import com.kira3.ametrine.data.SaveLocation
import com.kira3.ametrine.data.SaveLocationState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What to tell the user once, after an action. */
enum class MemoryCardMessage { Imported, Exported, Deleted, Restored, Invalid, Failed, Busy, NotGameFolder }

/** A game of the library a save can go to. */
data class SaveGame(val serial: String, val title: String)

/** A battery save, with the name of its game when the library has it. */
data class SaveEntry(val card: MemoryCard, val title: String?)

data class MemoryCardsUiState(
    /** Null while the save folder is being read. */
    val saves: List<SaveEntry>? = null,
    /** The library's games, by title, for an imported save to go to. */
    val games: List<SaveGame> = emptyList(),
    /** Null while it is being read. */
    val location: SaveLocationState? = null,
    val busy: Boolean = false,
    val message: MemoryCardMessage? = null,
)

class MemoryCardsViewModel(
    private val repository: MemoryCardRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<MemoryCardMessage?>(null)

    private val games = libraryRepository.games.catch { emit(emptyList()) }

    val uiState: StateFlow<MemoryCardsUiState> = combine(
        repository.cards.catch {
            Log.e(TAG, "Listing battery saves failed", it)
            emit(emptyList())
        },
        games,
        repository.location.catch { Log.e(TAG, "Reading the save location failed", it) },
        busy,
        message,
    ) { cards, games, location, busy, message ->
        val titles = games.mapNotNull { game -> game.dataSerial?.let { it to game.title } }.toMap()
        MemoryCardsUiState(
            saves = cards.map { SaveEntry(it, titles[it.serial]) },
            games = titles.map { (serial, title) -> SaveGame(serial, title) }.sortedBy { it.title.lowercase() },
            location = location,
            busy = busy,
            message = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryCardsUiState())

    /** Makes the file at [uri] the battery save of the game with [serial]. */
    fun import(uri: Uri, serial: String) = run(MemoryCardMessage.Imported) { repository.import(uri, serial, saveNames()[serial]) }

    fun export(card: MemoryCard, uri: Uri) = run(MemoryCardMessage.Exported) {
        if (repository.export(card, uri)) MemoryCardResult.Ok else MemoryCardResult.Failed
    }

    fun delete(card: MemoryCard) = run(MemoryCardMessage.Deleted) { repository.delete(card) }

    suspend fun backups(card: MemoryCard): List<SaveBackup> = repository.backups(card)

    fun restore(card: MemoryCard, backup: SaveBackup) = run(MemoryCardMessage.Restored) { repository.restore(card, backup, saveNames()[card.serial]) }

    /** Keeps the copies of the saves in the game folder, or in the folder at [folder] when it is given. */
    fun setLocation(mode: SaveLocation.Mode, folder: Uri? = null) = run(null) {
        repository.setLocation(mode, folder, saveNames())
        MemoryCardResult.Ok
    }

    /** The game folder, for the folder picker to open at. */
    suspend fun gameFolder(): Uri? = libraryRepository.gameFolder.first()

    /** The user picked [tree] to let the app write to the game folder. */
    fun allowGameFolder(tree: Uri) {
        viewModelScope.launch {
            if (tree != libraryRepository.gameFolder.first()) {
                message.value = MemoryCardMessage.NotGameFolder
                return@launch
            }
            run(null) {
                repository.allowGameFolder(tree, saveNames())
                MemoryCardResult.Ok
            }
        }
    }

    /** The names of the copies of the saves in the save folder, by serial: the games' file names. */
    private suspend fun saveNames(): Map<String, String> = runCatching {
        libraryRepository.games.first().mapNotNull { game ->
            val serial = game.dataSerial ?: return@mapNotNull null
            SaveFolderSync.nameOf(game.fileName)?.let { serial to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    fun exportName(entry: SaveEntry): String = repository.exportName(entry.card, entry.title)

    fun onMessageShown() = message.update { null }

    private fun run(success: MemoryCardMessage?, action: suspend () -> MemoryCardResult) {
        viewModelScope.launch {
            busy.value = true
            val result = withContext(NonCancellable) {
                runCatching { action() }
                    .onFailure { Log.e(TAG, "Battery save action failed", it) }
                    .getOrDefault(MemoryCardResult.Failed)
            }
            busy.value = false
            message.value = when (result) {
                MemoryCardResult.Ok -> success
                MemoryCardResult.Invalid -> MemoryCardMessage.Invalid
                MemoryCardResult.Busy -> MemoryCardMessage.Busy
                else -> MemoryCardMessage.Failed
            }
        }
    }

    companion object {
        private const val TAG = "MemoryCardsViewModel"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AmetrineApplication).container
                MemoryCardsViewModel(container.memoryCardRepository, container.libraryRepository)
            }
        }
    }
}
