package app.ondara.feature.artist

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
import app.ondara.core.designsystem.component.SectionTitle
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.domain.CatalogRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.Resource
import app.ondara.core.model.ArtistPage
import app.ondara.core.model.MusicItem
import app.ondara.core.model.Song
import app.ondara.core.ui.DetailScaffold
import app.ondara.core.ui.MviViewModel
import app.ondara.core.ui.Nav
import app.ondara.core.ui.Shelf
import app.ondara.core.ui.SongMenuSheet
import app.ondara.core.ui.SongRow
import app.ondara.core.ui.SongRowSkeleton
import app.ondara.core.ui.toNav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistUiState(val loading: Boolean = true, val page: ArtistPage? = null, val error: String? = null)

sealed interface ArtistEvent {
    data class Load(val id: String) : ArtistEvent
    data class Play(val index: Int, val shuffle: Boolean = false) : ArtistEvent
    data object Radio : ArtistEvent
    data class PlaySong(val song: Song) : ArtistEvent
}

@HiltViewModel
class ArtistViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val player: PlayerController,
) : MviViewModel<ArtistUiState, ArtistEvent, Nothing>(ArtistUiState()) {
    private var loadedId: String? = null

    override fun onEvent(event: ArtistEvent) {
        when (event) {
            is ArtistEvent.Load -> load(event.id)
            is ArtistEvent.Play -> state.value.page?.let { player.play(it.topSongs, event.index, event.shuffle) }
            is ArtistEvent.PlaySong -> player.startRadio(event.song)
            ArtistEvent.Radio -> radio()
        }
    }

    private fun radio() {
        val page = state.value.page ?: return
        val seed = page.topSongs.firstOrNull() ?: return
        viewModelScope.launch {
            val songs = runCatching { catalog.radio(seed.id, page.radioPlaylistId) }.getOrDefault(emptyList())
            if (songs.isEmpty()) player.startRadio(seed) else player.play(songs)
        }
    }

    private fun load(id: String) {
        if (loadedId == id) return
        loadedId = id
        viewModelScope.launch {
            catalog.artist(id).collect { res ->
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
    var menu by remember { mutableStateOf<Song?>(null) }
    LaunchedEffect(artistId) { viewModel.onEvent(ArtistEvent.Load(artistId)) }
    val page = state.page
    DetailScaffold(
        imageUrl = page?.artist?.thumbnailUrl,
        title = page?.artist?.name.orEmpty(),
        subtitle = page?.artist?.subscribers?.let { "$it suscriptores" }.orEmpty(),
        onBack = { onNavigate(Nav.Back) },
        modifier = modifier,
        header = {
            if (page != null && page.topSongs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
                    OndaraButton("Aleatorio", { viewModel.onEvent(ArtistEvent.Play(0, true)) }, icon = OndaraIcons.Shuffle)
                    OndaraButton("Radio", { viewModel.onEvent(ArtistEvent.Radio) }, style = ButtonStyle.TONAL, icon = OndaraIcons.Radio)
                }
            }
        },
    ) {
        if (state.loading && page == null) items(6, key = { "sk$it" }) { SongRowSkeleton() }
        if (page != null) {
            if (page.topSongs.isNotEmpty()) item(key = "top") { SectionTitle("Populares") }
            items(page.topSongs.size, key = { page.topSongs[it].id }, contentType = { "song" }) { i ->
                SongRow(page.topSongs[i], { viewModel.onEvent(ArtistEvent.Play(i)) }, entryIndex = i, onMore = { menu = page.topSongs[i] })
            }
            items(page.shelves.size, key = { "shelf-$it" }, contentType = { "shelf" }) { i ->
                Shelf(page.shelves[i], { item ->
                    item.toNav()?.let(onNavigate) ?: viewModel.onEvent(ArtistEvent.PlaySong((item as MusicItem.SongItem).song))
                }, Modifier.padding(top = if (i == 0) OndaraTheme.spacing.lg else OndaraTheme.spacing.xxs))
            }
            page.description?.let { about ->
                item(key = "about") {
                    SectionTitle("Acerca de")
                    Text(about, Modifier.padding(horizontal = OndaraTheme.spacing.screen), color = OndaraTheme.colors.onMuted, maxLines = 12)
                }
            }
        } else if (!state.loading) {
            item { EmptyState(OndaraIcons.Person, "No se pudo abrir el artista", message = state.error) }
        }
    }
    menu?.let { song -> SongMenuSheet(song, { menu = null }, {}, { onNavigate(Nav.Album(it)) }) }
}
