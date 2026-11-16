package app.ondara.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ondara.core.designsystem.component.EmptyState
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.component.ScreenScaffold
import app.ondara.core.designsystem.component.SectionTitle
import app.ondara.core.designsystem.component.Surface
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.pressable
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.domain.CatalogRepository
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.Resource
import app.ondara.core.model.HomeShelf
import app.ondara.core.model.MoodCategory
import app.ondara.core.model.MusicItem
import app.ondara.core.model.Song
import app.ondara.core.ui.CollectEffects
import app.ondara.core.ui.MviViewModel
import app.ondara.core.ui.Nav
import app.ondara.core.ui.Shelf
import app.ondara.core.ui.ShelfSkeleton
import app.ondara.core.ui.SongMenuSheet
import app.ondara.core.ui.SongRow
import app.ondara.core.ui.toNav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val shelves: List<HomeShelf> = emptyList(),
    val moods: List<MoodCategory> = emptyList(),
    val recent: List<Song> = emptyList(),
    val error: String? = null,
)

sealed interface HomeEvent {
    data object Refresh : HomeEvent
    data class Open(val item: MusicItem, val shelf: HomeShelf?) : HomeEvent
    data class PlayRecent(val index: Int) : HomeEvent
    data class OpenMood(val mood: MoodCategory) : HomeEvent
    data object Flow : HomeEvent
}

sealed interface HomeEffect {
    data class Navigate(val nav: Nav) : HomeEffect
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    private val player: PlayerController,
) : MviViewModel<HomeUiState, HomeEvent, HomeEffect>(HomeUiState()) {
    private var loadJob: Job? = null

    init {
        load(false)
        viewModelScope.launch { library.history(12).collect { setState { copy(recent = it) } } }
    }

    override fun onEvent(event: HomeEvent) {
        when (event) {
            HomeEvent.Refresh -> load(true)
            is HomeEvent.Open -> open(event.item, event.shelf)
            is HomeEvent.PlayRecent -> player.play(state.value.recent, event.index)
            is HomeEvent.OpenMood -> emit(HomeEffect.Navigate(Nav.Mood(event.mood.title, event.mood.browseId, event.mood.params)))
            HomeEvent.Flow -> state.value.recent.firstOrNull()?.let(player::startRadio)
        }
    }

    private fun open(item: MusicItem, shelf: HomeShelf?) {
        val nav = item.toNav()
        if (nav != null) return emit(HomeEffect.Navigate(nav))
        val song = (item as MusicItem.SongItem).song
        // Una estantería de canciones se reproduce entera desde la elegida; Ondara Flow la continúa.
        val songs = shelf?.items?.mapNotNull { (it as? MusicItem.SongItem)?.song }.orEmpty()
        val index = songs.indexOfFirst { it.id == song.id }
        if (index >= 0) player.play(songs, index) else player.startRadio(song)
    }

    private fun load(force: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            catalog.home(force).collect { res ->
                when (res) {
                    Resource.Loading -> setState { copy(loading = shelves.isEmpty(), error = null) }
                    is Resource.Success -> setState { copy(loading = false, shelves = res.data.shelves, moods = res.data.moods, error = null) }
                    is Resource.Error -> setState {
                        val cached = res.cached
                        copy(loading = false, error = res.message, shelves = cached?.shelves ?: shelves, moods = cached?.moods ?: moods)
                    }
                }
            }
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Buenos días"
    in 12..19 -> "Buenas tardes"
    else -> "Buenas noches"
}

@Composable
fun HomeScreen(onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<Song?>(null) }
    CollectEffects(viewModel.effects) { if (it is HomeEffect.Navigate) onNavigate(it.nav) }
    ScreenScaffold(
        title = remember { greeting() },
        subtitle = "Tu música, en otra frecuencia.",
        modifier = modifier,
        actions = {
            OndaraIconButton(OndaraIcons.Palette, "Temas", { onNavigate(Nav.Themes) })
            OndaraIconButton(OndaraIcons.Settings, "Ajustes", { onNavigate(Nav.Settings) })
        },
    ) {
        if (state.moods.isNotEmpty()) {
            item(key = "moods", contentType = "moods") {
                LazyRow(
                    Modifier.padding(bottom = OndaraTheme.spacing.xl),
                    contentPadding = PaddingValues(horizontal = OndaraTheme.spacing.screen),
                    horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm),
                ) {
                    items(state.moods, key = { it.title }) { mood -> MoodChip(mood) { viewModel.onEvent(HomeEvent.OpenMood(mood)) } }
                }
            }
        }
        if (state.recent.isNotEmpty()) {
            item(key = "recent-title") { SectionTitle("Volver a escuchar", action = "Ondara Flow", onAction = { viewModel.onEvent(HomeEvent.Flow) }) }
            items(state.recent.size.coerceAtMost(4), key = { "recent-" + state.recent[it].id }, contentType = { "song" }) { i ->
                val song = state.recent[i]
                SongRow(song, { viewModel.onEvent(HomeEvent.PlayRecent(i)) }, entryIndex = i, onMore = { menu = song })
            }
            item(key = "recent-space") { Spacer(Modifier.height(OndaraTheme.spacing.xl)) }
        }
        if (state.loading) {
            items(3, key = { "sk$it" }, contentType = { "skeleton" }) { ShelfSkeleton() }
        }
        items(state.shelves.size, key = { "shelf-$it-" + state.shelves[it].title }, contentType = { "shelf" }) { i ->
            val shelf = state.shelves[i]
            Shelf(shelf, { viewModel.onEvent(HomeEvent.Open(it, shelf)) })
        }
        if (!state.loading && state.shelves.isEmpty()) {
            item(key = "empty") {
                EmptyState(OndaraIcons.Wave, "Sin conexión", message = state.error ?: "No se pudo cargar el inicio.", action = "Reintentar", onAction = { viewModel.onEvent(HomeEvent.Refresh) })
            }
        }
    }
    menu?.let { song -> SongMenuSheet(song, { menu = null }, { onNavigate(Nav.Artist(it)) }, { onNavigate(Nav.Album(it)) }) }
}

