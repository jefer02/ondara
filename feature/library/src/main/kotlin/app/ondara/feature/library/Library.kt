package app.ondara.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ondara.core.designsystem.component.ButtonStyle
import app.ondara.core.designsystem.component.EmptyState
import app.ondara.core.designsystem.component.Hairline
import app.ondara.core.designsystem.component.Label
import app.ondara.core.designsystem.component.LocalBottomInset
import app.ondara.core.designsystem.component.OndaraButton
import app.ondara.core.designsystem.component.OndaraChip
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.ScreenScaffold
import app.ondara.core.designsystem.component.SectionTitle
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.pressable
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.ListStyle
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.domain.DownloadRepository
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.model.DownloadInfo
import app.ondara.core.model.DownloadStatus
import app.ondara.core.model.MusicItem
import app.ondara.core.model.PlaybackContext
import app.ondara.core.model.Playlist
import app.ondara.core.model.PlaylistSource
import app.ondara.core.model.SavedItem
import app.ondara.core.model.Song
import app.ondara.core.ui.Artwork
import app.ondara.core.ui.AvatarButton
import app.ondara.core.ui.CollectEffects
import app.ondara.core.ui.MviViewModel
import app.ondara.core.ui.NameDialog
import app.ondara.core.ui.Nav
import app.ondara.core.ui.PlaylistCover
import app.ondara.core.ui.SongMenuSheet
import app.ondara.core.ui.SongRow
import app.ondara.core.ui.imageUrl
import app.ondara.core.ui.titleText
import app.ondara.core.ui.toNav
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class LibraryTab(val label: String) { PLAYLISTS("Playlists"), FAVORITES("Favoritos"), HISTORY("Historial"), DOWNLOADS("Descargas") }

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.PLAYLISTS,
    val playlists: List<Playlist> = emptyList(),
    val pins: List<MusicItem> = emptyList(),
    val saved: List<SavedItem> = emptyList(),
    val source: PlaylistSource? = null,
    val notice: String? = null,
    val favorites: List<Song> = emptyList(),
    val history: List<Song> = emptyList(),
    val downloads: List<DownloadInfo> = emptyList(),
) {
    val isEmpty: Boolean get() = listOf(playlists, saved, pins, favorites, downloads).all { it.isEmpty() }

    val songs: List<Song>
        get() = when (tab) {
            LibraryTab.FAVORITES -> favorites
            LibraryTab.HISTORY -> history
            LibraryTab.DOWNLOADS -> downloads.filter { it.status == DownloadStatus.COMPLETED }.map { it.song }
            LibraryTab.PLAYLISTS -> emptyList()
        }
}

sealed interface LibraryEvent {
    data class SelectTab(val tab: LibraryTab) : LibraryEvent
    data class Play(val index: Int, val shuffle: Boolean = false) : LibraryEvent
    data class CreatePlaylist(val name: String) : LibraryEvent
    data class OpenPlaylist(val id: String) : LibraryEvent
    data class RemoveDownload(val songId: String) : LibraryEvent
    data class TogglePin(val item: MusicItem) : LibraryEvent
    data class SourceChanged(val source: PlaylistSource?) : LibraryEvent
    data class Open(val item: MusicItem) : LibraryEvent
    data object DismissNotice : LibraryEvent
    data object ClearHistory : LibraryEvent
}

sealed interface LibraryEffect {
    data class Navigate(val nav: Nav) : LibraryEffect
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val player: PlayerController,
) : MviViewModel<LibraryUiState, LibraryEvent, LibraryEffect>(LibraryUiState()) {
    init {
        viewModelScope.launch { library.pins().collect { setState { copy(pins = it) } } }
        viewModelScope.launch { library.saved().collect { setState { copy(saved = it) } } }
        viewModelScope.launch { library.playlists().collect { setState { copy(playlists = it) } } }
        viewModelScope.launch { library.favorites().collect { setState { copy(favorites = it) } } }
        viewModelScope.launch { library.history().collect { setState { copy(history = it) } } }
        viewModelScope.launch { downloads.downloads().collect { setState { copy(downloads = it) } } }
    }

    override fun onEvent(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.SelectTab -> setState { copy(tab = event.tab) }
            is LibraryEvent.Play -> player.play(
                state.value.songs, event.index, event.shuffle, PlaybackContext("Biblioteca", state.value.tab.label),
            )
            is LibraryEvent.TogglePin -> viewModelScope.launch {
                if (!library.togglePin(event.item)) setState { copy(notice = "Solo puedes fijar 6 elementos") }
            }
            is LibraryEvent.SourceChanged -> setState { copy(source = event.source) }
            is LibraryEvent.Open -> event.item.toNav()?.let { emit(LibraryEffect.Navigate(it)) }
            LibraryEvent.DismissNotice -> setState { copy(notice = null) }
            is LibraryEvent.CreatePlaylist -> viewModelScope.launch { library.createPlaylist(event.name) }
            is LibraryEvent.OpenPlaylist -> emit(LibraryEffect.Navigate(Nav.Playlist(event.id)))
            is LibraryEvent.RemoveDownload -> viewModelScope.launch { downloads.remove(event.songId) }
            LibraryEvent.ClearHistory -> viewModelScope.launch { library.clearHistory() }
        }
    }
}

