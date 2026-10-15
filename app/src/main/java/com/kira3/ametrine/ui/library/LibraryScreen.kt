package com.kira3.ametrine.ui.library

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import com.kira3.ametrine.ui.components.DiscItem
import com.kira3.ametrine.ui.components.DiscSheet
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kira3.ametrine.data.LibrarySort
import android.text.format.DateUtils
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kira3.ametrine.R
import com.kira3.ametrine.data.Game
import com.kira3.ametrine.emulation.EmulationActivity
import com.kira3.ametrine.ui.theme.AmetrineTheme

@Composable
fun LibraryScreen(
    onOpenSettings: () -> Unit,
    onOpenGameSettings: (serial: String, title: String) -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickGameFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::onGameFolderPicked)
    }

    // A game may have left new play time and suspend states in the :emu process.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.reloadRecords()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.launchRequest) {
        val request = state.launchRequest ?: return@LaunchedEffect
        context.startActivity(EmulationActivity.intent(context, request.gameUri, request.serial))
        viewModel.onLaunchHandled()
    }

    LaunchedEffect(state.settingsRequest) {
        val request = state.settingsRequest ?: return@LaunchedEffect
        onOpenGameSettings(request.serial, request.title)
        viewModel.onSettingsRequestHandled()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = state.message?.let { stringResource(it.textRes) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.onMessageShown()
        }
    }

    LibraryContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onOpenSettings = onOpenSettings,
        onPickGameFolder = { pickGameFolder.launch(null) },
        onRefresh = viewModel::refresh,
        onGameClick = viewModel::onGameClicked,
        onGameSettingsClick = viewModel::onGameSettingsClicked,
        onSortSelected = viewModel::onSortSelected,
        onQueryChanged = viewModel::onQueryChanged,
        onFavoriteToggled = viewModel::onFavoriteToggled,
        onRenamed = viewModel::onRenamed,
        onDiscPicked = viewModel::onDiscPicked,
        onDiscardConfirmed = viewModel::onDiscardConfirmed,
        onDiscardDismissed = viewModel::onDiscardDismissed,
        onAddShortcut = viewModel::onAddShortcutClicked,
    )
}

