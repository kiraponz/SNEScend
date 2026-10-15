package com.kira3.ametrine.ui

import androidx.navigation3.runtime.NavKey
import com.kira3.ametrine.ui.settings.SettingsCategory
import kotlinx.serialization.Serializable

// Destinations in MainActivity. Serializable so the back stack survives process death.

@Serializable
data object LibraryRoute : NavKey

/** The BIOS files the app holds, a page of the global settings. */
@Serializable
data object BiosRoute : NavKey

/** The memory cards the app holds, a page of the global settings. */
@Serializable
data object MemoryCardsRoute : NavKey

/** The settings categories: global when [serial] is null, else that game's. */
@Serializable
data class SettingsRoute(val serial: String? = null, val gameTitle: String? = null) : NavKey

/** One category of the settings [SettingsRoute] lists, shown beside it on a large screen. */
@Serializable
data class SettingsCategoryRoute(
    val category: SettingsCategory,
    val serial: String? = null,
    val gameTitle: String? = null,
) : NavKey

/** Search across every settings category, global or for a game. */
@Serializable
data class SettingsSearchRoute(val serial: String? = null, val gameTitle: String? = null) : NavKey

/** The custom Vulkan driver of the settings [SettingsRoute] lists, global or for a game. */
@Serializable
data class GpuDriverRoute(val serial: String? = null, val gameTitle: String? = null) : NavKey

/** A game's cheats, a page of that game's settings. */
@Serializable
data class CheatsRoute(
    val serial: String,
    val gameTitle: String? = null,
    /** Opened from the global settings, where the game was picked from [CheatGamesRoute]. */
    val fromGlobal: Boolean = false,
) : NavKey

/** The games to pick from for their cheats, a page of the global settings. */
@Serializable
data object CheatGamesRoute : NavKey

/** A game's macros, a page of that game's settings. */
@Serializable
data class MacrosRoute(
    val serial: String,
    val gameTitle: String? = null,
    /** Opened from the global settings, where the game was picked from [MacroGamesRoute]. */
    val fromGlobal: Boolean = false,
) : NavKey

/** The games to pick from for their macros, a page of the global settings. */
@Serializable
data object MacroGamesRoute : NavKey

/** Backing up and restoring the app's data, a page of the global settings. */
@Serializable
data object BackupRoute : NavKey

/** The save states games have left, exported and imported here, a page of the global settings. */
@Serializable
data object SaveStatesRoute : NavKey

/** The app's version, source and licenses, a page of the global settings. */
@Serializable
data object AboutRoute : NavKey

/** What the app includes from others, opened from [AboutRoute]. */
@Serializable
data object LicensesRoute : NavKey

/** One license text, opened from [AboutRoute] or [LicensesRoute]. */
@Serializable
data class LicenseRoute(val id: String) : NavKey
