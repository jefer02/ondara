package app.ondara.feature.album

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.domain.CatalogRepository
import app.ondara.core.domain.DownloadRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.Resource
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.model.AlbumPage
import app.ondara.core.model.HomeShelf
import app.ondara.core.model.MusicItem
import app.ondara.core.model.Song
import app.ondara.core.ui.DetailScaffold
import app.ondara.core.ui.MviViewModel
import app.ondara.core.ui.Nav
import app.ondara.core.ui.Shelf
import app.ondara.core.ui.SongMenuSheet
import app.ondara.core.ui.SongRow
import app.ondara.core.ui.SongRowSkeleton
import app.ondara.core.ui.formatDuration
import app.ondara.core.ui.toNav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumUiState(val loading: Boolean = true, val page: AlbumPage? = null, val error: String? = null)

sealed interface AlbumEvent {
    data class Load(val id: String) : AlbumEvent
    data class Play(val index: Int, val shuffle: Boolean = false) : AlbumEvent
    data object Download : AlbumEvent
}

@HiltViewModel
class AlbumViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val player: PlayerController,
    private val downloads: DownloadRepository,
    private val settings: SettingsRepository,
) : MviViewModel<AlbumUiState, AlbumEvent, Nothing>(AlbumUiState()) {
    private var loadedId: String? = null

    override fun onEvent(event: AlbumEvent) {
        when (event) {
            is AlbumEvent.Load -> load(event.id)
            is AlbumEvent.Play -> state.value.page?.let { player.play(it.songs, event.index, event.shuffle) }
            AlbumEvent.Download -> viewModelScope.launch {
                state.value.page?.let { downloads.enqueue(it.songs, settings.settings.first().downloadQuality) }
            }
        }
    }

    private fun load(id: String) {
        if (loadedId == id) return
        loadedId = id
        viewModelScope.launch {
            catalog.album(id).collect { res ->
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
fun AlbumScreen(albumId: String, onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: AlbumViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<Song?>(null) }
    LaunchedEffect(albumId) { viewModel.onEvent(AlbumEvent.Load(albumId)) }
    val page = state.page
    val album = page?.album
    DetailScaffold(
        imageUrl = album?.thumbnailUrl,
        title = album?.title.orEmpty(),
        subtitle = listOfNotNull(album?.artists?.joinToString { it.name }?.takeIf { it.isNotEmpty() }, album?.year?.toString()).joinToString(" · "),
        onBack = { onNavigate(Nav.Back) },
        modifier = modifier,
        actions = { if (page != null) OndaraIconButton(OndaraIcons.Download, "Descargar álbum", { viewModel.onEvent(AlbumEvent.Download) }) },
        header = {
            if (page != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
                    OndaraButton("Reproducir", { viewModel.onEvent(AlbumEvent.Play(0)) }, icon = OndaraIcons.Play)
                    OndaraButton("Aleatorio", { viewModel.onEvent(AlbumEvent.Play(0, true)) }, style = ButtonStyle.TONAL, icon = OndaraIcons.Shuffle)
                }
            }
        },
    ) {
        if (state.loading && page == null) items(8, key = { "sk$it" }) { SongRowSkeleton() }
        if (page != null) {
            items(page.songs.size, key = { page.songs[it].id }, contentType = { "song" }) { i ->
                SongRow(page.songs[i], { viewModel.onEvent(AlbumEvent.Play(i)) }, trackNumber = i + 1, entryIndex = i, onMore = { menu = page.songs[i] })
            }
            item(key = "meta") {
                val total = page.songs.sumOf { it.durationSec }
                Text(
                    "${page.songs.size} canciones · ${formatDuration(total)}" + (page.description?.let { "\n\n$it" } ?: ""),
                    Modifier.padding(OndaraTheme.spacing.screen),
                    style = OndaraTheme.type.caption,
                    color = OndaraTheme.colors.onMuted,
                    maxLines = 8,
                )
            }
            if (page.otherVersions.isNotEmpty()) {
                item(key = "others") {
                    Shelf(HomeShelf("Otras versiones", null, page.otherVersions.map { MusicItem.AlbumItem(it) }), { item -> item.toNav()?.let(onNavigate) })
                }
            }
        } else if (!state.loading) {
            item { EmptyState(OndaraIcons.Album, "No se pudo abrir el álbum", message = state.error) }
        }
    }
    menu?.let { song -> SongMenuSheet(song, { menu = null }, { onNavigate(Nav.Artist(it)) }, {}) }
}
