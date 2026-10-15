package com.kira3.ametrine.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.kira3.ametrine.R
import com.kira3.ametrine.ui.about.AboutScreen
import com.kira3.ametrine.ui.about.LicenseScreen
import com.kira3.ametrine.ui.about.LicensesScreen
import com.kira3.ametrine.ui.backup.BackupScreen
import com.kira3.ametrine.ui.backup.SaveStatesScreen
import com.kira3.ametrine.ui.bios.BiosScreen
import com.kira3.ametrine.ui.memcards.MemoryCardSavesScreen
import com.kira3.ametrine.ui.memcards.MemoryCardsScreen
import com.kira3.ametrine.ui.library.LibraryScreen
import com.kira3.ametrine.ui.settings.CheatGamesScreen
import com.kira3.ametrine.ui.settings.CheatsScreen
import com.kira3.ametrine.ui.settings.GpuDriverScreen
import com.kira3.ametrine.ui.settings.MacrosScreen
import com.kira3.ametrine.ui.settings.SettingsCategoryScreen
import com.kira3.ametrine.ui.settings.SettingsDetailPlaceholder
import com.kira3.ametrine.ui.settings.SettingsHomeScreen
import com.kira3.ametrine.ui.settings.SettingsSearchScreen

/**
 * Root of MainActivity's UI. The back stack is a plain list the app owns (Navigation 3).
 * Settings show as list and detail side by side when the window is wide enough.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AmetrineApp() {
    val backStack = rememberNavBackStack(LibraryRoute)
    val listDetail = rememberListDetailSceneStrategy<NavKey>()

    // Beside the settings list, opening something replaces what is open in the detail pane.
    fun openDetail(route: NavKey) {
        val last = backStack.lastOrNull()
        if (last is SettingsCategoryRoute || last is SettingsSearchRoute || last is GpuDriverRoute || last is BiosRoute || last is MemoryCardsRoute || last is MemoryCardRoute || last is CheatsRoute || last is CheatGamesRoute || last is MacrosRoute || last is MacroGamesRoute || last is BackupRoute || last is SaveStatesRoute || last is AboutRoute || last is LicensesRoute || last is LicenseRoute) {
            while (backStack.lastOrNull() !is SettingsRoute) backStack.removeLastOrNull()
        }
        backStack.add(route)
    }

    NavDisplay(
        backStack = backStack,
        // Shows between the list and detail panes.
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        onBack = { backStack.removeLastOrNull() },
        sceneStrategies = listOf(listDetail),
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            // Scopes each destination's ViewModels to its back stack entry.
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<LibraryRoute> {
                LibraryScreen(
                    onOpenSettings = { backStack.add(SettingsRoute()) },
                    onOpenGameSettings = { serial, title -> backStack.add(SettingsRoute(serial, title)) },
                )
            }
            entry<BiosRoute>(
                metadata = { ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute()) },
            ) {
                BiosScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry<MemoryCardsRoute>(
                metadata = { ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute()) },
            ) {
                MemoryCardsScreen(
                    onOpenCard = { backStack.add(MemoryCardRoute(it)) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<MemoryCardRoute>(
                metadata = { ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute()) },
            ) { route ->
                MemoryCardSavesScreen(cardName = route.name, onBack = { backStack.removeLastOrNull() })
            }
            entry<SettingsRoute>(
                metadata = { route ->
                    ListDetailSceneStrategy.listPane(sceneKey = route) { SettingsDetailPlaceholder() }
                },
            ) { route ->
                SettingsHomeScreen(
                    serial = route.serial,
                    gameTitle = route.gameTitle,
                    selected = backStack.filterIsInstance<SettingsCategoryRoute>().lastOrNull()?.category,
                    onOpenCategory = { category ->
                        openDetail(SettingsCategoryRoute(category, route.serial, route.gameTitle))
                    },
                    onOpenSearch = { openDetail(SettingsSearchRoute(route.serial, route.gameTitle)) },
                    cheatsSelected = backStack.lastOrNull().let {
                        it is CheatGamesRoute || it is CheatsRoute && it.fromGlobal == (route.serial == null)
                    },
                    macrosSelected = backStack.lastOrNull().let {
                        it is MacroGamesRoute || it is MacrosRoute && it.fromGlobal == (route.serial == null)
                    },
                    onOpenMacros = {
                        openDetail(route.serial?.let { MacrosRoute(it, route.gameTitle) } ?: MacroGamesRoute)
                    },
                    backupSelected = backStack.lastOrNull() is BackupRoute,
                    onOpenBackup = { openDetail(BackupRoute) },
                    statesSelected = backStack.lastOrNull() is SaveStatesRoute,
                    onOpenStates = { openDetail(SaveStatesRoute) },
                    aboutSelected = backStack.lastOrNull().let { it is AboutRoute || it is LicensesRoute || it is LicenseRoute },
                    onOpenAbout = { openDetail(AboutRoute) },
                    onOpenCheats = {
                        // A game's settings go straight to its cheats; the global ones pick a game first.
                        openDetail(route.serial?.let { CheatsRoute(it, route.gameTitle) } ?: CheatGamesRoute)
                    },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<SettingsCategoryRoute>(
                metadata = { route ->
                    ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute(route.serial, route.gameTitle))
                },
            ) { route ->
                SettingsCategoryScreen(
                    category = route.category,
                    serial = route.serial,
                    gameTitle = route.gameTitle,
                    onOpenGpuDriver = { backStack.add(GpuDriverRoute(route.serial, route.gameTitle)) },
                    onOpenBios = { openDetail(BiosRoute) },
                    onOpenMemoryCards = { openDetail(MemoryCardsRoute) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<SettingsSearchRoute>(
                metadata = { route ->
                    ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute(route.serial, route.gameTitle))
                },
            ) { route ->
                SettingsSearchScreen(
                    serial = route.serial,
                    gameTitle = route.gameTitle,
                    onOpenGpuDriver = { backStack.add(GpuDriverRoute(route.serial, route.gameTitle)) },
                    onOpenBios = { openDetail(BiosRoute) },
                    onOpenMemoryCards = { openDetail(MemoryCardsRoute) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<CheatGamesRoute>(
                metadata = { ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute()) },
            ) {
                CheatGamesScreen(
                    onPick = { serial, title -> backStack.add(CheatsRoute(serial, title, fromGlobal = true)) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<CheatsRoute>(
                metadata = { route ->
                    val list = if (route.fromGlobal) SettingsRoute() else SettingsRoute(route.serial, route.gameTitle)
                    ListDetailSceneStrategy.detailPane(sceneKey = list)
                },
            ) { route ->
                CheatsScreen(
                    serial = route.serial,
                    gameTitle = route.gameTitle,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<MacroGamesRoute>(
                metadata = { ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute()) },
            ) {
                CheatGamesScreen(
                    title = R.string.settings_macros,
                    subtitle = R.string.macros_pick_game,
                    onPick = { serial, title -> backStack.add(MacrosRoute(serial, title, fromGlobal = true)) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<MacrosRoute>(
                metadata = { route ->
                    val list = if (route.fromGlobal) SettingsRoute() else SettingsRoute(route.serial, route.gameTitle)
                    ListDetailSceneStrategy.detailPane(sceneKey = list)
                },
            ) { route ->
                MacrosScreen(
                    serial = route.serial,
                    gameTitle = route.gameTitle,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<BackupRoute>(
                metadata = { ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute()) },
            ) {
                BackupScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry<SaveStatesRoute>(
                metadata = { ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute()) },
            ) {
                SaveStatesScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry<AboutRoute>(
                metadata = { ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute()) },
            ) {
                AboutScreen(
                    onOpenLicense = { backStack.add(LicenseRoute(it)) },
                    onOpenLicenses = { backStack.add(LicensesRoute) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<LicensesRoute>(
                metadata = { ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute()) },
            ) {
                LicensesScreen(
                    onOpenLicense = { backStack.add(LicenseRoute(it)) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<LicenseRoute>(
                metadata = { ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute()) },
            ) { route ->
                LicenseScreen(id = route.id, onBack = { backStack.removeLastOrNull() })
            }
            entry<GpuDriverRoute>(
                metadata = { route ->
                    ListDetailSceneStrategy.detailPane(sceneKey = SettingsRoute(route.serial, route.gameTitle))
                },
            ) { route ->
                GpuDriverScreen(
                    serial = route.serial,
                    gameTitle = route.gameTitle,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}