@Composable
private fun MoodChip(mood: MoodCategory, onClick: () -> Unit) {
    Surface(Modifier.pressable(haptic = true, onClick = onClick), shape = OndaraTheme.shapes.control, color = OndaraTheme.colors.surfaceHigh) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(mood.color.toInt()).copy(alpha = 1f)))
            Spacer(Modifier.width(OndaraTheme.spacing.sm))
            Text(mood.title, style = OndaraTheme.type.label, maxLines = 1)
        }
    }
}

data class MoodUiState(val loading: Boolean = true, val shelves: List<HomeShelf> = emptyList(), val error: String? = null)

@HiltViewModel
class MoodViewModel @Inject constructor(private val catalog: CatalogRepository) : MviViewModel<MoodUiState, Nav.Mood, Nothing>(MoodUiState()) {
    private var loaded: Nav.Mood? = null

    /** El único evento es "cargar esta categoría". */
    override fun onEvent(event: Nav.Mood) {
        if (loaded == event) return
        loaded = event
        viewModelScope.launch {
            catalog.moodShelves(event.browseId, event.params).collect { res ->
                when (res) {
                    Resource.Loading -> setState { copy(loading = true) }
                    is Resource.Success -> setState { copy(loading = false, shelves = res.data, error = null) }
                    is Resource.Error -> setState { copy(loading = false, error = res.message) }
                }
            }
        }
    }
}

@Composable
fun MoodScreen(mood: Nav.Mood, onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: MoodViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(mood) { viewModel.onEvent(mood) }
    ScreenScaffold(mood.title, modifier, onBack = { onNavigate(Nav.Back) }) {
        if (state.loading) items(3, key = { "sk$it" }) { ShelfSkeleton() }
        items(state.shelves.size, key = { "shelf-$it" }, contentType = { "shelf" }) { i ->
            Shelf(state.shelves[i], { item -> item.toNav()?.let(onNavigate) })
        }
        if (!state.loading && state.shelves.isEmpty()) {
            item { EmptyState(OndaraIcons.Wave, "Nada por aquí", message = state.error) }
        }
    }
}
