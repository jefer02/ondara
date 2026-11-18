package app.ondara.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
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
import app.ondara.core.designsystem.component.GlassSurface
import app.ondara.core.designsystem.component.OndaraChip
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.OndaraTextField
import app.ondara.core.designsystem.component.ScreenScaffold
import app.ondara.core.designsystem.component.SectionTitle
import app.ondara.core.designsystem.component.Skeleton
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.pressable
import app.ondara.core.designsystem.icon.Icon
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.LocalOndaraColors
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.domain.CatalogRepository
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.Resource
import app.ondara.core.model.MoodCategory
import app.ondara.core.model.MusicItem
import app.ondara.core.model.SearchFilter
import app.ondara.core.model.Song
import app.ondara.core.ui.Artwork
import app.ondara.core.ui.AvatarButton
import app.ondara.core.ui.CollectEffects
import app.ondara.core.ui.MusicItemRow
import app.ondara.core.ui.MviViewModel
import app.ondara.core.ui.Nav
import app.ondara.core.ui.SongMenuSheet
import app.ondara.core.ui.SongRowSkeleton
import app.ondara.core.ui.toNav
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class SearchUiState(
    val query: String = "",
    /** Consulta enviada; vacía = se muestran historial y sugerencias. */
    val submitted: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val suggestions: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val moods: List<MoodCategory> = emptyList(),
    val moodsLoading: Boolean = true,
    val moodsError: String? = null,
    val artwork: Map<MoodCategory, String?> = emptyMap(),
)

sealed interface SearchEvent {
    data class QueryChanged(val query: String) : SearchEvent
    data class Submit(val query: String) : SearchEvent
    data class FilterChanged(val filter: SearchFilter) : SearchEvent
    data class RemoveHistory(val query: String) : SearchEvent
    data class Open(val item: MusicItem, val songEquivalent: Song? = null) : SearchEvent
    data class LoadArtwork(val mood: MoodCategory) : SearchEvent
    data object ClearHistory : SearchEvent
    data object RetryMoods : SearchEvent
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
    private val artworkRequests = mutableSetOf<MoodCategory>()
    private val artworkSemaphore = Semaphore(3)
    private val query = MutableStateFlow("")
    private val request = MutableStateFlow("" to SearchFilter.ALL)

    /** Resultados paginados (Paging 3) de la consulta enviada. */
    val results: Flow<PagingData<MusicItem>> = request
        .flatMapLatest { (q, filter) ->
            flow {
                emit(PagingData.empty<MusicItem>())
                if (q.isNotBlank()) emitAll(catalog.search(q, filter))
            }
        }
        .buffer(0)
        .cachedIn(viewModelScope)

