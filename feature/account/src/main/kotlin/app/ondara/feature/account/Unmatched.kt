package app.ondara.feature.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.SyncRepository
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.model.*
import app.ondara.core.ui.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UnmatchedUiState(
    val tracks: List<UnmatchedTrack> = emptyList(),
    val selected: UnmatchedTrack? = null,
    val candidates: List<Song> = emptyList(),
    val loading: Boolean = true,
    val resolving: Boolean = false,
    val error: String? = null,
)
sealed interface UnmatchedEvent {
    data class Select(val track: UnmatchedTrack) : UnmatchedEvent
    data class Preview(val song: Song) : UnmatchedEvent
    data class Resolve(val song: Song) : UnmatchedEvent
    data object Dismiss : UnmatchedEvent
    data object Back : UnmatchedEvent
}
sealed interface UnmatchedEffect { data object Back : UnmatchedEffect }

@HiltViewModel
class UnmatchedViewModel @Inject constructor(private val sync: SyncRepository, private val player: PlayerController) :
    MviViewModel<UnmatchedUiState, UnmatchedEvent, UnmatchedEffect>(UnmatchedUiState()) {
    private var search: Job? = null
    init { viewModelScope.launch { sync.unmatched().collect { setState { copy(tracks = it, loading = false) } } } }
    override fun onEvent(event: UnmatchedEvent) {
        when (event) {
            UnmatchedEvent.Back -> emit(UnmatchedEffect.Back)
            UnmatchedEvent.Dismiss -> { search?.cancel(); setState { copy(selected = null, loading = false) } }
            is UnmatchedEvent.Preview -> player.play(listOf(event.song), context = PlaybackContext("Spotify", "Preescucha"))
            is UnmatchedEvent.Select -> {
                search?.cancel()
                setState { copy(selected = event.track, candidates = emptyList(), loading = true, error = null) }
                search = viewModelScope.launch {
                    try {
                        val candidates = sync.candidates(event.track)
                        setState { copy(candidates = candidates) }
                    } catch (error: CancellationException) { throw error } catch (error: Exception) {
                        setState { copy(error = error.message ?: "No se pudieron buscar candidatos") }
                    } finally { setState { copy(loading = false) } }
                }
            }
            is UnmatchedEvent.Resolve -> state.value.selected?.let { track ->
                if (!state.value.resolving) viewModelScope.launch {
                    setState { copy(resolving = true, error = null) }
                    try {
                        sync.resolveManually(track, event.song)
                        setState { copy(selected = null) }
                    } catch (error: CancellationException) { throw error } catch (error: Exception) {
                        setState { copy(error = error.message ?: "No se pudo guardar la coincidencia") }
                    } finally { setState { copy(resolving = false) } }
                }
            }
        }
    }
}

@Composable
fun UnmatchedScreen(
    onNavigate: (Nav) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UnmatchedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val event = viewModel::onEvent
    CollectEffects(viewModel.effects) { onNavigate(Nav.Back) }
    ScreenScaffold("Sin coincidencia", modifier, onBack = { event(UnmatchedEvent.Back) }) {
        if (state.loading && state.selected == null) item(key = "carga", contentType = "carga") { Skeleton(Modifier.fillMaxWidth().height(OndaraTheme.spacing.touch)) }
        if (!state.loading && state.tracks.isEmpty()) item(key = "vacío", contentType = "vacío") { Text("Todo emparejado") }
        items(state.tracks, key = { it.spotifyId }, contentType = { "canción" }) { track ->
            OndaraButton("${track.title} · ${track.artist}", { event(UnmatchedEvent.Select(track)) }, style = ButtonStyle.GHOST)
        }
    }
    state.selected?.let { track ->
        OndaraSheet({ event(UnmatchedEvent.Dismiss) }, title = track.title) {
            ErrorMessage(state.error)
            if (state.loading) Skeleton(Modifier.fillMaxWidth().height(OndaraTheme.spacing.touch))
            if (!state.loading && state.candidates.isEmpty()) Text("No se encontraron candidatos. Inténtalo de nuevo más tarde.")
            LazyColumn(Modifier.heightIn(max = OndaraTheme.spacing.touch * 9)) {
                items(state.candidates, key = { it.id }, contentType = { "candidato" }) { song ->
                    SongRow(song, { event(UnmatchedEvent.Preview(song)) }, trailing = {
                        OndaraButton("Elegir", { event(UnmatchedEvent.Resolve(song)) }, enabled = !state.resolving)
                    })
                }
            }
        }
    }
}
