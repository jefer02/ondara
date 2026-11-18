package app.ondara.feature.playlist

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.domain.*
import app.ondara.core.model.*
import app.ondara.core.ui.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistUiState(
    val loading: Boolean = true,
    val playlist: Playlist? = null,
    val songs: List<Song> = emptyList(),
    val error: String? = null,
    val saved: Boolean = false,
    val pinned: Boolean = false,
    val unmatched: Int = 0,
)

sealed interface PlaylistEvent {
    data class Load(val id: String, val retry: Boolean = false) : PlaylistEvent
    data class Play(val index: Int, val shuffle: Boolean = false) : PlaylistEvent
    data object Share : PlaylistEvent
    data object Download : PlaylistEvent
    data object SaveCopy : PlaylistEvent
    data object Save : PlaylistEvent
    data object Pin : PlaylistEvent
    data object Radio : PlaylistEvent
    data class Rename(val name: String) : PlaylistEvent
    data object Delete : PlaylistEvent
    data class Remove(val songId: String) : PlaylistEvent
    data class Move(val from: Int, val to: Int) : PlaylistEvent
}

sealed interface PlaylistEffect {
    data class Share(val text: String) : PlaylistEffect
    data object Closed : PlaylistEffect
    data class Saved(val id: String) : PlaylistEffect
}

