package app.ondara.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import app.ondara.core.designsystem.component.EmptyState
import app.ondara.core.designsystem.component.OndaraChip
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.OndaraTextField
import app.ondara.core.designsystem.component.ScreenScaffold
import app.ondara.core.designsystem.component.SectionTitle
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.pressable
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.domain.CatalogRepository
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.model.MusicItem
import app.ondara.core.model.SearchFilter
import app.ondara.core.model.Song
import app.ondara.core.ui.CollectEffects
import app.ondara.core.ui.MusicItemRow
import app.ondara.core.ui.MviViewModel
import app.ondara.core.ui.Nav
import app.ondara.core.ui.SongMenuSheet
import app.ondara.core.ui.SongRowSkeleton
import app.ondara.core.ui.toNav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    /** Consulta enviada; vacía = se muestran historial y sugerencias. */
    val submitted: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val suggestions: List<String> = emptyList(),
    val history: List<String> = emptyList(),
)

sealed interface SearchEvent {
    data class QueryChanged(val query: String) : SearchEvent
    data class Submit(val query: String) : SearchEvent
    data class FilterChanged(val filter: SearchFilter) : SearchEvent
    data class RemoveHistory(val query: String) : SearchEvent
    data class Open(val item: MusicItem) : SearchEvent
    data object Clear : SearchEvent
}

sealed interface SearchEffect {
    data class Navigate(val nav: Nav) : SearchEffect
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    private val player: PlayerController,
) : MviViewModel<SearchUiState, SearchEvent, SearchEffect>(SearchUiState()) {
    private val query = MutableStateFlow("")
    private val request = MutableStateFlow("" to SearchFilter.ALL)

    /** Resultados paginados (Paging 3) de la consulta enviada. */
    val results: Flow<PagingData<MusicItem>> = request
        .flatMapLatest { (q, filter) -> if (q.isBlank()) flowOf(PagingData.empty()) else catalog.search(q, filter) }
        .cachedIn(viewModelScope)

    init {
        // Sugerencias en vivo: 300 ms de debounce y sin repetir la misma consulta.
        viewModelScope.launch {
            query.debounce(300).map { it.trim() }.distinctUntilChanged()
                .mapLatest { catalog.suggestions(it) }
                .collect { setState { copy(suggestions = it) } }
        }
        viewModelScope.launch { library.searchHistory().collect { setState { copy(history = it) } } }
    }

    override fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged -> {
                query.value = event.query
                setState { copy(query = event.query, submitted = if (event.query.isBlank()) "" else submitted) }
            }
            is SearchEvent.Submit -> submit(event.query)
            is SearchEvent.FilterChanged -> {
                setState { copy(filter = event.filter) }
                request.value = state.value.submitted to event.filter
            }
            is SearchEvent.RemoveHistory -> viewModelScope.launch { library.removeSearch(event.query) }
            is SearchEvent.Open -> event.item.toNav()?.let { emit(SearchEffect.Navigate(it)) }
                ?: player.startRadio((event.item as MusicItem.SongItem).song)
            SearchEvent.Clear -> {
                query.value = ""
                setState { copy(query = "", submitted = "", suggestions = emptyList()) }
            }
        }
    }

    private fun submit(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        query.value = q
        setState { copy(query = q, submitted = q) }
        request.value = q to state.value.filter
        viewModelScope.launch { library.addSearch(q) }
    }
}

private val FILTERS = listOf(
    SearchFilter.ALL to "Todo", SearchFilter.SONGS to "Canciones", SearchFilter.ALBUMS to "Álbumes",
    SearchFilter.ARTISTS to "Artistas", SearchFilter.PLAYLISTS to "Playlists",
)

