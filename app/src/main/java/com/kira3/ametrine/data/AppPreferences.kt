package com.kira3.ametrine.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Where the battery saves go: [Mode.GameFolder], or the SAF tree [folder] the user picked. */
data class SaveLocation(val mode: Mode, val folder: Uri?) {
    enum class Mode { GameFolder, Folder }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app")

/** App-level choices that are not emulator settings (picked folders, library order). */
class AppPreferences(context: Context) {
    private val dataStore = context.applicationContext.dataStore

    /** SAF tree URI of the folder the library scans. */
    val gameFolder: Flow<Uri?> = dataStore.data.map { prefs -> prefs[GAME_FOLDER]?.let(Uri::parse) }

    /** Where the battery saves are kept apart from the app (see [SaveFolderSync]). */
    val saveLocation: Flow<SaveLocation> = dataStore.data.map { prefs ->
        SaveLocation(
            mode = SaveLocation.Mode.entries.firstOrNull { it.name == prefs[SAVE_LOCATION] } ?: SaveLocation.Mode.GameFolder,
            folder = prefs[SAVE_FOLDER]?.let(Uri::parse),
        )
    }

    suspend fun setSaveLocation(mode: SaveLocation.Mode, folder: Uri? = null) {
        dataStore.edit { prefs ->
            prefs[SAVE_LOCATION] = mode.name
            folder?.let { prefs[SAVE_FOLDER] = it.toString() }
        }
    }

    /** [com.kira3.ametrine.data.LibrarySort] name; null until the user picks one. */
    val librarySort: Flow<String?> = dataStore.data.map { prefs -> prefs[LIBRARY_SORT] }

    suspend fun setLibrarySort(name: String) {
        dataStore.edit { it[LIBRARY_SORT] = name }
    }

    suspend fun setGameFolder(uri: Uri) {
        dataStore.edit { it[GAME_FOLDER] = uri.toString() }
    }

    private companion object {
        val GAME_FOLDER = stringPreferencesKey("game_folder")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val SAVE_LOCATION = stringPreferencesKey("save_location")
        val SAVE_FOLDER = stringPreferencesKey("save_folder")
    }
}