private val LibraryMessage.textRes: Int
    get() = when (this) {
        LibraryMessage.ScanFailed -> R.string.library_message_scan_failed
        LibraryMessage.CoreUnavailable -> R.string.library_message_core_unavailable
        LibraryMessage.NoSerial -> R.string.library_message_no_serial
        LibraryMessage.ShortcutUnsupported -> R.string.library_message_shortcut_unsupported
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContent(
    state: LibraryUiState,
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onPickGameFolder: () -> Unit,
    onRefresh: () -> Unit,
    onGameClick: (Game) -> Unit,
    onGameSettingsClick: (Game) -> Unit,
    onSortSelected: (LibrarySort) -> Unit,
    onQueryChanged: (String) -> Unit,
    onFavoriteToggled: (Game) -> Unit,
    onRenamed: (Game, String) -> Unit,
    onDiscPicked: (LibraryEntry, Game) -> Unit,
    onDiscardConfirmed: () -> Unit,
    onDiscardDismissed: () -> Unit,
    onAddShortcut: (Game) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var unrecognizedOpen by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Game?>(null) }
    var choosingDisc by remember { mutableStateOf<LibraryEntry?>(null) }
    choosingDisc?.let { entry ->
        DiscDialog(
            entry = entry,
            onPick = {
                choosingDisc = null
                onDiscPicked(entry, it)
            },
            onDismiss = { choosingDisc = null },
        )
    }
    state.discardRequest?.let { disc ->
        AlertDialog(
            onDismissRequest = onDiscardDismissed,
            title = { Text(stringResource(R.string.library_discard_title)) },
            text = { Text(stringResource(R.string.library_discard_text)) },
            confirmButton = {
                TextButton(onClick = onDiscardConfirmed) { Text(stringResource(R.string.library_discard_confirm)) }
            },
            dismissButton = { TextButton(onClick = onDiscardDismissed) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
    renaming?.let { game ->
        RenameDialog(
            game = game,
            onConfirm = {
                onRenamed(game, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = onQueryChanged,
                            placeholder = { Text(stringResource(R.string.library_search_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {}),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else {
                        Text(stringResource(R.string.library_title))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searching) onQueryChanged("")
                        searching = !searching
                    }) {
                        Icon(
                            if (searching) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = stringResource(
                                if (searching) R.string.library_search_close else R.string.library_search,
                            ),
                        )
                    }
                    IconButton(onClick = { sortOpen = true }) {
                        Icon(painterResource(R.drawable.ic_sort), contentDescription = stringResource(R.string.library_sort))
                    }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        LibrarySort.entries.forEach { sort ->
                            DropdownMenuItem(
                                leadingIcon = { RadioButton(selected = sort == state.sort, onClick = null) },
                                text = { Text(stringResource(sort.labelRes)) },
                                onClick = {
                                    sortOpen = false
                                    onSortSelected(sort)
                                },
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_change_game_folder)) },
                            onClick = {
                                menuOpen = false
                                onPickGameFolder()
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val games = state.games
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                // Nothing to show yet; the refresh indicator covers the wait.
                games == null -> Unit
                !state.hasGameFolder -> EmptyState(
                    text = stringResource(R.string.library_no_folder),
                    actionText = stringResource(R.string.library_select_game_folder),
                    onAction = onPickGameFolder,
                )
                games.isEmpty() && state.unrecognized.isEmpty() && state.gameCount > 0 ->
                    EmptyMessage(stringResource(R.string.library_no_matches))
                games.isEmpty() && state.unrecognized.isEmpty() -> if (!state.refreshing) {
                    EmptyState(
                        text = stringResource(R.string.library_empty),
                        actionText = stringResource(R.string.library_change_game_folder),
                        onAction = onPickGameFolder,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(games, key = { it.key }) { entry ->
                        val game = entry.game
                        GameRow(
                            game = game,
                            discCount = entry.discCount,
                            onClick = { onGameClick(game) },
                            onSettings = { onGameSettingsClick(game) },
                            onFavoriteToggled = { onFavoriteToggled(game) },
                            onAddShortcut = { onAddShortcut(game) },
                            onChooseDisc = { choosingDisc = entry }.takeIf { entry.discCount > 1 },
                            onRename = { renaming = game },
                            onResetTitle = { onRenamed(game, "") },
                        )
                    }
                    if (state.unrecognized.isNotEmpty()) {
                        item(key = "unrecognized-header") {
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(R.string.library_unrecognized, state.unrecognized.size))
                                },
                                supportingContent = { Text(stringResource(R.string.library_unrecognized_summary)) },
                                trailingContent = {
                                    Icon(
                                        if (unrecognizedOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null,
                                    )
                                },
                                modifier = Modifier.clickable { unrecognizedOpen = !unrecognizedOpen },
                            )
                        }
                        if (unrecognizedOpen) {
                            items(state.unrecognized, key = { "unrecognized/" + it.uri }) { game ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onGameClick(game) }
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                ) {
                                    Text(game.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                    Text(
                                        game.fileDetails(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameRow(
    game: Game,
    discCount: Int,
    onClick: () -> Unit,
    onSettings: () -> Unit,
    onFavoriteToggled: () -> Unit,
    onAddShortcut: () -> Unit,
    onChooseDisc: (() -> Unit)?,
    onRename: () -> Unit,
    onResetTitle: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(game.title, maxLines = 2) },
            supportingContent = { Text(game.details(discCount), maxLines = 2) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onFavoriteToggled) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = stringResource(
                                if (game.record.favorite) R.string.library_unfavorite else R.string.library_favorite,
                            ),
                            tint = if (game.record.favorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(
                            painterResource(R.drawable.ic_tune),
                            contentDescription = stringResource(R.string.library_game_settings),
                        )
                    }
                }
            },
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (onChooseDisc != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_choose_disc)) },
                    onClick = {
                        menuOpen = false
                        onChooseDisc()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_add_shortcut)) },
                onClick = {
                    menuOpen = false
                    onAddShortcut()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_rename)) },
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            if (game.record.customTitle != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_reset_title)) },
                    onClick = {
                        menuOpen = false
                        onResetTitle()
                    },
                )
            }
        }
    }
}

