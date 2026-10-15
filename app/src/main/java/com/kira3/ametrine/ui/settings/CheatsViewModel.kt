package com.kira3.ametrine.ui.settings

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kira3.ametrine.AmetrineApplication
import com.kira3.ametrine.data.CheatRepository
import com.kira3.ametrine.emucore.Cheat
import com.kira3.ametrine.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class CheatsViewModel(
    private val serial: String,
    private val repository: CheatRepository,
    private val core: EmulatorCore,
    private val application: Application,
) : ViewModel() {
    /** Null while the cheats are being read. */
    val cheats: StateFlow<List<Cheat>?> = repository.cheats(serial)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How many cheats the last import added; -1 when there were none. Cleared by [onImportShown]. */
    val imported = MutableStateFlow<Int?>(null)

    fun setEnabled(id: String, enabled: Boolean) = edit { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    /** Adds a cheat, or replaces the one with [id]. */
    fun save(id: String?, name: String, code: String) = edit { list ->
        if (id == null) {
            list + Cheat(UUID.randomUUID().toString(), name.trim(), code.trim())
        } else {
            list.map { if (it.id == id) it.copy(name = name.trim(), code = code.trim()) else it }
        }
    }

    fun delete(id: String) = edit { list -> list.filterNot { it.id == id } }

    fun import(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { application.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
                    .onFailure { Log.w(TAG, "Could not read $uri", it) }
                    .getOrNull()
            }
            add(text?.let { core.parseCheatFile(it) { UUID.randomUUID().toString() } }.orEmpty())
        }
    }

    /** Adds [found], leaving out cheats already in the list with the same name and code. */
    private suspend fun add(found: List<Cheat>) {
        var added = 0
        repository.update(serial) { list ->
            val have = list.mapTo(HashSet()) { it.name to it.code }
            val new = found.filter { have.add(it.name to it.code) }
            added = new.size
            list + new
        }
        imported.value = if (added == 0) -1 else added
    }

    fun onImportShown() {
        imported.value = null
    }

    private fun edit(transform: (List<Cheat>) -> List<Cheat>) {
        viewModelScope.launch { repository.update(serial, transform) }
    }

    companion object {
        private const val TAG = "CheatsViewModel"

        fun factory(serial: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AmetrineApplication
                CheatsViewModel(serial, application.container.cheatRepository, application.container.emulatorCore, application)
            }
        }
    }
}