    init {
        loadMoods()
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
            is SearchEvent.LoadArtwork -> loadArtwork(event.mood)
            SearchEvent.ClearHistory -> viewModelScope.launch { state.value.history.forEach { library.removeSearch(it) } }
            SearchEvent.RetryMoods -> loadMoods()
            is SearchEvent.QueryChanged -> {
                query.value = event.query
                request.value = "" to state.value.filter
                setState { copy(query = event.query, submitted = "") }
            }
            is SearchEvent.Submit -> submit(event.query)
            is SearchEvent.FilterChanged -> {
                setState { copy(filter = event.filter) }
                request.value = state.value.submitted to event.filter
            }
            is SearchEvent.RemoveHistory -> viewModelScope.launch { library.removeSearch(event.query) }
            is SearchEvent.Open -> event.item.toNav()?.let { emit(SearchEffect.Navigate(it)) }
                ?: player.startRadio(event.songEquivalent ?: (event.item as MusicItem.SongItem).song)
            SearchEvent.Clear -> {
                query.value = ""
                request.value = "" to state.value.filter
                setState { copy(query = "", submitted = "", suggestions = emptyList()) }
            }
        }
    }

    private fun loadMoods() {
        viewModelScope.launch {
            catalog.moods().collect { resource ->
                when (resource) {
                    Resource.Loading -> setState { copy(moodsLoading = true, moodsError = null) }
                    is Resource.Success -> setState { copy(moods = resource.data, moodsLoading = false, moodsError = null) }
                    is Resource.Error -> setState {
                        copy(moods = resource.cached.orEmpty(), moodsLoading = false, moodsError = resource.message)
                    }
                }
            }
        }
    }

    private fun loadArtwork(mood: MoodCategory) {
        if (!artworkRequests.add(mood)) return
        viewModelScope.launch {
            val url = try {
                artworkSemaphore.withPermit { catalog.moodArtwork(mood) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            setState { copy(artwork = artwork + (mood to url)) }
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
    CollectEffects(viewModel.effects) { if (it is SearchEffect.Navigate) onNavigate(it.nav) }
    SearchContent(state, viewModel::onEvent, onNavigate, viewModel.results, modifier)
}

@Composable
internal fun SearchContent(
    state: SearchUiState, onEvent: (SearchEvent) -> Unit, onNavigate: (Nav) -> Unit,
    resultsFlow: Flow<PagingData<MusicItem>>, modifier: Modifier = Modifier,
) {
    val results = resultsFlow.collectAsLazyPagingItems()
    val snapshot = results.itemSnapshotList
    val loaded = remember(snapshot) { snapshot.items.toList() }
    val grouped = remember(loaded, state.filter) { searchResults(loaded, state.filter) }
    val resultRows = grouped.rows
    val focus = LocalFocusManager.current
    var menu by remember { mutableStateOf<Song?>(null) }
    ScreenScaffold("Buscar", modifier, actions = { AvatarButton(onClick = { onNavigate(Nav.Account) }) }) {
        item(key = "field", contentType = "field") {
            SearchField(state, onEvent) {
                focus.clearFocus()
                onEvent(SearchEvent.Submit(state.query))
            }
        }
        if (state.submitted.isEmpty()) {
            val suggestions = if (state.query.isBlank()) emptyList() else state.suggestions
            items(suggestions.size, key = { "sug-${suggestions[it]}-$it" }, contentType = { "suggestion" }) { i ->
                TermRow(suggestions[i], OndaraIcons.Search, { focus.clearFocus(); onEvent(SearchEvent.Submit(suggestions[i])) })
            }
            if (state.query.isBlank() && state.history.isNotEmpty()) {
                item(key = "history-title", contentType = "heading") {
                    SectionTitle("Recientes", Modifier.padding(top = OndaraTheme.spacing.lg), action = "Borrar",
                        onAction = { onEvent(SearchEvent.ClearHistory) })
                }
                items(state.history.take(5).size, key = { "his-${state.history[it]}-$it" }, contentType = { "history" }) { i ->
                    val term = state.history[i]
                    TermRow(term, OndaraIcons.History, { focus.clearFocus(); onEvent(SearchEvent.Submit(term)) }) {
                        OndaraIconButton(OndaraIcons.Close, "Quitar $term del historial", { onEvent(SearchEvent.RemoveHistory(term)) }, size = 16.dp)
                    }
                }
            }
            if (state.query.isBlank()) {
                if (state.moodsLoading && state.moods.isEmpty()) {
                    items(3, key = { "mood-skeleton-$it" }, contentType = { "mood-skeleton" }) {
                        Row(Modifier.padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md)) {
                            repeat(2) { Skeleton(Modifier.weight(1f).aspectRatio(1.45f), shape = OndaraTheme.shapes.medium) }
                        }
                    }
                }
                if (state.moodsError != null && state.moods.isEmpty()) item(key = "moods-error", contentType = "empty") {
                    EmptyState(OndaraIcons.Search, "No se pudieron cargar las categorías", message = state.moodsError,
                        action = "Reintentar", onAction = { onEvent(SearchEvent.RetryMoods) })
                }
                items(
                    state.moods.withIndex().toList().chunked(2),
                    key = { "moods-${it.first().value.browseId}-${it.first().index}" }, contentType = { "mood-row" },
                ) { row ->
                    Row(Modifier.padding(horizontal = OndaraTheme.spacing.screen, vertical = OndaraTheme.spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md)) {
                        row.forEach { (_, mood) ->
                            MoodCard(mood, state.artwork[mood], Modifier.weight(1f),
                                onLoad = { onEvent(SearchEvent.LoadArtwork(mood)) },
                                onClick = { onNavigate(Nav.Mood(mood.title, mood.browseId, mood.params)) })
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            item(key = "filters", contentType = "filters") {
                LazyRow(
                    Modifier.padding(vertical = OndaraTheme.spacing.md),
                    contentPadding = PaddingValues(horizontal = OndaraTheme.spacing.screen),
                    horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm),
                ) {
                    items(FILTERS, key = { it.first }, contentType = { "filter" }) { (filter, label) ->
                        OndaraChip(label, state.filter == filter, { onEvent(SearchEvent.FilterChanged(filter)) })
                    }
                }
            }
            when (val refresh = results.loadState.refresh) {
                is LoadState.Loading -> items(8, key = { "sk$it" }, contentType = { "skeleton" }) { SongRowSkeleton() }
                is LoadState.Error -> item(key = "error", contentType = "empty") {
                    EmptyState(OndaraIcons.Wave, "No se pudo buscar", message = refresh.error.message, action = "Reintentar", onAction = { results.retry() })
                }
                else -> {
                    if (loaded.isEmpty()) item(key = "none", contentType = "empty") {
                        EmptyState(OndaraIcons.Search, "Sin resultados", message = "Prueba con otras palabras.")
                    }
                    items(resultRows.size, key = { index ->
                        resultRows[index].key
                    }, contentType = { if (resultRows[it].heading != null) "heading" else "result" }) { i ->
                        val row = resultRows[i]
                        if (row.heading != null) SectionTitle(row.heading)
                        else {
                            val item = row.item
                            LaunchedEffect(snapshot, row.index) {
                                // Solo señala precarga: claves y contenido pertenecen al snapshot capturado.
                                val pagingIndex = snapshot.placeholdersBefore + row.index
                                if (results.itemSnapshotList === snapshot && pagingIndex in 0 until results.itemCount) {
                                    results[pagingIndex]
                                }
                            }
                            if (item != null) MusicItemRow(item, {
                                val equivalent = (item as? MusicItem.SongItem)?.song?.id?.let(grouped.equivalents::get)
                                onEvent(SearchEvent.Open(item, equivalent))
                            },
                                onMore = (item as? MusicItem.SongItem)?.let { song -> { menu = song.song } })
                        }
                    }
                    if (results.loadState.append is LoadState.Loading) items(2, key = { "more$it" }, contentType = { "skeleton" }) { SongRowSkeleton() }
                }
            }
        }
    }
    menu?.let { song -> SongMenuSheet(song, { menu = null }, { onNavigate(Nav.Artist(it)) }, { onNavigate(Nav.Album(it)) }) }
}

@Composable
private fun TermRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick = onClick)
            .padding(start = OndaraTheme.spacing.screen, end = OndaraTheme.spacing.xs, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = OndaraTheme.colors.onMuted, size = 18.dp)
        Spacer(Modifier.width(OndaraTheme.spacing.lg))
        Text(text, Modifier.weight(1f).padding(vertical = 10.dp), style = OndaraTheme.type.headline, maxLines = 1)
        trailing()
    }
}

@Composable
private fun MoodCard(mood: MoodCategory, artwork: String?, modifier: Modifier, onLoad: () -> Unit, onClick: () -> Unit) {
    LaunchedEffect(mood) { onLoad() }
    val colors = OndaraTheme.colors
    val background = colors.background.copy(alpha = 0.9f)
    val foreground = colors.onBackground
    Box(modifier.aspectRatio(1.45f).clip(OndaraTheme.shapes.medium).background(background).pressable(onClick = onClick)) {
        if (artwork == null) Skeleton(Modifier.fillMaxSize())
        else Artwork(artwork,
            Modifier.align(Alignment.BottomEnd).fillMaxHeight(0.85f).aspectRatio(1f).graphicsLayer {
                rotationZ = 18f
                translationX = size.width * 0.24f
                translationY = size.height * 0.2f
            }, shape = OndaraTheme.shapes.small)
        // La base sólida mantiene el contraste del rótulo incluso sobre portadas muy claras.
        Text(mood.title, Modifier.align(Alignment.BottomStart).fillMaxWidth().background(background)
            .padding(OndaraTheme.spacing.md), style = OndaraTheme.type.headline.copy(fontWeight = FontWeight.Bold),
            color = foreground, maxLines = 2)
    }
}

@Composable
private fun SearchField(state: SearchUiState, onEvent: (SearchEvent) -> Unit, onSubmit: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().padding(horizontal = OndaraTheme.spacing.screen), shape = OndaraTheme.shapes.control) {
        // El campo hereda el vidrio sin taparlo con su superficie opaca habitual.
        CompositionLocalProvider(LocalOndaraColors provides OndaraTheme.colors.copy(surfaceHigh = Color.Transparent)) {
            OndaraTextField(
                value = state.query,
                onValueChange = { onEvent(SearchEvent.QueryChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Artistas, canciones, letras y más",
                leading = OndaraIcons.Search,
                imeAction = ImeAction.Search,
                onImeAction = onSubmit,
                trailing = {
                    if (state.query.isNotEmpty()) {
                        OndaraIconButton(OndaraIcons.Close, "Borrar búsqueda", { onEvent(SearchEvent.Clear) }, size = 18.dp)
                    }
                },
            )
        }
    }
}