/** The discs of a game, the one a tap starts marked; picking one starts the game from it. */
@Composable
private fun DiscDialog(entry: LibraryEntry, onPick: (Game) -> Unit, onDismiss: () -> Unit) {
    DiscSheet(
        title = stringResource(R.string.library_choose_disc),
        discs = entry.discs.mapIndexed { i, disc -> DiscItem(i + 1, disc.discTitle, disc.uri == entry.game.uri) },
        currentLabel = stringResource(R.string.library_disc_last_played),
        onPick = { onPick(entry.discs[it]) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun RenameDialog(game: Game, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(game.uri) { mutableStateOf(game.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_rename)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.library_rename_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

private val LibrarySort.labelRes: Int
    get() = when (this) {
        LibrarySort.RecentlyPlayed -> R.string.library_sort_recently_played
        LibrarySort.Title -> R.string.library_sort_title
        LibrarySort.PlayTime -> R.string.library_sort_play_time
        LibrarySort.RecentlyAdded -> R.string.library_sort_recently_added
        LibrarySort.Size -> R.string.library_sort_size
    }

/** Format and size, e.g. "ISO · 4.3 GB". */
@Composable
private fun Game.fileDetails(): String {
    val size = Formatter.formatShortFileSize(LocalContext.current, sizeBytes)
    return listOfNotNull(format.ifEmpty { null }, size.takeIf { sizeBytes > 0 }).joinToString(" · ")
}

/**
 * Serial, format and size, then last played and play time, e.g. "SLUS-20312 · CHD · 2.1 GB". A game
 * on several discs gives the number of discs in place of the serial and size.
 */
@Composable
private fun Game.details(discCount: Int): String {
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, sizeBytes)
    val fileInfo = if (discCount > 1) {
        listOfNotNull(stringResource(R.string.library_discs, discCount), format.ifEmpty { null })
    } else {
        listOfNotNull(serial, format.ifEmpty { null }, size.takeIf { sizeBytes > 0 })
    }.joinToString(" · ")
    val played = if (record.lastPlayed > 0) {
        val ago = DateUtils.getRelativeTimeSpanString(record.lastPlayed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        val time = playTimeText(record.playSeconds)
        stringResource(R.string.library_played, ago, time)
    } else {
        null
    }
    return listOfNotNull(
        fileInfo.takeIf { it.isNotEmpty() },
        played,
        stringResource(R.string.library_resumable).takeIf { resumable },
    ).joinToString("\n")
}

@Composable
private fun playTimeText(seconds: Long): String {
    val minutes = seconds / 60
    return when {
        minutes < 1 -> stringResource(R.string.library_play_time_short)
        minutes < 60 -> stringResource(R.string.library_play_time_minutes, minutes)
        else -> stringResource(R.string.library_play_time_hours, minutes / 60, minutes % 60)
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyState(text: String, actionText: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(onClick = onAction) { Text(actionText) }
    }
}

@Preview
@Composable
private fun LibraryContentPreview() {
    AmetrineTheme {
        LibraryContent(
            state = LibraryUiState(
                hasGameFolder = true,
                games = listOf(LibraryEntry(Game(Uri.EMPTY, "Example Game (USA).sfc", "SNES-B19ED489", true, 2_100_000_000L), listOf(Game(Uri.EMPTY, "Example Game (USA).sfc", "SNES-B19ED489", true, 2_100_000_000L)), "example")),
            ),
            snackbarHostState = SnackbarHostState(),
            onOpenSettings = {},
            onPickGameFolder = {},
            onRefresh = {},
            onGameClick = {},
            onGameSettingsClick = {},
            onSortSelected = {},
            onQueryChanged = {},
            onFavoriteToggled = {},
            onRenamed = { _, _ -> },
            onDiscPicked = { _, _ -> },
            onDiscardConfirmed = {},
            onDiscardDismissed = {},
            onAddShortcut = {},
        )
    }
}