/** Carga listas locales, remotas y virtuales sin permitir editar estas últimas. */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    private val player: PlayerController,
    private val downloads: DownloadRepository,
    private val settings: SettingsRepository,
    private val personal: PersonalRepository,
    private val sync: SyncRepository,
) : MviViewModel<PlaylistUiState, PlaylistEvent, PlaylistEffect>(PlaylistUiState()) {
    private var id: String? = null
    private var loadJob: Job? = null
    private val editable: Boolean get() = state.value.playlist?.isLocal == true && id?.let { !MixIds.isVirtual(it) } == true

    override fun onEvent(event: PlaylistEvent) {
        val current = id
        val item = state.value.playlist?.let { MusicItem.PlaylistItem(it) }
        when (event) {
            is PlaylistEvent.Load -> load(event.id, event.retry)
            is PlaylistEvent.Play -> player.play(state.value.songs, event.index, event.shuffle, PlaybackContext("Playlist", state.value.playlist?.title.orEmpty()))
            PlaylistEvent.Share -> share()
            PlaylistEvent.Download -> viewModelScope.launch { downloads.enqueue(state.value.songs, settings.settings.first().downloadQuality) }
            PlaylistEvent.SaveCopy -> viewModelScope.launch {
                emit(PlaylistEffect.Saved(library.createPlaylist(state.value.playlist?.title.orEmpty(), state.value.songs)))
            }
            PlaylistEvent.Save -> viewModelScope.launch {
                item?.let { if (editable) library.togglePin(it) else library.toggleSaved(it) }
            }
            PlaylistEvent.Pin -> viewModelScope.launch { item?.let { library.togglePin(it) } }
            PlaylistEvent.Radio -> state.value.songs.firstOrNull()?.let { player.startRadio(it) }
            else -> if (current != null && editable) edit(event, current)
        }
    }

    /** Compartir una lista local recorre sus canciones: O(n) tiempo y espacio para el texto. */
    private fun share() {
        val playlist = state.value.playlist ?: return
        val text = if (editable || MixIds.isVirtual(playlist.id)) {
            playlist.title + "\n" + state.value.songs.joinToString("\n") { "${it.title} · ${it.artistLine}" }
        } else "https://music.youtube.com/playlist?list=${Uri.encode(playlist.id.removePrefix("VL"))}"
        viewModelScope.launch { emit(PlaylistEffect.Share(text)) }
    }

    private fun edit(event: PlaylistEvent, current: String) {
        viewModelScope.launch {
            when (event) {
                is PlaylistEvent.Rename -> library.renamePlaylist(current, event.name)
                PlaylistEvent.Delete -> { library.deletePlaylist(current); emit(PlaylistEffect.Closed) }
                is PlaylistEvent.Remove -> library.removeFromPlaylist(current, event.songId)
                is PlaylistEvent.Move -> library.movePlaylistSong(current, event.from, event.to)
                else -> Unit
            }
        }
    }

    private fun load(playlistId: String, retry: Boolean) {
        if (id == playlistId && !retry) return
        id = playlistId
        loadJob?.cancel()
        setState { PlaylistUiState() }
        loadJob = viewModelScope.launch {
            launch { library.isSaved("p:$playlistId").collect { setState { copy(saved = it) } } }
            launch { library.isPinned("p:$playlistId").collect { setState { copy(pinned = it) } } }
            launch { sync.unmatched().collect { setState { copy(unmatched = it.size) } } }
            try {
            when {
                MixIds.isVirtual(playlistId) -> loadVirtual(playlistId)
                else -> {
                    val stored = library.playlist(playlistId).first()
                    if (stored?.isLocal == true || playlistId.startsWith("local_")) loadLocal(playlistId)
                    else loadRemote(playlistId)
                }
            }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                setState { copy(loading = false, error = error.message ?: "No se pudo cargar la playlist") }
            }
        }
    }

    private suspend fun loadVirtual(playlistId: String) {
        try {
            val page = personal.virtualPlaylist(playlistId)
            if (page == null) setState { copy(loading = false, error = "No se encontró este mix") }
            else {
                setState { copy(loading = false, playlist = page.playlist, songs = page.songs, error = null) }
                library.recordOpened(MusicItem.PlaylistItem(page.playlist))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            setState { copy(loading = false, error = error.message ?: "No se pudo cargar el mix") }
        }
    }

    private suspend fun loadLocal(playlistId: String) {
        var recorded = false
        combine(library.playlist(playlistId), library.playlistSongs(playlistId)) { p, s -> p to s }
            .catch { setState { copy(loading = false, error = it.message) } }.collect { (p, s) ->
                setState { copy(loading = false, playlist = p, songs = s, error = if (p == null) "La playlist ya no existe" else null) }
                if (p != null && !recorded) {
                    recorded = true
                    library.recordOpened(MusicItem.PlaylistItem(p))
                }
            }
    }

    private suspend fun loadRemote(playlistId: String) {
        var recorded = false
        catalog.playlist(playlistId).catch { setState { copy(loading = false, error = it.message) } }.collect { res ->
            when (res) {
                Resource.Loading -> setState { copy(loading = true) }
                is Resource.Success -> {
                    val playlist = res.data.playlist
                    setState { copy(loading = false, playlist = playlist, songs = res.data.songs, error = null) }
                    if (!recorded) {
                        recorded = true
                        library.recordOpened(MusicItem.PlaylistItem(playlist))
                    }
                }
                is Resource.Error -> setState { copy(loading = false, error = res.message) }
            }
        }
    }
}

@Composable
fun PlaylistScreen(playlistId: String, onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: PlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menu by remember(playlistId) { mutableStateOf<Pair<Int, Song>?>(null) }
    var renaming by remember(playlistId) { mutableStateOf(false) }
    var deleting by remember(playlistId) { mutableStateOf(false) }
    var info by remember(playlistId) { mutableStateOf(false) }
    var more by remember(playlistId) { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(playlistId) { viewModel.onEvent(PlaylistEvent.Load(playlistId)) }
    CollectEffects(viewModel.effects) {
        when (it) {
            is PlaylistEffect.Share -> context.startActivity(
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it.text), "Compartir"),
            )
            PlaylistEffect.Closed -> onNavigate(Nav.Back)
            is PlaylistEffect.Saved -> onNavigate(Nav.Playlist(it.id))
        }
    }
    val playlist = state.playlist
    val local = playlist?.isLocal == true && !MixIds.isVirtual(playlistId)
    val starred = if (local) state.pinned else state.saved
    val meta = listOfNotNull(playlist?.author, "${state.songs.size} canciones",
        formatDuration(state.songs.sumOf { it.durationSec }).ifEmpty { null }).joinToString(" · ")
    val generated: (@Composable (Modifier) -> Unit)? = if (MixIds.isVirtual(playlistId) && playlist?.thumbnailUrl == null) {
        { m -> MixCover(playlistId, playlist?.title.orEmpty(), m, RectangleShape) }
    } else null
    ImmersiveDetail(
        imageUrl = playlist?.thumbnailUrl ?: state.songs.firstOrNull()?.thumbnailUrl?.takeUnless { MixIds.isVirtual(playlistId) },
        cover = generated,
        title = playlist?.title.orEmpty(), meta = meta,
        eyebrow = when (playlist?.source) {
            PlaylistSource.SPOTIFY -> "De Spotify"
            PlaylistSource.YOUTUBE -> "De YouTube Music"
            else -> null
        },
        onBack = { onNavigate(Nav.Back) }, modifier = modifier,
        topActions = {
            if (playlist != null) {
                GlassIconButton(OndaraIcons.Share, "Compartir playlist",
                    { viewModel.onEvent(PlaylistEvent.Share) }, size = OndaraTheme.spacing.touch)
                GlassIconButton(OndaraIcons.More, "Más opciones", { more = true }, size = OndaraTheme.spacing.touch)
            }
        },
        centerActions = {
            if (playlist != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.xs, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm), itemVerticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(OndaraIcons.Info, "Información de playlist", { info = true }, size = OndaraTheme.spacing.touch)
                    GlassPillButton("Reproducir", { viewModel.onEvent(PlaylistEvent.Play(0)) },
                        modifier = Modifier.heightIn(min = OndaraTheme.spacing.touch), filled = true)
                    GlassPillButton("Aleatorio", { viewModel.onEvent(PlaylistEvent.Play(0, true)) },
                        modifier = Modifier.heightIn(min = OndaraTheme.spacing.touch))
                    GlassIconButton(if (starred) OndaraIcons.StarFilled else OndaraIcons.Star,
                        if (local) { if (starred) "Desfijar playlist" else "Fijar playlist" } else { if (starred) "Quitar de guardados" else "Guardar playlist" },
                        { viewModel.onEvent(PlaylistEvent.Save) }, size = OndaraTheme.spacing.touch)
                }
            }
        },
    ) {
        if (state.loading && state.songs.isEmpty()) items(8, key = { "sk$it" }, contentType = { "skeleton" }) { SongRowSkeleton() }
        if (local && playlist.source == PlaylistSource.SPOTIFY && state.unmatched > 0) item(key = "unmatched", contentType = "action") {
            GlassCard { GlassPillButton("${state.unmatched} canciones sin coincidencia · Revisar", { onNavigate(Nav.Unmatched) }) }
        }
        items(state.songs.size, key = { "song-$it-${state.songs[it].id}" }, contentType = { "song" }) { i ->
            GlassCard(contentPadding = PaddingValues(0.dp)) {
                ImmersiveSongRow(state.songs[i], i + 1, { viewModel.onEvent(PlaylistEvent.Play(i)) }, { menu = i to state.songs[i] })
            }
        }
        if (!state.loading && (state.songs.isEmpty() || state.error != null)) item(key = "empty", contentType = "empty") {
            EmptyState(OndaraIcons.Playlist, if (state.error != null) "No se pudo abrir la playlist" else "Playlist vacía",
                message = state.error ?: "Añade canciones desde su menú.")
            if (state.error != null) OndaraButton("Reintentar", { viewModel.onEvent(PlaylistEvent.Load(playlistId, true)) })
        }
    }
    if (info) AboutSheet("Acerca de ${playlist?.title.orEmpty()}", meta + when (playlist?.source) {
        PlaylistSource.SPOTIFY -> "\n\nImportada de Spotify"
        PlaylistSource.YOUTUBE -> "\n\nPlaylist de YouTube Music"
        else -> if (MixIds.isVirtual(playlistId)) "\n\nMix personal de Ondara · Solo lectura" else "\n\nPlaylist de Ondara"
    }) { info = false }
    if (more) OndaraSheet({ more = false }, title = playlist?.title) {
        MenuAction(OndaraIcons.Download, "Descargar todo", { viewModel.onEvent(PlaylistEvent.Download); more = false })
        MenuAction(OndaraIcons.Star, if (state.pinned) "Desfijar de Biblioteca" else "Fijar en Biblioteca", { viewModel.onEvent(PlaylistEvent.Pin); more = false })
        MenuAction(OndaraIcons.Add, "Guardar copia en la biblioteca", { viewModel.onEvent(PlaylistEvent.SaveCopy); more = false })
        MenuAction(OndaraIcons.Radio, "Iniciar radio", { viewModel.onEvent(PlaylistEvent.Radio); more = false })
        if (local) {
            MenuAction(OndaraIcons.Edit, "Renombrar", { renaming = true; more = false })
            MenuAction(OndaraIcons.Delete, "Eliminar playlist", { deleting = true; more = false })
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
