package com.kira3.ametrine.ui.settings

import androidx.compose.ui.platform.LocalContext
import com.kira3.ametrine.emulation.EmulationActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kira3.ametrine.R

/**
 * The list of settings categories: the global settings when [serial] is null, else the
 * settings of that game. [selected] is the category open beside it in a two-pane layout.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsHomeScreen(
    serial: String?,
    gameTitle: String?,
    selected: SettingsCategory?,
    onOpenCategory: (SettingsCategory) -> Unit,
    onOpenSearch: () -> Unit,
    cheatsSelected: Boolean,
    onOpenCheats: () -> Unit,
    macrosSelected: Boolean,
    onOpenMacros: () -> Unit,
    backupSelected: Boolean,
    onOpenBackup: () -> Unit,
    statesSelected: Boolean,
    onOpenStates: () -> Unit,
    aboutSelected: Boolean,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(serial)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isGame = serial != null
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(gameTitle ?: stringResource(R.string.settings_title), maxLines = 2) },
                subtitle = if (isGame) ({ Text(stringResource(R.string.settings_game_subtitle)) }) else null,
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.settings_search))
                    }
                    if (state?.overrides?.isNotEmpty() == true) {
                        IconButton(onClick = { confirmReset = true }) {
                            Icon(
                                painterResource(R.drawable.ic_settings_backup_restore),
                                contentDescription = stringResource(R.string.settings_reset_game),
                            )
                        }
                    }
                },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val categories = SettingsCategory.entries
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(categories, key = { _, category -> category.name }) { index, category ->
                val overridden = state?.overrides.orEmpty().count { it in category.paths }
                SegmentedListItem(
                    selected = category == selected,
                    onClick = { onOpenCategory(category) },
                    shapes = segmentedShapes(index, categories.size),
                    colors = settingsListColors(highlightSelected = true),
                    leadingContent = { CategoryIcon(category, index) },
                    supportingContent = {
                        Text(
                            if (overridden > 0) {
                                stringResource(R.string.settings_overridden_count, overridden)
                            } else {
                                stringResource(category.summary)
                            },
                            color = if (overridden > 0) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    },
                ) { Text(stringResource(category.title)) }
            }
            item(key = "cheats") {
                SegmentedListItem(
                    selected = cheatsSelected,
                    onClick = onOpenCheats,
                    shapes = ListItemDefaults.segmentedShapes(0, 2),
                    colors = settingsListColors(highlightSelected = true),
                    modifier = Modifier.animateItem().padding(top = 12.dp),
                    leadingContent = { ShapedIcon(MaterialShapes.Flower.toShape(), R.drawable.ic_cheats, categories.size) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    supportingContent = { Text(stringResource(R.string.settings_cheats_summary)) },
                ) { Text(stringResource(R.string.settings_cheats)) }
            }
            item(key = "macros") {
                SegmentedListItem(
                    selected = macrosSelected,
                    onClick = onOpenMacros,
                    shapes = ListItemDefaults.segmentedShapes(1, 2),
                    colors = settingsListColors(highlightSelected = true),
                    modifier = Modifier.animateItem(),
                    leadingContent = { ShapedIcon(MaterialShapes.Pill.toShape(), R.drawable.ic_macro, categories.size + 3) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    supportingContent = { Text(stringResource(R.string.settings_macros_summary)) },
                ) { Text(stringResource(R.string.settings_macros)) }
            }
            // The app's own data, not a game's.
            if (!isGame) {
                item(key = "states") {
                    SegmentedListItem(
                        selected = statesSelected,
                        onClick = onOpenStates,
                        shapes = ListItemDefaults.segmentedShapes(0, 2),
                        colors = settingsListColors(highlightSelected = true),
                        modifier = Modifier.animateItem().padding(top = 12.dp),
                        leadingContent = { ShapedIcon(MaterialShapes.Cookie4Sided.toShape(), R.drawable.ic_rewind, categories.size + 1) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        supportingContent = { Text(stringResource(R.string.settings_states_summary)) },
                    ) { Text(stringResource(R.string.settings_states)) }
                }
                item(key = "backup") {
                    SegmentedListItem(
                        selected = backupSelected,
                        onClick = onOpenBackup,
                        shapes = ListItemDefaults.segmentedShapes(1, 2),
                        colors = settingsListColors(highlightSelected = true),
                        modifier = Modifier.animateItem(),
                        leadingContent = { ShapedIcon(MaterialShapes.Burst.toShape(), R.drawable.ic_settings_backup_restore, categories.size + 2) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        supportingContent = { Text(stringResource(R.string.settings_backup_summary)) },
                    ) { Text(stringResource(R.string.settings_backup)) }
                }
                item(key = "about") {
                    SegmentedListItem(
                        selected = aboutSelected,
                        onClick = onOpenAbout,
                        shapes = ListItemDefaults.segmentedShapes(0, 1),
                        colors = settingsListColors(highlightSelected = true),
                        modifier = Modifier.animateItem().padding(top = 12.dp),
                        leadingContent = { ShapedIcon(MaterialShapes.SoftBurst.toShape(), R.drawable.ic_info, categories.size + 4) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        supportingContent = { Text(stringResource(R.string.settings_about_summary)) },
                    ) { Text(stringResource(R.string.settings_about)) }
                }
            }
        }
    }

    if (confirmReset) {
        ConfirmResetDialog(
            text = stringResource(R.string.settings_reset_game_confirm),
            onConfirm = {
                confirmReset = false
                state?.let { viewModel.reset(it.overrides) }
            },
            onDismiss = { confirmReset = false },
        )
    }
}

/** The category's icon on an Expressive shape, each category with its own shape and color. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CategoryIcon(category: SettingsCategory, index: Int) {
    val shape: Shape = when (category) {
        SettingsCategory.Graphics -> MaterialShapes.Cookie9Sided
        SettingsCategory.Performance -> MaterialShapes.Clover4Leaf
        SettingsCategory.Audio -> MaterialShapes.Sunny
        SettingsCategory.Controls -> MaterialShapes.Gem
        SettingsCategory.System -> MaterialShapes.Pentagon
        SettingsCategory.Miscellaneous -> MaterialShapes.Cookie12Sided
    }.toShape()
    ShapedIcon(shape, category.icon, index)
}

/** [icon] on [shape], colored by the row's [index] so neighbors differ. */
@Composable
internal fun ShapedIcon(shape: Shape, icon: Int, index: Int) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (index % 3) {
        0 -> colors.primaryContainer to colors.onPrimaryContainer
        // Not secondaryContainer, which is the selected row's color.
        1 -> colors.primary to colors.onPrimary
        else -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    Box(
        Modifier.size(44.dp).clip(shape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
    }
}

/** Shown beside the category list on a large screen until a category is picked. */
@Composable
fun SettingsDetailPlaceholder() {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.settings_pick_category),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/** One category's settings, for the global settings or for the game [serial]. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SettingsCategoryScreen(
    category: SettingsCategory,
    serial: String?,
    gameTitle: String?,
    onOpenBios: () -> Unit,
    onOpenMemoryCards: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(serial)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isGame = serial != null
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Beside the category list there is nothing to go back to.
    val twoPane = LocalListDetailSceneScope.current != null
    val overridden = state?.overrides.orEmpty().filter { it in category.paths }

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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(category.title)) },
                subtitle = gameTitle?.let { { Text(it, maxLines = 1) } },
                navigationIcon = { if (!twoPane) BackButton(onBack) },
                actions = {
                    if (overridden.isNotEmpty()) {
                        IconButton(onClick = { confirmReset = true }) {
                            Icon(
                                painterResource(R.drawable.ic_settings_backup_restore),
                                contentDescription = stringResource(R.string.settings_reset_category),
                            )
                        }
                    }
                },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val current = state ?: return@Scaffold
        val (basic, advanced) = category.sections.partition { !it.advanced }
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            for (section in basic) section(section, current, isGame, actions)
            if (advanced.isNotEmpty()) {
                item(key = "advanced") {
                    AdvancedToggle(
                        expanded = showAdvanced,
                        onToggle = { showAdvanced = !showAdvanced },
                        modifier = Modifier.animateItem().padding(top = 16.dp),
                    )
                }
                if (showAdvanced) for (section in advanced) section(section, current, isGame, actions)
            }
        }
    }

    if (confirmReset) {
        ConfirmResetDialog(
            text = stringResource(R.string.settings_reset_category_confirm, stringResource(category.title)),
            onConfirm = {
                confirmReset = false
                viewModel.reset(overridden)
            },
            onDismiss = { confirmReset = false },
        )
    }
}

/** A section: its header, then its visible settings as one segmented group. */
private fun LazyListScope.section(
    section: SettingsSection,
    state: SettingsUiState,
    isGame: Boolean,
    actions: SettingsActions,
) {
    val settings = section.settings.filter { (isGame || !it.gameOnly) && (!isGame || !it.globalOnly) && it.visibleWhen?.invoke(state.values) != false }
    if (settings.isEmpty()) return
    item(key = "header:${section.title}") {
        Text(
            stringResource(section.title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.animateItem().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
        )
    }
    itemsIndexed(settings, key = { _, setting -> "setting:${setting.path ?: setting.title}" }) { index, setting ->
        SettingRow(
            setting = setting,
            state = state,
            isGame = isGame,
            shapes = segmentedShapes(index, settings.size),
            actions = actions,
            modifier = Modifier.animateItem(),
        )
    }
}

@Composable
private fun AdvancedToggle(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    SegmentedListItem(
        onClick = onToggle,
        shapes = segmentedShapes(0, 1),
        colors = settingsListColors(),
        modifier = modifier,
        trailingContent = {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.rotate(rotation))
        },
        supportingContent = { Text(stringResource(R.string.settings_advanced_options_summary)) },
    ) {
        Text(stringResource(if (expanded) R.string.settings_hide_advanced else R.string.settings_show_advanced))
    }
}

@Composable
private fun ConfirmResetDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reset_title)) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_reset)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
    }
}

/** Side gutters for the grouped lists, and room for the navigation bar below the last item. */
@Composable
internal fun listPadding(innerPadding: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    val bottom = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    return PaddingValues(
        start = innerPadding.calculateStartPadding(direction) + 16.dp,
        end = innerPadding.calculateEndPadding(direction) + 16.dp,
        top = innerPadding.calculateTopPadding() + 8.dp,
        bottom = maxOf(innerPadding.calculateBottomPadding(), bottom) + 16.dp,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun settingsTopBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
)
