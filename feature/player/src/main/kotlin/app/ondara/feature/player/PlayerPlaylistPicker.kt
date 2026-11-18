package app.ondara.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ondara.core.designsystem.component.Label
import app.ondara.core.designsystem.component.OndaraSheet
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.model.Playlist
import app.ondara.core.model.Song
import app.ondara.core.ui.CollectEffects
import app.ondara.core.ui.MenuAction
import app.ondara.core.ui.MviViewModel
import app.ondara.core.ui.NameDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerPlaylistState(val playlists: List<Playlist> = emptyList(), val saving: Boolean = false, val error: String? = null)
sealed interface PlayerPlaylistEvent {
    data class Add(val playlistId: String, val song: Song) : PlayerPlaylistEvent
    data class Create(val name: String, val song: Song) : PlayerPlaylistEvent
}

/** Mantiene el resultado de escritura para confirmar solo adiciones completadas. */
@HiltViewModel
class PlayerPlaylistViewModel @Inject constructor(private val library: LibraryRepository) :
    MviViewModel<PlayerPlaylistState, PlayerPlaylistEvent, String>(PlayerPlaylistState()) {
    init { viewModelScope.launch { library.playlists().collect { setState { copy(playlists = it) } } } }
    override fun onEvent(event: PlayerPlaylistEvent) {
        if (state.value.saving) return
        setState { copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val song = when (event) {
                    is PlayerPlaylistEvent.Add -> { library.addToPlaylist(event.playlistId, listOf(event.song)); event.song }
                    is PlayerPlaylistEvent.Create -> { library.createPlaylist(event.name, listOf(event.song)); event.song }
                }
                emit(song.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                setState { copy(error = "No se pudo guardar en la playlist. Vuelve a intentarlo.") }
            } finally {
                setState { copy(saving = false) }
            }
        }
    }
}

/** Variante local del selector compartido: expone el resultado del guardado sin modificar core. */
@Composable
internal fun PlayerPlaylistPicker(
    song: Song, onDismiss: () -> Unit, onAdded: () -> Unit,
    viewModel: PlayerPlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val currentSong by rememberUpdatedState(song.id)
    val currentOnAdded by rememberUpdatedState(onAdded)
    CollectEffects(viewModel.effects) { if (it == currentSong) currentOnAdded() }
    if (creating) NameDialog("Nueva playlist", "Crear", { creating = false }, text = state.error) { name ->
        viewModel.onEvent(PlayerPlaylistEvent.Create(name, song))
    } else OndaraSheet(onDismiss, title = "Agregar a playlist") {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            state.error?.let { Label(it, color = OndaraTheme.colors.error) }
            if (state.saving) Label("Guardando…")
            MenuAction(OndaraIcons.Add, "Nueva playlist", { creating = true })
            state.playlists.forEach { playlist ->
                MenuAction(OndaraIcons.Playlist, playlist.title, { viewModel.onEvent(PlayerPlaylistEvent.Add(playlist.id, song)) })
            }
        }
    }
}