@Composable
fun LibraryScreen(onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: LibraryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEffects(viewModel.effects) { if (it is LibraryEffect.Navigate) onNavigate(it.nav) }
    LibraryContent(state, viewModel::onEvent, onNavigate, modifier)
}

@Composable
internal fun LibraryContent(state: LibraryUiState, onEvent: (LibraryEvent) -> Unit, onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier) {
    val recent = remember(state.playlists, state.saved, state.source) { recentLibraryItems(state) }
    var creating by remember { mutableStateOf(false) }
    Box(modifier) {
        ScreenScaffold("Biblioteca", actions = { AvatarButton(onClick = { onNavigate(Nav.Account) }) }) {
            item(key = "pins-title", contentType = "heading") { SectionTitle("Fijados") }
            val pins = state.pins.filter { it.matches(state.source) }.take(6)
            if (pins.isEmpty()) item(key = "pin-help", contentType = "help") {
                Text(
                    "Mantén pulsado un elemento para fijarlo aquí",
                    Modifier.padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.md),
                    style = OndaraTheme.type.caption, color = OndaraTheme.colors.onMuted,
                )
            }
            libraryGrid(pins, 3, "pins", onEvent)
            items(CATEGORIES, key = { it.id }, contentType = { "category" }) { category ->
                CategoryRow(category) { onNavigate(Nav.LibraryCategory(category.id)) }
            }
            item(key = "sources", contentType = "filters") { SourceChips(state.source, onEvent) }
            item(key = "recent-title", contentType = "heading") { SectionTitle("Agregado recientemente") }
            libraryGrid(recent, 2, "recent", onEvent)
            if (state.isEmpty) item(key = "empty", contentType = "empty") {
                EmptyState(OndaraIcons.Library, "Tu biblioteca empieza aquí", action = "Crear playlist", onAction = { creating = true })
                OndaraButton("Conectar cuentas", { onNavigate(Nav.Account) }, Modifier.padding(horizontal = OndaraTheme.spacing.screen), style = ButtonStyle.GHOST)
            }
        }
        PinNotice(state.notice, { onEvent(LibraryEvent.DismissNotice) }, Modifier.align(Alignment.BottomCenter))
    }
    if (creating) NameDialog("Nueva playlist", "Crear", { creating = false }) { name ->
        onEvent(LibraryEvent.CreatePlaylist(name))
        creating = false
    }
}

