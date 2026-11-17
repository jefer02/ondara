package app.ondara.feature.album

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.time.Year

data class AlbumUiState(
    val loading: Boolean = true,
    val page: AlbumPage? = null,
    val error: String? = null,
    val saved: Boolean = false,
    val pinned: Boolean = false,
)

sealed interface AlbumEvent {
    data class Load(val id: String, val retry: Boolean = false) : AlbumEvent
    data class Play(val index: Int, val shuffle: Boolean = false) : AlbumEvent
    data object Share : AlbumEvent
    data object Download : AlbumEvent
    data object Save : AlbumEvent
    data object Pin : AlbumEvent
    data object Radio : AlbumEvent
}

sealed interface AlbumEffect {
    data class Share(val text: String) : AlbumEffect
}

@HiltViewModel
class AlbumViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val player: PlayerController,
    private val downloads: DownloadRepository,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
) : MviViewModel<AlbumUiState, AlbumEvent, AlbumEffect>(AlbumUiState()) {
    private var loadedId: String? = null
    private var loadJob: Job? = null

    override fun onEvent(event: AlbumEvent) {
        val page = state.value.page
        when (event) {
            is AlbumEvent.Load -> load(event.id, event.retry)
            is AlbumEvent.Play -> page?.let { player.play(it.songs, event.index, event.shuffle, PlaybackContext("Álbum", it.album.title)) }
            AlbumEvent.Share -> viewModelScope.launch {
                page?.album?.let { album ->
                    val url = album.playlistId?.let { "https://music.youtube.com/playlist?list=${Uri.encode(it)}" }
                        ?: "https://music.youtube.com/browse/${Uri.encode(album.id)}"
                    emit(AlbumEffect.Share(url))
                }
            }
            AlbumEvent.Download -> viewModelScope.launch {
                page?.let { downloads.enqueue(it.songs, settings.settings.first().downloadQuality) }
            }
            AlbumEvent.Save -> viewModelScope.launch { page?.let { library.toggleSaved(MusicItem.AlbumItem(it.album)) } }
            AlbumEvent.Pin -> viewModelScope.launch { page?.let { library.togglePin(MusicItem.AlbumItem(it.album)) } }
            AlbumEvent.Radio -> page?.songs?.firstOrNull()?.let { player.startRadio(it) }
        }
    }

    private fun load(id: String, retry: Boolean) {
        if (loadedId == id && !retry) return
        loadedId = id
        loadJob?.cancel()
        setState { AlbumUiState() }
        loadJob = viewModelScope.launch {
            launch { library.isSaved("a:$id").collect { setState { copy(saved = it) } } }
            launch { library.isPinned("a:$id").collect { setState { copy(pinned = it) } } }
            var recorded = false
            catalog.album(id).catch { setState { copy(loading = false, error = it.message) } }.collect { res ->
                when (res) {
                    Resource.Loading -> setState { copy(loading = true) }
                    is Resource.Success -> {
                        setState { copy(loading = false, page = res.data, error = null) }
                        if (!recorded) {
                            recorded = true
                            library.recordOpened(MusicItem.AlbumItem(res.data.album))
                        }
                    }
                    is Resource.Error -> setState { copy(loading = false, error = res.message) }
                }
            }
        }
    }
}

@Composable
fun AlbumScreen(albumId: String, onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: AlbumViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menu by remember(albumId) { mutableStateOf<Song?>(null) }
    var info by remember(albumId) { mutableStateOf(false) }
    var more by remember(albumId) { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(albumId) { viewModel.onEvent(AlbumEvent.Load(albumId)) }
    CollectEffects(viewModel.effects) {
        when (it) {
            is AlbumEffect.Share -> context.startActivity(
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it.text), "Compartir"),
            )
        }
    }
    val page = state.page
    val album = page?.album
    val meta = listOfNotNull(
        album?.artists?.joinToString { it.name }?.takeIf { it.isNotEmpty() }, album?.year?.toString(),
        page?.let { "${it.songs.size} canciones" }, page?.let { formatDuration(it.songs.sumOf { song -> song.durationSec }) }?.ifEmpty { null },
    ).joinToString(" · ")
    ImmersiveDetail(
        imageUrl = album?.thumbnailUrl,
        title = album?.title.orEmpty(), meta = meta,
        eyebrow = if (album?.year == Year.now().value) "Nuevo lanzamiento" else null,
        onBack = { onNavigate(Nav.Back) }, modifier = modifier,
        topActions = {
            if (album != null) {
                GlassIconButton(OndaraIcons.Share, "Compartir álbum",
                    { viewModel.onEvent(AlbumEvent.Share) }, size = OndaraTheme.spacing.touch)
                GlassIconButton(OndaraIcons.More, "Más opciones", { more = true }, size = OndaraTheme.spacing.touch)
            }
        },
        centerActions = {
            if (page != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.xs, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm), itemVerticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(OndaraIcons.Info, "Información del álbum", { info = true }, size = OndaraTheme.spacing.touch)
                    GlassPillButton("Reproducir", { viewModel.onEvent(AlbumEvent.Play(0)) },
                        modifier = Modifier.heightIn(min = OndaraTheme.spacing.touch), filled = true)
                    GlassPillButton("Aleatorio", { viewModel.onEvent(AlbumEvent.Play(0, true)) },
                        modifier = Modifier.heightIn(min = OndaraTheme.spacing.touch))
                    GlassIconButton(if (state.saved) OndaraIcons.StarFilled else OndaraIcons.Star,
                        if (state.saved) "Quitar de guardados" else "Guardar álbum", { viewModel.onEvent(AlbumEvent.Save) }, size = OndaraTheme.spacing.touch)
                }
            }
        },
    ) {
        if (state.loading && page == null) items(8, key = { "sk$it" }, contentType = { "skeleton" }) { SongRowSkeleton() }
        if (page != null) {
            items(page.songs.size, key = { "song-$it-${page.songs[it].id}" }, contentType = { "song" }) { i ->
                GlassCard(contentPadding = PaddingValues(0.dp)) {
                    ImmersiveSongRow(page.songs[i], i + 1, { viewModel.onEvent(AlbumEvent.Play(i)) }, { menu = page.songs[i] })
                }
            }
            if (page.otherVersions.isNotEmpty()) item(key = "others", contentType = "shelf") {
                GlassCard { Shelf(HomeShelf("Otras versiones", null, page.otherVersions.map { MusicItem.AlbumItem(it) }), { it.toNav()?.let(onNavigate) }) }
            }
        }
        if (state.error != null) item(key = "error", contentType = "error") {
            EmptyState(OndaraIcons.Album, "No se pudo abrir el álbum", message = state.error)
            OndaraButton("Reintentar", { viewModel.onEvent(AlbumEvent.Load(albumId, true)) })
        }
    }
    if (info) AboutSheet("Acerca del álbum", listOfNotNull(meta, page?.description).joinToString("\n\n")) { info = false }
    if (more) OndaraSheet({ more = false }, title = album?.title) {
        MenuAction(OndaraIcons.Download, "Descargar todo", { viewModel.onEvent(AlbumEvent.Download); more = false })
        MenuAction(OndaraIcons.Star, if (state.pinned) "Desfijar de Biblioteca" else "Fijar en Biblioteca", { viewModel.onEvent(AlbumEvent.Pin); more = false })
        MenuAction(OndaraIcons.Radio, "Iniciar radio", { viewModel.onEvent(AlbumEvent.Radio); more = false })
    }
    menu?.let { song -> SongMenuSheet(song, { menu = null }, { onNavigate(Nav.Artist(it)) }, { onNavigate(Nav.Album(it)) }) }
}
