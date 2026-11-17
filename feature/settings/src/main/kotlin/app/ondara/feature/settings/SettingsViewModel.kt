package app.ondara.feature.settings

import androidx.lifecycle.viewModelScope
import app.ondara.core.domain.AccountRepository
import app.ondara.core.domain.BackupRepository
import app.ondara.core.domain.ImportRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.model.AccountsState
import app.ondara.core.model.AudioQuality
import app.ondara.core.model.EqualizerBand
import app.ondara.core.model.UserSettings
import app.ondara.core.ui.MviViewModel
import app.ondara.core.ui.Nav
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val bands: List<EqualizerBand> = emptyList(),
    val accounts: AccountsState = AccountsState(),
    /** Texto de progreso de una operación larga (importación, backup) o null. */
    val busy: String? = null,
)

sealed interface SettingsEvent {
    data class Update(val transform: (UserSettings) -> UserSettings) : SettingsEvent
    data class SetBand(val index: Int, val levelMb: Int) : SettingsEvent
    data object LoadBands : SettingsEvent
    data class ImportYouTube(val url: String) : SettingsEvent
    data class ImportCsv(val name: String, val csv: String) : SettingsEvent
    data class ExportBackup(val password: String) : SettingsEvent
    data class ImportBackup(val bytes: ByteArray, val password: String) : SettingsEvent
}

sealed interface SettingsEffect {
    data class Message(val text: String) : SettingsEffect
    data class Navigate(val nav: Nav) : SettingsEffect
    class BackupReady(val bytes: ByteArray) : SettingsEffect
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val player: PlayerController,
    private val imports: ImportRepository,
    private val backup: BackupRepository,
    private val accounts: AccountRepository,
) : MviViewModel<SettingsUiState, SettingsEvent, SettingsEffect>(SettingsUiState()) {
    init {
        viewModelScope.launch { accounts.accounts.collect { setState { copy(accounts = it) } } }
        viewModelScope.launch { repository.settings.collect { setState { copy(settings = it) } } }
    }

    override fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.Update -> viewModelScope.launch { repository.update(event.transform) }
            SettingsEvent.LoadBands -> setState { copy(bands = player.equalizerBands()) }
            is SettingsEvent.SetBand -> setBand(event.index, event.levelMb)
            is SettingsEvent.ImportYouTube -> task("Importando playlist…") {
                val result = imports.importYouTubePlaylist(event.url)
                emit(SettingsEffect.Message("Importadas ${result.matched} canciones"))
                emit(SettingsEffect.Navigate(Nav.Playlist(result.playlistId)))
            }
            is SettingsEvent.ImportCsv -> task("Buscando canciones…") {
                val result = imports.importCsv(event.name, event.csv) { done, total -> setState { copy(busy = "Buscando canciones… $done / $total") } }
                emit(SettingsEffect.Message("Encontradas ${result.matched} de ${result.total} canciones"))
                emit(SettingsEffect.Navigate(Nav.Playlist(result.playlistId)))
            }
            is SettingsEvent.ExportBackup -> task("Creando backup…") { emit(SettingsEffect.BackupReady(backup.export(event.password.ifEmpty { null }))) }
            is SettingsEvent.ImportBackup -> task("Restaurando backup…") {
                backup.import(event.bytes, event.password.ifEmpty { null })
                emit(SettingsEffect.Message("Backup restaurado"))
            }
        }
    }

    private fun setBand(index: Int, levelMb: Int) {
        val count = state.value.bands.size
        viewModelScope.launch {
            repository.update { s ->
                val levels = MutableList(count) { i -> s.equalizer.bandLevelsMb.getOrElse(i) { 0 } }
                if (index in levels.indices) levels[index] = levelMb
                s.copy(equalizer = s.equalizer.copy(bandLevelsMb = levels))
            }
        }
    }

    /** Ejecuta una operación larga mostrando progreso y convirtiendo los errores en un aviso. */
    private fun task(label: String, block: suspend () -> Unit) {
        if (state.value.busy != null) return
        setState { copy(busy = label) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(SettingsEffect.Message(e.message ?: "Algo salió mal"))
            } finally {
                setState { copy(busy = null) }
            }
        }
    }

    companion object {
        val QUALITIES = listOf(AudioQuality.HIGH to "Alta · Opus ~160 kbps", AudioQuality.LOW to "Ahorro de datos · ~64 kbps")
    }
}
