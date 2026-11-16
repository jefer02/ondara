package app.ondara.feature.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ondara.core.designsystem.component.ButtonStyle
import app.ondara.core.designsystem.component.EmptyState
import app.ondara.core.designsystem.component.OndaraButton
import app.ondara.core.designsystem.component.OndaraDialog
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.domain.CatalogRepository
import app.ondara.core.domain.DownloadRepository
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.Resource
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.model.Playlist
import app.ondara.core.model.Song
import app.ondara.core.ui.CollectEffects
import app.ondara.core.ui.DetailScaffold
import app.ondara.core.ui.MenuAction
import app.ondara.core.ui.MviViewModel
import app.ondara.core.ui.NameDialog
import app.ondara.core.ui.Nav
import app.ondara.core.ui.SongMenuSheet
import app.ondara.core.ui.SongRow
import app.ondara.core.ui.SongRowSkeleton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistUiState(
    val loading: Boolean = true,
    val playlist: Playlist? = null,
    val songs: List<Song> = emptyList(),
    val error: String? = null,
)

sealed interface PlaylistEvent {
    data class Load(val id: String) : PlaylistEvent
    data class Play(val index: Int, val shuffle: Boolean = false) : PlaylistEvent
    data object Download : PlaylistEvent
    data object SaveCopy : PlaylistEvent
    data class Rename(val name: String) : PlaylistEvent
    data object Delete : PlaylistEvent
    data class Remove(val songId: String) : PlaylistEvent
    data class Move(val from: Int, val to: Int) : PlaylistEvent
}

sealed interface PlaylistEffect {
    data object Closed : PlaylistEffect
    data class Saved(val id: String) : PlaylistEffect
}

/** Sirve tanto a playlists remotas (YouTube Music) como a las locales (`local_…`), que son editables. */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    private val player: PlayerController,
    private val downloads: DownloadRepository,
    private val settings: SettingsRepository,
) : MviViewModel<PlaylistUiState, PlaylistEvent, PlaylistEffect>(PlaylistUiState()) {
    private var id: String? = null

    override fun onEvent(event: PlaylistEvent) {
        val current = id
        when (event) {
            is PlaylistEvent.Load -> load(event.id)
            is PlaylistEvent.Play -> player.play(state.value.songs, event.index, event.shuffle)
            PlaylistEvent.Download -> viewModelScope.launch { downloads.enqueue(state.value.songs, settings.settings.first().downloadQuality) }
            PlaylistEvent.SaveCopy -> viewModelScope.launch {
                emit(PlaylistEffect.Saved(library.createPlaylist(state.value.playlist?.title.orEmpty(), state.value.songs)))
            }
            is PlaylistEvent.Rename -> if (current != null) viewModelScope.launch { library.renamePlaylist(current, event.name) }
            PlaylistEvent.Delete -> if (current != null) {
                viewModelScope.launch {
                    library.deletePlaylist(current)
                    emit(PlaylistEffect.Closed)
                }
            }
            is PlaylistEvent.Remove -> if (current != null) viewModelScope.launch { library.removeFromPlaylist(current, event.songId) }
            is PlaylistEvent.Move -> if (current != null) viewModelScope.launch { library.movePlaylistSong(current, event.from, event.to) }
        }
    }

    private fun load(playlistId: String) {
        if (id == playlistId) return
        id = playlistId
        viewModelScope.launch {
            if (playlistId.startsWith(LOCAL_PREFIX)) {
                combine(library.playlist(playlistId), library.playlistSongs(playlistId)) { p, s -> p to s }.collect { (p, s) ->
                    setState { copy(loading = false, playlist = p, songs = s) }
                }
            } else {
                catalog.playlist(playlistId).collect { res ->
                    when (res) {
                        Resource.Loading -> setState { copy(loading = true) }
                        is Resource.Success -> setState { copy(loading = false, playlist = res.data.playlist, songs = res.data.songs, error = null) }
                        is Resource.Error -> setState { copy(loading = false, error = res.message) }
                    }
                }
            }
        }
    }

    private companion object {
        const val LOCAL_PREFIX = "local_"
    }
}

