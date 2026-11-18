package app.ondara.feature.news

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NewsUiState(
    val loading: Boolean = true,
    val shelves: List<HomeShelf> = emptyList(),
    val moods: List<MoodCategory> = emptyList(),
    val error: String? = null,
)

sealed interface NewsEvent {
    data object Refresh : NewsEvent
    data class Open(val item: MusicItem, val shelf: HomeShelf) : NewsEvent
    data class Navigate(val nav: Nav) : NewsEvent
}

sealed interface NewsEffect {
    data class Navigate(val nav: Nav) : NewsEffect
}

@HiltViewModel
class NewsViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val player: PlayerController,
) : MviViewModel<NewsUiState, NewsEvent, NewsEffect>(NewsUiState()) {
    private var loadJob: Job? = null

    init { load(false) }

    override fun onEvent(event: NewsEvent) {
        when (event) {
            NewsEvent.Refresh -> load(true)
            is NewsEvent.Navigate -> emit(NewsEffect.Navigate(event.nav))
            is NewsEvent.Open -> open(event.item, event.shelf)
        }
    }

    /** Reproduce las canciones de la estantería desde la seleccionada. O(n) en tiempo y espacio. */
    private fun open(item: MusicItem, shelf: HomeShelf) {
        item.toNav()?.let { emit(NewsEffect.Navigate(it)); return }
        val songs = shelf.items.mapNotNull { (it as? MusicItem.SongItem)?.song }
        val index = songs.indexOfFirst { it.id == (item as MusicItem.SongItem).song.id }
        if (index >= 0) player.play(songs, index, context = PlaybackContext("Novedades", shelf.title))
    }

    private fun load(force: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            setState { copy(loading = shelves.isEmpty(), error = null) }
            launch {
                catalog.moods().collect { res ->
                    when (res) {
                        Resource.Loading -> Unit
                        is Resource.Success -> setState { copy(moods = res.data) }
                        is Resource.Error -> setState { copy(moods = res.cached ?: moods, error = res.message) }
                    }
                }
            }
            catalog.news(force).collect { res ->
                when (res) {
                    Resource.Loading -> Unit
                    is Resource.Success -> setState { copy(loading = false, shelves = res.data.filter { it.items.isNotEmpty() }) }
                    is Resource.Error -> setState { copy(loading = false, shelves = res.cached ?: shelves, error = res.message) }
                }
            }
        }
    }
}

@Composable
fun NewsScreen(onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: NewsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val send = viewModel::onEvent
    CollectEffects(viewModel.effects) { if (it is NewsEffect.Navigate) onNavigate(it.nav) }
    ScreenScaffold("Novedades", modifier, actions = { AvatarButton(onClick = { send(NewsEvent.Navigate(Nav.Account)) }) }) {
        if (state.loading) items(3, key = { "skeleton-$it" }, contentType = { "skeleton" }) { ShelfSkeleton() }
        items(state.shelves.size, key = { "shelf-$it-${state.shelves[it].title}" }, contentType = { "shelf" }) { i ->
            val shelf = state.shelves[i]
            Shelf(shelf, { send(NewsEvent.Open(it, shelf)) }, Modifier.staggeredEntry(i, enabled = true))
        }
        if (state.moods.isNotEmpty()) {
            item(key = "moods", contentType = "moods") {
                Column(Modifier.padding(bottom = OndaraTheme.spacing.xl).staggeredEntry(state.shelves.size, enabled = true)) {
                    SectionTitle("Géneros y estados de ánimo")
                    LazyRow(contentPadding = PaddingValues(horizontal = OndaraTheme.spacing.screen), horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
                        items(state.moods, key = { it.browseId + it.params }, contentType = { "mood" }) { mood ->
                            OndaraChip(mood.title, false, { send(NewsEvent.Navigate(Nav.Mood(mood.title, mood.browseId, mood.params))) }, Modifier.heightIn(min = OndaraTheme.spacing.touch))
                        }
                    }
                }
            }
        }
        if (!state.loading && (state.error != null || state.shelves.isEmpty())) {
            item(key = "error", contentType = "error") {
                EmptyState(OndaraIcons.Wave, "No se pudieron cargar las novedades", message = state.error, action = "Reintentar", onAction = { send(NewsEvent.Refresh) })
            }
        }
    }
}