@Composable
fun LibraryCategoryScreen(
    category: String,
    onNavigate: (Nav) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Song?>(null) }
    LaunchedEffect(category) {
        viewModel.onEvent(LibraryEvent.SelectTab(if (category == "downloads") LibraryTab.DOWNLOADS else LibraryTab.FAVORITES))
    }
    CollectEffects(viewModel.effects) { if (it is LibraryEffect.Navigate) onNavigate(it.nav) }
    Box(modifier) {
        ScreenScaffold(CATEGORIES.firstOrNull { it.id == category }?.title ?: "Biblioteca", onBack = { onNavigate(Nav.Back) }) {
            when (category) {
                "playlists" -> {
                    item(key = "create", contentType = "actions") {
                        OndaraButton("Nueva playlist", { creating = true }, Modifier.padding(horizontal = OndaraTheme.spacing.screen), icon = OndaraIcons.Add)
                        SourceChips(state.source, viewModel::onEvent)
                    }
                    libraryGrid(state.playlists.map { MusicItem.PlaylistItem(it) }.filter { it.matches(state.source) }, 2, "playlists", viewModel::onEvent)
                    if (state.playlists.isEmpty()) item(key = "empty", contentType = "empty") {
                        EmptyState(OndaraIcons.Playlist, "Aún no tienes playlists", action = "Crear playlist", onAction = { creating = true })
                    }
                }
                "artists", "albums" -> {
                    val saved = state.saved.map { it.item }.filter {
                        if (category == "artists") it is MusicItem.ArtistItem else it is MusicItem.AlbumItem
                    }
                    libraryGrid(saved, 2, category, viewModel::onEvent)
                    if (saved.isEmpty()) item(key = "empty", contentType = "empty") {
                        EmptyState(OndaraIcons.Library, "Sin elementos guardados", message = "Guarda álbumes o sigue artistas para encontrarlos aquí.")
                    }
                }
                else -> {
                    if (category == "songs") item(key = "tabs", contentType = "tabs") {
                        Row(Modifier.padding(horizontal = OndaraTheme.spacing.screen), horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
                            listOf(LibraryTab.FAVORITES, LibraryTab.HISTORY).forEach { tab ->
                                OndaraChip(tab.label, state.tab == tab, { viewModel.onEvent(LibraryEvent.SelectTab(tab)) })
                            }
                        }
                    }
                    librarySongs(state, viewModel::onEvent) { menu = it }
                }
            }
        }
        PinNotice(state.notice, { viewModel.onEvent(LibraryEvent.DismissNotice) }, Modifier.align(Alignment.BottomCenter))
    }
    if (creating) NameDialog("Nueva playlist", "Crear", { creating = false }) { name ->
        viewModel.onEvent(LibraryEvent.CreatePlaylist(name))
        creating = false
    }
    menu?.let { song -> SongMenuSheet(song, { menu = null }, { onNavigate(Nav.Artist(it)) }, { onNavigate(Nav.Album(it)) }) }
}

private data class LibraryCategory(val id: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val CATEGORIES = listOf(
    LibraryCategory("playlists", "Playlists", OndaraIcons.Playlist),
    LibraryCategory("artists", "Artistas", OndaraIcons.Person),
    LibraryCategory("albums", "Álbumes", OndaraIcons.Album),
    LibraryCategory("songs", "Canciones", OndaraIcons.Note),
    LibraryCategory("downloads", "Descargas", OndaraIcons.Download),
)

@Composable
private fun CategoryRow(category: LibraryCategory, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = OndaraTheme.spacing.touch).pressable(onClick = onClick)
                .padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(category.icon, null, tint = OndaraTheme.colors.accent)
            Text(category.title, Modifier.weight(1f).padding(horizontal = OndaraTheme.spacing.lg), style = OndaraTheme.type.title)
            Icon(OndaraIcons.ChevronRight, null, tint = OndaraTheme.colors.onMuted)
        }
        if (OndaraTheme.spec.list == ListStyle.SEPARATORS) Hairline(startIndent = OndaraTheme.spacing.screen)
    }
}

@Composable
private fun SourceChips(source: PlaylistSource?, onEvent: (LibraryEvent) -> Unit) {
    LazyRow(
        Modifier.padding(vertical = OndaraTheme.spacing.md),
        contentPadding = PaddingValues(horizontal = OndaraTheme.spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm),
    ) {
        items(listOf(null) + PlaylistSource.entries, key = { it?.name ?: "all" }, contentType = { "source" }) { value ->
            val label = when (value) {
                null -> "Todo"
                PlaylistSource.ONDARA -> "Ondara"
                PlaylistSource.YOUTUBE -> "YouTube"
                PlaylistSource.SPOTIFY -> "Spotify"
            }
            OndaraChip(label, source == value, { onEvent(LibraryEvent.SourceChanged(value)) })
        }
    }
}

private fun MusicItem.matches(source: PlaylistSource?): Boolean =
    source == null || ((this as? MusicItem.PlaylistItem)?.playlist?.source ?: PlaylistSource.ONDARA) == source

/** O(n + m log m) tiempo y O(n + m) espacio (n playlists, m guardados); sin fecha, las playlists conservan el orden del repositorio. */
private fun recentLibraryItems(state: LibraryUiState): List<MusicItem> =
    (state.saved.sortedByDescending { it.addedAtMs }.map { it.item } + state.playlists.map { MusicItem.PlaylistItem(it) })
        .distinctBy { it.key }.filter { it.matches(state.source) }