@Composable
fun PlaylistScreen(playlistId: String, onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: PlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<Pair<Int, Song>?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(playlistId) { viewModel.onEvent(PlaylistEvent.Load(playlistId)) }
    CollectEffects(viewModel.effects) {
        when (it) {
            PlaylistEffect.Closed -> onNavigate(Nav.Back)
            is PlaylistEffect.Saved -> onNavigate(Nav.Playlist(it.id))
        }
    }
    val playlist = state.playlist
    val local = playlist?.isLocal == true
    DetailScaffold(
        imageUrl = playlist?.thumbnailUrl ?: state.songs.firstOrNull()?.thumbnailUrl,
        title = playlist?.title.orEmpty(),
        subtitle = listOfNotNull(playlist?.author, "${state.songs.size} canciones").joinToString(" · "),
        onBack = { onNavigate(Nav.Back) },
        modifier = modifier,
        actions = {
            if (state.songs.isNotEmpty()) OndaraIconButton(OndaraIcons.Download, "Descargar playlist", { viewModel.onEvent(PlaylistEvent.Download) })
            if (local) {
                OndaraIconButton(OndaraIcons.Edit, "Renombrar", { renaming = true })
                OndaraIconButton(OndaraIcons.Delete, "Eliminar playlist", { deleting = true })
            } else if (playlist != null) {
                OndaraIconButton(OndaraIcons.Add, "Guardar copia en la biblioteca", { viewModel.onEvent(PlaylistEvent.SaveCopy) })
            }
        },
        header = {
            if (state.songs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
                    OndaraButton("Reproducir", { viewModel.onEvent(PlaylistEvent.Play(0)) }, icon = OndaraIcons.Play)
                    OndaraButton("Aleatorio", { viewModel.onEvent(PlaylistEvent.Play(0, true)) }, style = ButtonStyle.TONAL, icon = OndaraIcons.Shuffle)
                }
            }
        },
    ) {
        if (state.loading && state.songs.isEmpty()) items(8, key = { "sk$it" }) { SongRowSkeleton() }
        items(state.songs.size, key = { state.songs[it].id }, contentType = { "song" }) { i ->
            SongRow(state.songs[i], { viewModel.onEvent(PlaylistEvent.Play(i)) }, entryIndex = i, onMore = { menu = i to state.songs[i] })
        }
        if (!state.loading && state.songs.isEmpty()) {
            item {
                EmptyState(OndaraIcons.Playlist, if (state.error != null) "No se pudo abrir la playlist" else "Playlist vacía", message = state.error ?: "Añade canciones desde su menú.")
            }
        }
    }
    menu?.let { (index, song) ->
        SongMenuSheet(
            song, { menu = null }, { onNavigate(Nav.Artist(it)) }, { onNavigate(Nav.Album(it)) },
            extra = {
                if (local) {
                    if (index > 0) MenuAction(OndaraIcons.ChevronDown, "Subir una posición", { viewModel.onEvent(PlaylistEvent.Move(index, index - 1)); menu = null })
                    if (index < state.songs.lastIndex) MenuAction(OndaraIcons.ChevronDown, "Bajar una posición", { viewModel.onEvent(PlaylistEvent.Move(index, index + 1)); menu = null })
                    MenuAction(OndaraIcons.Delete, "Quitar de esta playlist", { viewModel.onEvent(PlaylistEvent.Remove(song.id)); menu = null })
                }
            },
        )
    }
    if (renaming) NameDialog("Renombrar playlist", "Guardar", { renaming = false }, initial = playlist?.title.orEmpty()) {
        viewModel.onEvent(PlaylistEvent.Rename(it))
        renaming = false
    }
    if (deleting) {
        OndaraDialog("¿Eliminar playlist?", { deleting = false }, "Eliminar", { deleting = false; viewModel.onEvent(PlaylistEvent.Delete) }, text = "Las canciones no se borran de tus favoritos ni de tus descargas.")
    }
}
