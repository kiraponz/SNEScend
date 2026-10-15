package com.kira3.ametrine.ui.memcards

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kira3.ametrine.R
import com.kira3.ametrine.data.MemoryCard
import com.kira3.ametrine.ui.settings.BackButton
import com.kira3.ametrine.ui.settings.listPadding
import com.kira3.ametrine.ui.settings.segmentedShapes
import com.kira3.ametrine.ui.settings.settingsListColors
import com.kira3.ametrine.ui.settings.settingsTopBarColors
import java.util.Date

/**
 * The games' battery saves: copy one out to a file, put a file in as a game's save (from another
 * emulator, say), or delete one. A game writes its save when it stops.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MemoryCardsScreen(
    onBack: () -> Unit,
    viewModel: MemoryCardsViewModel = viewModel(factory = MemoryCardsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<SaveEntry?>(null) }
    var exporting by remember { mutableStateOf<SaveEntry?>(null) }
    /** The game a picked file goes to; the file picker opens once one is chosen. */
    var importTarget by remember { mutableStateOf<String?>(null) }
    var choosingGame by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Beside the settings list there is nothing to go back to.
    val twoPane = LocalListDetailSceneScope.current != null
    val snackbarHostState = remember { SnackbarHostState() }

    val pickSave = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val serial = importTarget
        importTarget = null
        if (uri != null && serial != null) viewModel.import(uri, serial)
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = exporting
        exporting = null
        if (uri != null && entry != null) viewModel.export(entry.card, uri)
    }
    fun importInto(serial: String) {
        importTarget = serial
        pickSave.launch(arrayOf("*/*"))
    }

    val messages = mapOf(
        MemoryCardMessage.Imported to stringResource(R.string.memcards_imported),
        MemoryCardMessage.Exported to stringResource(R.string.memcards_exported),
        MemoryCardMessage.Deleted to stringResource(R.string.memcards_deleted),
        MemoryCardMessage.Invalid to stringResource(R.string.memcards_invalid),
        MemoryCardMessage.Failed to stringResource(R.string.memcards_failed),
        MemoryCardMessage.Busy to stringResource(R.string.memcards_busy),
    )
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        messages[message]?.let { snackbarHostState.showSnackbar(it) }
        viewModel.onMessageShown()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.memcards_title)) },
                navigationIcon = { if (!twoPane) BackButton(onBack) },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!state.busy) choosingGame = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.memcards_import)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val saves = state.saves
        if (saves == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(innerPadding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            if (saves.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.memcards_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(saves, key = { _, entry -> entry.card.name }) { index, entry ->
                SegmentedListItem(
                    onClick = {},
                    shapes = segmentedShapes(index, saves.size),
                    colors = settingsListColors(),
                    modifier = Modifier.animateItem(),
                    trailingContent = {
                        SaveMenu(
                            enabled = !state.busy,
                            onExport = {
                                exporting = entry
                                saveFile.launch(viewModel.exportName(entry))
                            },
                            onReplace = { importInto(entry.card.serial) },
                            onDelete = { deleting = entry },
                        )
                    },
                    supportingContent = { Text(entry.details()) },
                ) {
                    Text(entry.title ?: entry.card.serial, maxLines = 1)
                }
            }
            item(key = "about") {
                Text(
                    stringResource(R.string.memcards_about),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Room for the floating button over the last line.
                    modifier = Modifier.animateItem().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 72.dp),
                )
            }
        }
    }

    if (choosingGame) {
        GameDialog(
            games = state.games,
            onPick = {
                choosingGame = false
                importInto(it.serial)
            },
            onDismiss = { choosingGame = false },
        )
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.memcards_delete_title, entry.title ?: entry.card.serial)) },
            text = { Text(stringResource(R.string.memcards_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(entry.card)
                }) { Text(stringResource(R.string.memcards_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun SaveMenu(enabled: Boolean, onExport: () -> Unit, onReplace: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.memcards_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.memcards_export)) },
                onClick = { open = false; onExport() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.memcards_replace)) },
                onClick = { open = false; onReplace() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.memcards_delete)) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

/** The game's serial, the save's size and when the game last wrote it. */
@Composable
private fun SaveEntry.details(): String {
    val context = LocalContext.current
    val date = DateFormat.getMediumDateFormat(context).format(Date(card.modified))
    return "${card.serial} · ${Formatter.formatShortFileSize(context, card.bytes)} · $date"
}

/** Picks the library game an imported save goes to. */
@Composable
private fun GameDialog(games: List<SaveGame>, onPick: (SaveGame) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memcards_import_for)) },
        text = {
            if (games.isEmpty()) {
                Text(stringResource(R.string.memcards_import_no_games))
            } else {
                LazyColumn {
                    itemsIndexed(games, key = { _, game -> game.serial }) { _, game ->
                        Text(
                            game.title,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(game) }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
