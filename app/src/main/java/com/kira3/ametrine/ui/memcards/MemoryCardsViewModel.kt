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
enum class MemoryCardMessage { Imported, Exported, Deleted, Invalid, Failed, Busy }

/** A game of the library a save can go to. */
data class SaveGame(val serial: String, val title: String)

/** A battery save, with the name of its game when the library has it. */
data class SaveEntry(val card: MemoryCard, val title: String?)

data class MemoryCardsUiState(
    /** Null while the save folder is being read. */
    val saves: List<SaveEntry>? = null,
    /** The library's games, by title, for an imported save to go to. */
    val games: List<SaveGame> = emptyList(),
    val busy: Boolean = false,
    val message: MemoryCardMessage? = null,
)

class MemoryCardsViewModel(
    private val repository: MemoryCardRepository,
    libraryRepository: LibraryRepository,
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
        busy,
        message,
    ) { cards, games, busy, message ->
        val titles = games.mapNotNull { game -> game.dataSerial?.let { it to game.title } }.toMap()
        MemoryCardsUiState(
            saves = cards.map { SaveEntry(it, titles[it.serial]) },
            games = titles.map { (serial, title) -> SaveGame(serial, title) }.sortedBy { it.title.lowercase() },
            busy = busy,
            message = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryCardsUiState())

    /** Makes the file at [uri] the battery save of the game with [serial]. */
    fun import(uri: Uri, serial: String) = run(MemoryCardMessage.Imported) { repository.import(uri, serial) }

    fun export(card: MemoryCard, uri: Uri) = run(MemoryCardMessage.Exported) {
        if (repository.export(card, uri)) MemoryCardResult.Ok else MemoryCardResult.Failed
    }

    fun delete(card: MemoryCard) = run(MemoryCardMessage.Deleted) { repository.delete(card) }

    fun exportName(entry: SaveEntry): String = repository.exportName(entry.card, entry.title)

    fun onMessageShown() = message.update { null }

    private fun run(success: MemoryCardMessage, action: suspend () -> MemoryCardResult) {
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
