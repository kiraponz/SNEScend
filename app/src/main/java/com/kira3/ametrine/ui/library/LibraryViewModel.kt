package com.kira3.ametrine.ui.library

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kira3.ametrine.AmetrineApplication
import com.kira3.ametrine.data.Game
import com.kira3.ametrine.data.LibraryRepository
import com.kira3.ametrine.data.LibrarySort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A game to start: what EmulationActivity needs from its Intent. */
data class LaunchRequest(val gameUri: Uri, val serial: String?)

/** A game whose settings to open. */
data class GameSettingsRequest(val serial: String, val title: String)

/** One row of the library: a game, or every disc of a multi-disc game. [game] is the disc a tap starts. */
class LibraryEntry(val game: Game, val discs: List<Game>, val key: String) {
    val discCount: Int get() = discs.size
}

data class LibraryUiState(
    val hasGameFolder: Boolean = false,
    /** Null until the cached list has been read. */
    val games: List<LibraryEntry>? = null,
    /** Files the core found no PS1 disc in; they sit under their own heading at the end. */
    val unrecognized: List<Game> = emptyList(),
    val refreshing: Boolean = false,
    val sort: LibrarySort = LibrarySort.RecentlyPlayed,
    val query: String = "",
    /** Files in the folder before the search, recognized or not; zero shows the empty-folder state rather than "no matches". */
    val gameCount: Int = 0,
    val launchRequest: LaunchRequest? = null,
    val settingsRequest: GameSettingsRequest? = null,
    /** A disc picked while another disc of the game has a suspended game: starting it would discard that. */
    val discardRequest: Game? = null,
    val message: LibraryMessage? = null,
)

enum class LibraryMessage { ScanFailed, CoreUnavailable, NoSerial, ShortcutUnsupported }

