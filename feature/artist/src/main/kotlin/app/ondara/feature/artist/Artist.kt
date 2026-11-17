package app.ondara.feature.artist

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.LocalContentColor
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

data class ArtistUiState(val loading: Boolean = true, val page: ArtistPage? = null, val error: String? = null, val saved: Boolean = false, val pinned: Boolean = false)

sealed interface ArtistEvent {
    data class Load(val id: String, val retry: Boolean = false) : ArtistEvent
    data class Play(val index: Int, val shuffle: Boolean = false) : ArtistEvent
    data object Save : ArtistEvent
    data object Pin : ArtistEvent
    data object Share : ArtistEvent
    data object Download : ArtistEvent
    data class SaveRelease(val album: Album) : ArtistEvent
    data object Radio : ArtistEvent
    data class PlaySong(val song: Song) : ArtistEvent
}

sealed interface ArtistEffect {
    data class Share(val text: String) : ArtistEffect
}

@HiltViewModel
class ArtistViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val player: PlayerController,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val settings: SettingsRepository,
) : MviViewModel<ArtistUiState, ArtistEvent, ArtistEffect>(ArtistUiState()) {
    private var loadedId: String? = null

    private var loadJob: Job? = null

    override fun onEvent(event: ArtistEvent) {
        when (event) {
            is ArtistEvent.Load -> load(event.id, event.retry)
            is ArtistEvent.Play -> state.value.page?.let { player.play(it.topSongs, event.index, event.shuffle, PlaybackContext("Artista", it.artist.name)) }
            is ArtistEvent.PlaySong -> player.startRadio(event.song)
            ArtistEvent.Radio -> radio()
            ArtistEvent.Save -> viewModelScope.launch { state.value.page?.let { library.toggleSaved(MusicItem.ArtistItem(it.artist)) } }
            ArtistEvent.Pin -> viewModelScope.launch { state.value.page?.let { library.togglePin(MusicItem.ArtistItem(it.artist)) } }
            is ArtistEvent.SaveRelease -> viewModelScope.launch { library.toggleSaved(MusicItem.AlbumItem(event.album)) }
            ArtistEvent.Share -> viewModelScope.launch {
                state.value.page?.artist?.let { emit(ArtistEffect.Share("https://music.youtube.com/channel/${Uri.encode(it.id)}")) }
            }
            ArtistEvent.Download -> viewModelScope.launch {
                state.value.page?.let { downloads.enqueue(it.topSongs, settings.settings.first().downloadQuality) }
            }
        }
    }

    private fun radio() {
        val page = state.value.page ?: return
        val seed = page.topSongs.firstOrNull() ?: return
        viewModelScope.launch {
            val songs = runCatching { catalog.radio(seed.id, page.radioPlaylistId) }.getOrDefault(emptyList())
            if (songs.isEmpty()) player.startRadio(seed) else player.play(songs, context = PlaybackContext("Artista", page.artist.name))
        }
    }

    private fun load(id: String, retry: Boolean) {
        if (loadedId == id && !retry) return
        loadedId = id
        loadJob?.cancel()
        setState { ArtistUiState() }
        loadJob = viewModelScope.launch {
            launch { library.isSaved("r:$id").collect { setState { copy(saved = it) } } }
            launch { library.isPinned("r:$id").collect { setState { copy(pinned = it) } } }
            catalog.artist(id).catch { setState { copy(loading = false, error = it.message) } }.collect { res ->
                when (res) {
                    Resource.Loading -> setState { copy(loading = true) }
                    is Resource.Success -> setState { copy(loading = false, page = res.data, error = null) }
                    is Resource.Error -> setState { copy(loading = false, error = res.message) }
                }
            }
        }
    }
}

@Composable
fun ArtistScreen(artistId: String, onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: ArtistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(artistId) { viewModel.onEvent(ArtistEvent.Load(artistId)) }
    CollectEffects(viewModel.effects) {
        when (it) {
            is ArtistEffect.Share -> context.startActivity(
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it.text), "Compartir"),
            )
        }
    }
    ArtistContent(state, viewModel::onEvent, artistId, onNavigate, modifier)
}