/** Filas no desplazables dentro de la lista: O(n) tiempo y espacio para repartir los elementos. */
private fun LazyListScope.libraryGrid(items: List<MusicItem>, columns: Int, prefix: String, onEvent: (LibraryEvent) -> Unit) {
    items(items.chunked(columns), key = { prefix + it.first().key }, contentType = { "artwork-row-$columns" }) { row ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md),
        ) {
            row.forEach { item ->
                Column(
                    Modifier.weight(1f).combinedClickable(
                        onClick = { onEvent(LibraryEvent.Open(item)) }, onLongClickLabel = "Fijar o desfijar",
                        onLongClick = { onEvent(LibraryEvent.TogglePin(item)) },
                    ), horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val cover = Modifier.fillMaxWidth().aspectRatio(1f)
                    if (item is MusicItem.PlaylistItem) PlaylistCover(item.playlist, cover)
                    else Artwork(item.imageUrl(), cover, shape = if (item is MusicItem.ArtistItem) CircleShape else OndaraTheme.shapes.artwork)
                    Text(item.titleText(), Modifier.fillMaxWidth().padding(top = OndaraTheme.spacing.sm),
                        style = OndaraTheme.type.body, maxLines = 2, textAlign = TextAlign.Center)
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PinNotice(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(message) {
        if (message != null) { delay(2500); onDismiss() }
    }
    if (message != null) Text(
        message,
        modifier.padding(bottom = LocalBottomInset.current).padding(OndaraTheme.spacing.screen).clip(OndaraTheme.shapes.control).background(OndaraTheme.colors.surfaceHigh)
            .padding(OndaraTheme.spacing.lg), style = OndaraTheme.type.bodyStrong, color = OndaraTheme.colors.onBackground,
    )
}

private fun LazyListScope.librarySongs(state: LibraryUiState, onEvent: (LibraryEvent) -> Unit, onMenu: (Song) -> Unit) {
    val songs = state.songs
    if (songs.isNotEmpty()) {
        item(key = "actions-${state.tab}", contentType = "actions") {
            Row(Modifier.padding(horizontal = OndaraTheme.spacing.screen).padding(bottom = OndaraTheme.spacing.md), horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
                OndaraButton("Reproducir", { onEvent(LibraryEvent.Play(0)) }, Modifier.weight(1f), icon = OndaraIcons.Play)
                OndaraButton("Aleatorio", { onEvent(LibraryEvent.Play(0, shuffle = true)) }, Modifier.weight(1f), style = ButtonStyle.TONAL, icon = OndaraIcons.Shuffle)
            }
            if (state.tab == LibraryTab.HISTORY) OndaraButton(
                "Borrar historial", { onEvent(LibraryEvent.ClearHistory) },
                Modifier.padding(horizontal = OndaraTheme.spacing.screen), style = ButtonStyle.GHOST, icon = OndaraIcons.Delete,
            )
        }
    } else if (state.tab != LibraryTab.DOWNLOADS || state.downloads.isEmpty()) {
        item(key = "empty-${state.tab}", contentType = "empty") {
            when (state.tab) {
                LibraryTab.FAVORITES -> EmptyState(OndaraIcons.Heart, "Sin favoritos", message = "Toca el corazón en cualquier canción.")
                LibraryTab.HISTORY -> EmptyState(OndaraIcons.History, "Sin historial", message = "Lo que escuches aparecerá aquí.")
                else -> EmptyState(OndaraIcons.Download, "Sin descargas", message = "Descarga canciones para escucharlas sin conexión.")
            }
        }
    }
    if (state.tab == LibraryTab.DOWNLOADS) {
        val pending = state.downloads.filter { it.status != DownloadStatus.COMPLETED }
        items(pending.size, key = { "pending-" + pending[it].song.id }, contentType = { "pending" }) { i ->
            val info = pending[i]
            SongRow(info.song, {}, trailing = {
                Label(if (info.status == DownloadStatus.FAILED) "Falló" else "${(info.progress * 100).toInt()} %", color = OndaraTheme.colors.accent)
                OndaraIconButton(OndaraIcons.Close, "Cancelar descarga", { onEvent(LibraryEvent.RemoveDownload(info.song.id)) }, size = 18.dp)
            })
        }
    }
    items(songs.size, key = { "${state.tab}-" + songs[it].id }, contentType = { "song" }) { i ->
        SongRow(songs[i], { onEvent(LibraryEvent.Play(i)) }, entryIndex = i, onMore = { onMenu(songs[i]) })
    }
}