class LibraryViewModel(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {
    private val transient = MutableStateFlow(LibraryUiState())

    private val games = combine(
        libraryRepository.games.catch { emit(emptyList()) },
        libraryRepository.gameFolder,
    ) { games, folder -> if (folder == null) emptyList() else games }

    private val query = MutableStateFlow("")

    /** Favorites first, then games with a suspend state, then the chosen order; only games matching the search. */
    private val shown = combine(games, libraryRepository.sort, query) { games, sort, query ->
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        fun Game.matches() = words.all { word ->
            word in title.lowercase() ||
                word in fileName.lowercase() ||
                serial?.lowercase()?.contains(word) == true
        }
        // Files whose serial isn't read yet are in neither list, so a scan doesn't flash BIOS files in.
        val recognized = games.filter { it.recognized }
        val unrecognized = games.filter { it.unrecognized }
        // The discs of a game are one row; a tap starts the one that was in the drive last, else the first.
        val entries = recognized.groupBy { it.groupSerial ?: it.uri.toString() }.map { (key, discs) ->
            val lastDisc = discs.firstOrNull { it.uri.toString() == it.record.lastDisc }
            val ordered = discs.sortedBy { it.discIndex }
            LibraryEntry(lastDisc ?: ordered.first(), ordered, key) to discs
        }
        Shown(
            games = entries.filter { (_, discs) -> discs.any { it.matches() } }.map { it.first }
                .sortedWith(compareByDescending<LibraryEntry> { it.game.record.favorite }
                    .thenByDescending { it.game.resumable }
                    .thenComparing({ it.game }, sort.comparator())),
            unrecognized = unrecognized.filter { it.matches() }.sortedBy { it.fileName.lowercase() },
            count = recognized.size + unrecognized.size,
            sort = sort,
        )
    }

    private class Shown(val games: List<LibraryEntry>, val unrecognized: List<Game>, val count: Int, val sort: LibrarySort)

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryRepository.gameFolder,
        shown,
        libraryRepository.refreshing,
        transient,
    ) { folder, shown, refreshing, transient ->
        transient.copy(
            hasGameFolder = folder != null,
            games = shown.games,
            unrecognized = shown.unrecognized,
            gameCount = shown.count,
            sort = shown.sort,
            refreshing = refreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        refresh()
    }

    /** Looks for new and removed games in the game folder. */
    fun refresh() {
        viewModelScope.launch {
            runCatching { libraryRepository.refresh() }.onFailure {
                Log.e(TAG, "Refreshing the library failed", it)
                showMessage(LibraryMessage.ScanFailed)
            }
        }
    }

    /** Picks up what the :emu process wrote while a game was running. Call when the library comes back. */
    fun reloadRecords() = libraryRepository.reloadRecords()

    fun onSortSelected(sort: LibrarySort) {
        viewModelScope.launch { libraryRepository.setSort(sort) }
    }

    fun onQueryChanged(text: String) {
        query.value = text
        transient.update { it.copy(query = text) }
    }

    fun onFavoriteToggled(game: Game) {
        viewModelScope.launch { libraryRepository.setFavorite(game, !game.record.favorite) }
    }

    fun onRenamed(game: Game, title: String) {
        viewModelScope.launch { libraryRepository.setCustomTitle(game, title) }
    }

    fun onGameFolderPicked(uri: Uri) {
        viewModelScope.launch {
            libraryRepository.setGameFolder(uri)
            refresh()
        }
    }

    fun onGameClicked(game: Game) {
        viewModelScope.launch {
            readSerial(game) ?: return@launch
            runCatching { libraryRepository.markPlayed(game) }
            // The data of every disc of a game is kept under the first disc's serial.
            transient.update { it.copy(launchRequest = LaunchRequest(game.uri, game.dataSerial)) }
        }
    }

    /** A disc picked from the library's list for [entry]; the entry's own disc continues the suspended game. */
    fun onDiscPicked(entry: LibraryEntry, disc: Game) {
        if (disc.uri != entry.game.uri && entry.game.resumable) {
            transient.update { it.copy(discardRequest = disc) }
        } else {
            onGameClicked(disc)
        }
    }

    fun onDiscardConfirmed() {
        val disc = uiState.value.discardRequest ?: return
        transient.update { it.copy(discardRequest = null) }
        viewModelScope.launch {
            disc.dataSerial?.let { runCatching { libraryRepository.discardSuspendState(it) } }
            onGameClicked(disc)
        }
    }

    fun onDiscardDismissed() = transient.update { it.copy(discardRequest = null) }

    fun onAddShortcutClicked(game: Game) {
        viewModelScope.launch {
            readSerial(game) ?: return@launch
            pinShortcut(game, game.dataSerial)
        }
    }

    private fun pinShortcut(game: Game, serial: String?) {
        val pinned = runCatching { libraryRepository.requestPinnedShortcut(game, serial) }
            .onFailure { Log.e(TAG, "Pinning a shortcut for ${game.fileName} failed", it) }
            .getOrDefault(false)
        if (!pinned) showMessage(LibraryMessage.ShortcutUnsupported)
    }

    fun onGameSettingsClicked(game: Game) {
        viewModelScope.launch {
            readSerial(game) ?: return@launch
            // Per-game settings are stored by serial, the first disc's for a multi-disc game.
            val value = game.dataSerial
            if (value == null) {
                showMessage(LibraryMessage.NoSerial)
            } else {
                transient.update { it.copy(settingsRequest = GameSettingsRequest(value, game.title)) }
            }
        }
    }

    fun onLaunchHandled() = transient.update { it.copy(launchRequest = null) }

    fun onSettingsRequestHandled() = transient.update { it.copy(settingsRequest = null) }

    fun onMessageShown() = transient.update { it.copy(message = null) }

    /** The game's serial boxed (it can be null), or null after telling the user the core is unavailable. */
    private suspend fun readSerial(game: Game): Serial? {
        val result = runCatching { libraryRepository.readSerial(game) }
            .onFailure { Log.e(TAG, "Reading serial of ${game.fileName} failed", it) }
        if (result.exceptionOrNull() is IllegalStateException) {
            showMessage(LibraryMessage.CoreUnavailable)
            return null
        }
        return Serial(result.getOrNull())
    }

    private class Serial(val value: String?)

    private fun showMessage(message: LibraryMessage) = transient.update { it.copy(message = message) }

    companion object {
        private const val TAG = "LibraryViewModel"

        /**
         * An adaptive icon is 108dp and the launcher shows a circle of 72dp in the middle at most,
         * so the square picture's corners must stay inside that: 108 * 0.46 * sqrt(2) < 72.
         */

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AmetrineApplication).container
                LibraryViewModel(container.libraryRepository)
            }
        }
    }
}