@Composable
internal fun ArtistContent(
    state: ArtistUiState, onEvent: (ArtistEvent) -> Unit, artistId: String,
    onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier,
) {
    var menu by remember(artistId) { mutableStateOf<Song?>(null) }
    var info by remember(artistId) { mutableStateOf(false) }
    var more by remember(artistId) { mutableStateOf(false) }
    var expanded by remember(artistId) { mutableStateOf(false) }
    val page = state.page
    val featured = remember(page) { latestRelease(page) }
    ImmersiveDetail(
        imageUrl = page?.artist?.thumbnailUrl, title = page?.artist?.name.orEmpty(),
        meta = page?.artist?.subscribers?.let { "$it suscriptores" },
        onBack = { onNavigate(Nav.Back) }, modifier = modifier,
        topActions = {
            if (page != null) {
                GlassIconButton(OndaraIcons.Share, "Compartir artista",
                    { onEvent(ArtistEvent.Share) }, size = OndaraTheme.spacing.touch)
                GlassIconButton(OndaraIcons.More, "Más opciones", { more = true }, size = OndaraTheme.spacing.touch)
            }
        },
        centerActions = {
            if (page != null) {
                GlassIconButton(OndaraIcons.Info, "Información del artista", { info = true }, size = OndaraTheme.spacing.touch)
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    GlassIconButton(OndaraIcons.Play, "Reproducir artista", { onEvent(ArtistEvent.Play(0)) }, filled = true, size = 64.dp)
                }
                GlassIconButton(if (state.saved) OndaraIcons.StarFilled else OndaraIcons.Star,
                    if (state.saved) "Dejar de seguir" else "Seguir artista", { onEvent(ArtistEvent.Save) }, size = OndaraTheme.spacing.touch)
            }
        },
    ) {
        if (state.loading && page == null) items(6, key = { "sk$it" }, contentType = { "skeleton" }) { SongRowSkeleton() }
        if (page != null) {
            if (featured != null) item(key = "featured", contentType = "release") {
                FeaturedRelease(featured, { onNavigate(Nav.Album(featured.id)) }, { onEvent(ArtistEvent.SaveRelease(featured)) })
            }
            if (page.topSongs.isNotEmpty()) {
                item(key = "top", contentType = "heading") {
                    GlassCard {
                        Text("Canciones populares ›", Modifier.padding(OndaraTheme.spacing.md), style = OndaraTheme.type.title)
                    }
                }
                items(if (expanded) page.topSongs.size else minOf(5, page.topSongs.size),
                    key = { "song-$it-${page.topSongs[it].id}" }, contentType = { "song" }) { i ->
                    GlassCard(contentPadding = PaddingValues(0.dp)) {
                        ImmersiveSongRow(page.topSongs[i], i + 1, { onEvent(ArtistEvent.Play(i)) }, { menu = page.topSongs[i] })
                    }
                }
                if (page.topSongs.size > 5) item(key = "expand", contentType = "action") {
                    GlassCard { GlassPillButton(if (expanded) "Ver menos" else "Ver todas", { expanded = !expanded }) }
                }
            }
            items(page.shelves.size, key = { "shelf-$it" }, contentType = { "shelf" }) { i ->
                GlassCard(Modifier.padding(top = OndaraTheme.spacing.md)) {
                    Shelf(page.shelves[i], { item ->
                        item.toNav()?.let(onNavigate) ?: (item as? MusicItem.SongItem)?.let { onEvent(ArtistEvent.PlaySong(it.song)) }
                    })
                }
            }
        }
        if (state.error != null) item(key = "error", contentType = "error") {
            EmptyState(OndaraIcons.Person, "No se pudo abrir el artista", message = state.error)
            OndaraButton("Reintentar", { onEvent(ArtistEvent.Load(artistId, true)) })
        }
    }
    if (info) AboutSheet("Acerca de ${page?.artist?.name.orEmpty()}",
        listOfNotNull(page?.description ?: "Sin descripción disponible", page?.artist?.subscribers?.let { "$it suscriptores" }).joinToString("\n\n")) { info = false }
    if (more) OndaraSheet({ more = false }, title = page?.artist?.name) {
        MenuAction(OndaraIcons.Download, "Descargar canciones populares", { onEvent(ArtistEvent.Download); more = false })
        MenuAction(OndaraIcons.Star, if (state.pinned) "Desfijar de Biblioteca" else "Fijar en Biblioteca", { onEvent(ArtistEvent.Pin); more = false })
        MenuAction(OndaraIcons.Shuffle, "Reproducción aleatoria", { onEvent(ArtistEvent.Play(0, true)); more = false })
        MenuAction(OndaraIcons.Radio, "Iniciar radio", { onEvent(ArtistEvent.Radio); more = false })
    }
    menu?.let { song -> SongMenuSheet(song, { menu = null }, { onNavigate(Nav.Artist(it)) }, { onNavigate(Nav.Album(it)) }) }
}

/** Recorre las estanterías una vez: O(n) tiempo y O(1) espacio adicional. */
private fun latestRelease(page: ArtistPage?): Album? = page?.shelves?.asSequence()?.flatMap { it.items.asSequence() }
    ?.mapNotNull { (it as? MusicItem.AlbumItem)?.album }?.maxByOrNull { it.year ?: 0 }

@Composable
private fun FeaturedRelease(album: Album, onOpen: () -> Unit, onSave: () -> Unit) {
    GlassCard {
        Text("Lanzamiento destacado", Modifier.padding(horizontal = OndaraTheme.spacing.md), style = OndaraTheme.type.caption)
        Row(Modifier.fillMaxWidth().padding(OndaraTheme.spacing.md), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md)) {
            Artwork(album.thumbnailUrl, Modifier.size(72.dp).pressable(onClick = onOpen), sizePx = 256)
            Column(Modifier.weight(1f).pressable(onClick = onOpen)) {
                Text(album.title, style = OndaraTheme.type.bodyStrong, maxLines = 2)
                album.year?.let { Text("$it", color = LocalContentColor.current.copy(alpha = 0.7f), style = OndaraTheme.type.caption) }
            }
            GlassIconButton(OndaraIcons.Add, "Guardar ${album.title}", onSave, size = OndaraTheme.spacing.touch)
        }
    }
}
