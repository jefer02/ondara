package app.ondara.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ondara.core.designsystem.component.ButtonStyle
import app.ondara.core.designsystem.component.EmptyState
import app.ondara.core.designsystem.component.Label
import app.ondara.core.designsystem.component.OndaraButton
import app.ondara.core.designsystem.component.OndaraChip
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.ScreenScaffold
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.pressable
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.domain.DownloadRepository
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.model.DownloadInfo
import app.ondara.core.model.DownloadStatus
import app.ondara.core.model.Playlist
import app.ondara.core.model.Song
import app.ondara.core.ui.Artwork
import app.ondara.core.ui.CollectEffects
import app.ondara.core.ui.MviViewModel
import app.ondara.core.ui.NameDialog
import app.ondara.core.ui.Nav
import app.ondara.core.ui.SongMenuSheet
import app.ondara.core.ui.SongRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab(val label: String) { PLAYLISTS("Playlists"), FAVORITES("Favoritos"), HISTORY("Historial"), DOWNLOADS("Descargas") }

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.PLAYLISTS,
    val playlists: List<Playlist> = emptyList(),
    val favorites: List<Song> = emptyList(),
    val history: List<Song> = emptyList(),
    val downloads: List<DownloadInfo> = emptyList(),
) {
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
        viewModelScope.launch { library.playlists().collect { setState { copy(playlists = it) } } }
        viewModelScope.launch { library.favorites().collect { setState { copy(favorites = it) } } }
        viewModelScope.launch { library.history().collect { setState { copy(history = it) } } }
        viewModelScope.launch { downloads.downloads().collect { setState { copy(downloads = it) } } }
    }

    override fun onEvent(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.SelectTab -> setState { copy(tab = event.tab) }
            is LibraryEvent.Play -> player.play(state.value.songs, event.index, event.shuffle)
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
    var menu by remember { mutableStateOf<Song?>(null) }
    var creating by remember { mutableStateOf(false) }
    CollectEffects(viewModel.effects) { if (it is LibraryEffect.Navigate) onNavigate(it.nav) }
    ScreenScaffold(
        "Biblioteca", modifier,
        actions = {
            OndaraIconButton(OndaraIcons.Add, "Nueva playlist", { creating = true })
            OndaraIconButton(OndaraIcons.Settings, "Ajustes", { onNavigate(Nav.Settings) })
        },
    ) {
        item(key = "tabs", contentType = "tabs") {
            LazyRow(
                Modifier.padding(bottom = OndaraTheme.spacing.md),
                contentPadding = PaddingValues(horizontal = OndaraTheme.spacing.screen),
                horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm),
            ) {
                items(LibraryTab.entries, key = { it.name }) { tab -> OndaraChip(tab.label, state.tab == tab, { viewModel.onEvent(LibraryEvent.SelectTab(tab)) }) }
            }
        }
        if (state.tab == LibraryTab.PLAYLISTS) {
            if (state.playlists.isEmpty()) {
                item(key = "empty") {
                    EmptyState(OndaraIcons.Playlist, "Aún no tienes playlists", message = "Crea una o importa desde YouTube Music o un CSV en Ajustes.", action = "Crear playlist", onAction = { creating = true })
                }
            }
            items(state.playlists.size, key = { state.playlists[it].id }, contentType = { "playlist" }) { i ->
                val playlist = state.playlists[i]
                PlaylistRow(playlist) { viewModel.onEvent(LibraryEvent.OpenPlaylist(playlist.id)) }
            }
        } else {
            val songs = state.songs
            if (songs.isNotEmpty()) {
                item(key = "actions-${state.tab}", contentType = "actions") {
                    Row(Modifier.padding(horizontal = OndaraTheme.spacing.screen).padding(bottom = OndaraTheme.spacing.md), horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
                        OndaraButton("Reproducir", { viewModel.onEvent(LibraryEvent.Play(0)) }, icon = OndaraIcons.Play)
                        OndaraButton("Aleatorio", { viewModel.onEvent(LibraryEvent.Play(0, shuffle = true)) }, style = ButtonStyle.TONAL, icon = OndaraIcons.Shuffle)
                        if (state.tab == LibraryTab.HISTORY) OndaraIconButton(OndaraIcons.Delete, "Borrar historial", { viewModel.onEvent(LibraryEvent.ClearHistory) })
                    }
                }
            } else if (state.tab != LibraryTab.DOWNLOADS || state.downloads.isEmpty()) {
                item(key = "empty-${state.tab}") {
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
                        OndaraIconButton(OndaraIcons.Close, "Cancelar descarga", { viewModel.onEvent(LibraryEvent.RemoveDownload(info.song.id)) }, size = 18.dp)
                    })
                }
            }
            items(songs.size, key = { "${state.tab}-" + songs[it].id }, contentType = { "song" }) { i ->
                SongRow(songs[i], { viewModel.onEvent(LibraryEvent.Play(i)) }, entryIndex = i, onMore = { menu = songs[i] })
            }
        }
    }
    if (creating) NameDialog("Nueva playlist", "Crear", { creating = false }) { name ->
        viewModel.onEvent(LibraryEvent.CreatePlaylist(name))
        creating = false
    }
    menu?.let { song -> SongMenuSheet(song, { menu = null }, { onNavigate(Nav.Artist(it)) }, { onNavigate(Nav.Album(it)) }) }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick = onClick).padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(playlist.thumbnailUrl, Modifier.size(64.dp), shape = OndaraTheme.shapes.small)
        Spacer(Modifier.width(OndaraTheme.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(playlist.title, style = OndaraTheme.type.headline, maxLines = 1)
            Label("${playlist.songCount ?: 0} canciones")
        }
        Icon(OndaraIcons.ChevronRight, null, tint = OndaraTheme.colors.onMuted, size = 18.dp)
    }
}
