package app.ondara.feature.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull

data class RadioUiState(
    val loading: Boolean = true,
    val starting: Boolean = false,
    val artists: List<Artist> = emptyList(),
    val moods: List<MoodCategory> = emptyList(),
    val history: List<Song> = emptyList(),
    val error: String? = null,
)

sealed interface RadioEvent {
    data object Refresh : RadioEvent
    data object Flow : RadioEvent
    data class StartRadio(val song: Song) : RadioEvent
    data class Navigate(val nav: Nav) : RadioEvent
}

sealed interface RadioEffect {
    data class Navigate(val nav: Nav) : RadioEffect
}

@HiltViewModel
class RadioViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    private val personal: PersonalRepository,
    private val player: PlayerController,
    private val buildFlowQueue: BuildFlowQueueUseCase,
) : MviViewModel<RadioUiState, RadioEvent, RadioEffect>(RadioUiState()) {
    private var loadJob: Job? = null

    init {
        viewModelScope.launch { library.history(6).collect { setState { copy(history = it.distinctBy(Song::id).take(6)) } } }
        load()
    }

    override fun onEvent(event: RadioEvent) {
        when (event) {
            RadioEvent.Refresh -> load()
            RadioEvent.Flow -> startFlow()
            is RadioEvent.StartRadio -> player.startRadio(event.song)
            is RadioEvent.Navigate -> emit(RadioEffect.Navigate(event.nav))
        }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            setState { copy(loading = true, error = null) }
            launch {
                try {
                    val artists = personal.topArtists()
                    setState { copy(artists = artists) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    setState { copy(error = "No se pudieron cargar tus artistas.") }
                }
            }
            catalog.moods().collect { res ->
                when (res) {
                    Resource.Loading -> Unit
                    is Resource.Success -> setState { copy(loading = false, moods = res.data) }
                    is Resource.Error -> setState { copy(loading = false, moods = res.cached ?: moods, error = res.message) }
                }
            }
        }
    }

    private fun startFlow() {
        if (state.value.starting) return
        setState { copy(starting = true, error = null) }
        viewModelScope.launch {
            try {
                val seed = player.state.value.current ?: library.mostPlayed(1).firstOrNull() ?: catalogSeed()
                if (seed == null) {
                    setState { copy(error = "Conéctate para encontrar la primera canción de tu Flow.") }
                } else {
                    val queue = buildFlowQueue(seed, emptyList()).ifEmpty { listOf(seed) }
                    player.play(queue, 0, context = PlaybackContext("Radio", "Ondara Flow"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                setState { copy(error = "No se pudo iniciar Ondara Flow. Inténtalo de nuevo.") }
            } finally {
                setState { copy(starting = false) }
            }
        }
    }

    /** Busca la primera canción del catálogo, incluyendo la caché sin conexión. O(n) sobre sus elementos. */
    private suspend fun catalogSeed(): Song? = catalog.home().map { res ->
        val feed = when (res) {
            is Resource.Success -> res.data
            is Resource.Error -> res.cached
            Resource.Loading -> null
        }
        feed?.shelves?.asSequence()?.flatMap { it.items.asSequence() }
            ?.filterIsInstance<MusicItem.SongItem>()?.firstOrNull()?.song
    }.filterNotNull().firstOrNull()
}

@Composable
fun RadioScreen(onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: RadioViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val send = viewModel::onEvent
    CollectEffects(viewModel.effects) { if (it is RadioEffect.Navigate) onNavigate(it.nav) }
    ScreenScaffold("Radio", modifier, actions = { AvatarButton(onClick = { send(RadioEvent.Navigate(Nav.Account)) }) }) {
        item(key = "flow", contentType = "hero") { FlowCard(state.starting) { send(RadioEvent.Flow) } }
        if (state.loading) items(2, key = { "skeleton-$it" }, contentType = { "skeleton" }) { ShelfSkeleton() }
        if (state.artists.isNotEmpty()) {
            item(key = "artists", contentType = "shelf") {
                val shelf = HomeShelf("Radios por artista", items = state.artists.map { MusicItem.ArtistItem(it) })
                Shelf(shelf, { item ->
                    val artist = (item as MusicItem.ArtistItem).artist
                    send(RadioEvent.Navigate(Nav.Playlist(MixIds.STATION_PREFIX + artist.id)))
                }, Modifier.staggeredEntry(1, enabled = true))
            }
        }
        if (state.moods.isNotEmpty()) {
            item(key = "moods", contentType = "moods") {
                Column(Modifier.padding(bottom = OndaraTheme.spacing.xl).staggeredEntry(2, enabled = true)) {
                    SectionTitle("Por género y estado de ánimo")
                    LazyRow(contentPadding = PaddingValues(horizontal = OndaraTheme.spacing.screen), horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
                        items(state.moods, key = { it.browseId + it.params }, contentType = { "mood" }) { mood ->
                            OndaraChip(mood.title, false, { send(RadioEvent.Navigate(Nav.Mood(mood.title, mood.browseId, mood.params))) }, Modifier.heightIn(min = OndaraTheme.spacing.touch))
                        }
                    }
                }
            }
        }
        if (state.history.isNotEmpty()) {
            item(key = "history-title", contentType = "title") { SectionTitle("Radio de lo último que escuchaste") }
            items(state.history, key = { it.id }, contentType = { "song" }) { song ->
                SongRow(song, { send(RadioEvent.StartRadio(song)) }, Modifier.staggeredEntry(3, enabled = true))
            }
        }
        if (state.error != null) {
            item(key = "error", contentType = "error") {
                EmptyState(OndaraIcons.Wave, "Sin conexión", message = state.error, action = "Reintentar", onAction = { send(RadioEvent.Refresh) })
            }
        }
    }
}

@Composable
private fun FlowCard(starting: Boolean, onPlay: () -> Unit) {
    val colors = OndaraTheme.colors
    val matte = OndaraTheme.materials.blur.value <= 0f
    Box(
        Modifier.padding(horizontal = OndaraTheme.spacing.screen).padding(bottom = OndaraTheme.spacing.xl)
            .fillMaxWidth().height(220.dp).staggeredEntry(0, enabled = true).clip(OndaraTheme.shapes.large)
            .background(if (matte) colors.blockPrimary else colors.background).pressable(enabled = !starting, onClick = onPlay),
    ) {
        if (!matte) MeshGradientBackground(OndaraTheme.artwork, Modifier.matchParentSize())
        val content = if (matte) colors.onBlock else colors.onBackground
        Column(Modifier.fillMaxSize().padding(OndaraTheme.spacing.xl), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Ondara Flow", style = OndaraTheme.type.display, color = content)
                Text("Radio infinita hecha con lo que escuchas", style = OndaraTheme.type.body, color = content)
            }
            if (starting) Skeleton(Modifier.size(OndaraTheme.spacing.touch))
            else PlayPauseButton(false, onPlay, size = OndaraTheme.spacing.touch + OndaraTheme.spacing.xl, playLabel = "Reproducir Ondara Flow")
        }
    }
}