@Composable
fun SearchScreen(onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val results = viewModel.results.collectAsLazyPagingItems()
    val focus = LocalFocusManager.current
    var menu by remember { mutableStateOf<Song?>(null) }
    CollectEffects(viewModel.effects) { if (it is SearchEffect.Navigate) onNavigate(it.nav) }
    ScreenScaffold("Buscar", modifier) {
        item(key = "field", contentType = "field") {
            OndaraTextField(
                value = state.query,
                onValueChange = { viewModel.onEvent(SearchEvent.QueryChanged(it)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = OndaraTheme.spacing.screen),
                placeholder = "Canciones, artistas, álbumes",
                leading = OndaraIcons.Search,
                imeAction = ImeAction.Search,
                onImeAction = {
                    focus.clearFocus()
                    viewModel.onEvent(SearchEvent.Submit(state.query))
                },
                trailing = {
                    if (state.query.isNotEmpty()) {
                        OndaraIconButton(OndaraIcons.Close, "Borrar búsqueda", { viewModel.onEvent(SearchEvent.Clear) }, size = 18.dp)
                    }
                },
            )
        }
        if (state.submitted.isEmpty()) {
            val suggestions = if (state.query.isBlank()) emptyList() else state.suggestions
            items(suggestions.size, key = { "sug-" + suggestions[it] }, contentType = { "suggestion" }) { i ->
                TermRow(suggestions[i], OndaraIcons.Search, { focus.clearFocus(); viewModel.onEvent(SearchEvent.Submit(suggestions[i])) })
            }
            if (suggestions.isEmpty() && state.history.isNotEmpty()) {
                item(key = "history-title") { SectionTitle("Recientes", Modifier.padding(top = OndaraTheme.spacing.lg)) }
                items(state.history.size, key = { "his-" + state.history[it] }, contentType = { "history" }) { i ->
                    val term = state.history[i]
                    TermRow(term, OndaraIcons.History, { focus.clearFocus(); viewModel.onEvent(SearchEvent.Submit(term)) }) {
                        OndaraIconButton(OndaraIcons.Close, "Quitar $term del historial", { viewModel.onEvent(SearchEvent.RemoveHistory(term)) }, size = 16.dp)
                    }
                }
            }
            if (suggestions.isEmpty() && state.history.isEmpty()) {
                item(key = "hint") { EmptyState(OndaraIcons.Search, "Busca en YouTube Music", message = "Todo el catálogo público, sin anuncios.") }
            }
        } else {
            item(key = "filters", contentType = "filters") {
                LazyRow(
                    Modifier.padding(vertical = OndaraTheme.spacing.md),
                    contentPadding = PaddingValues(horizontal = OndaraTheme.spacing.screen),
                    horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm),
                ) {
                    items(FILTERS, key = { it.first }) { (filter, label) ->
                        OndaraChip(label, state.filter == filter, { viewModel.onEvent(SearchEvent.FilterChanged(filter)) })
                    }
                }
            }
            when (val refresh = results.loadState.refresh) {
                is LoadState.Loading -> items(8, key = { "sk$it" }, contentType = { "skeleton" }) { SongRowSkeleton() }
                is LoadState.Error -> item(key = "error") {
                    EmptyState(OndaraIcons.Wave, "No se pudo buscar", message = refresh.error.message, action = "Reintentar", onAction = { results.retry() })
                }
                else -> {
                    if (results.itemCount == 0) item(key = "none") { EmptyState(OndaraIcons.Search, "Sin resultados", message = "Prueba con otras palabras.") }
                    items(results.itemCount, key = { results.peek(it)?.key ?: "i$it" }, contentType = { "result" }) { i ->
                        val item = results[i] ?: return@items
                        MusicItemRow(item, { viewModel.onEvent(SearchEvent.Open(item)) }, onMore = (item as? MusicItem.SongItem)?.let { s -> { menu = s.song } })
                    }
                    if (results.loadState.append is LoadState.Loading) items(2, key = { "more$it" }) { SongRowSkeleton() }
                }
            }
        }
    }
    menu?.let { song -> SongMenuSheet(song, { menu = null }, { onNavigate(Nav.Artist(it)) }, { onNavigate(Nav.Album(it)) }) }
}

@Composable
private fun TermRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick = onClick).padding(start = OndaraTheme.spacing.screen, end = OndaraTheme.spacing.xs, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = OndaraTheme.colors.onMuted, size = 18.dp)
        Spacer(Modifier.width(OndaraTheme.spacing.lg))
        Text(text, Modifier.weight(1f).padding(vertical = 10.dp), style = OndaraTheme.type.headline, maxLines = 1)
        trailing()
    }
}
