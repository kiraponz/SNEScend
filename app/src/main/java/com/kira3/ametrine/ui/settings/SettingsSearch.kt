package com.kira3.ametrine.ui.settings

import com.kira3.ametrine.emulation.EmulationActivity
import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kira3.ametrine.R
import java.util.Locale

/** Settings whose title, summary, section or category contain every word of the query. */
internal class SearchGroup(val category: SettingsCategory, val section: SettingsSection, val settings: List<Setting>)

/**
 * The settings matching [query] across all categories, advanced sections included, grouped
 * by section. [visible] says whether a setting would be shown at all (game-only rows
 * outside a game, rows hidden by another setting's value).
 */
internal fun searchSettings(resources: Resources, query: String, visible: (Setting) -> Boolean): List<SearchGroup> {
    val words = query.lowercase(Locale.ROOT).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    fun text(res: Int) = if (res == 0) "" else resources.getString(res).lowercase(Locale.ROOT)
    return SettingsCategory.entries.flatMap { category ->
        category.sections.mapNotNull { section ->
            val context = text(category.title) + " " + text(section.title)
            val matches = section.settings.filter { setting ->
                val haystack = text(setting.title) + " " + text(setting.summary) + " " + context
                visible(setting) && words.all { it in haystack }
            }
            if (matches.isEmpty()) null else SearchGroup(category, section, matches)
        }
    }
}

/**
 * Search across every category of the global settings, or of the game [serial]. Rows are the
 * ordinary setting rows, so a result can be changed (and a game's override reset) in place.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SettingsSearchScreen(
    serial: String?,
    gameTitle: String?,
    onOpenBios: () -> Unit,
    onOpenMemoryCards: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(serial)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isGame = serial != null
    var query by rememberSaveable { mutableStateOf("") }
    // Beside the category list there is nothing to go back to.
    val twoPane = LocalListDetailSceneScope.current != null
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val resources = LocalContext.current.resources

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val context = LocalContext.current
    val actions = remember(viewModel, onOpenBios, onOpenMemoryCards) {
        object : SettingsActions {
            override fun set(path: String, value: Any) = viewModel.set(path, value)
            override fun reset(path: String) = viewModel.reset(listOf(path))
            override fun openBios() = onOpenBios()
            override fun openMemoryCards() = onOpenMemoryCards()
            override fun openPadLayout() = context.startActivity(EmulationActivity.padLayoutIntent(context, viewModel.serial))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(stringResource(if (isGame) R.string.settings_search_game_hint else R.string.settings_search_hint))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                },
                navigationIcon = { if (!twoPane) BackButton(onBack) },
                actions = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_search_clear))
                        }
                    }
                },
                colors = settingsTopBarColors(),
            )
        },
    ) { innerPadding ->
        val current = state ?: return@Scaffold
        val groups = searchSettings(resources, query) { setting ->
            (isGame || !setting.gameOnly) && (!isGame || !setting.globalOnly) && setting.visibleWhen?.invoke(current.values) != false
        }
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding).padding(32.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    stringResource(if (query.isBlank()) R.string.settings_search_prompt else R.string.settings_search_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            for (group in groups) {
                item(key = "header:${group.category.name}:${group.section.title}") {
                    Text(
                        stringResource(R.string.settings_search_group, stringResource(group.category.title), stringResource(group.section.title)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
                    )
                }
                itemsIndexed(group.settings, key = { _, setting -> "setting:${setting.path ?: setting.title}" }) { index, setting ->
                    SettingRow(
                        setting = setting,
                        state = current,
                        isGame = isGame,
                        shapes = segmentedShapes(index, group.settings.size),
                        actions = actions,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}
