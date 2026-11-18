package app.ondara.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import java.util.Calendar
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val catalogLoading: Boolean = true,
    val personal: PersonalHome = PersonalHome(),
    val shelves: List<HomeShelf> = emptyList(),
    val catalogReleases: List<MusicItem> = emptyList(),
    val moods: List<MoodCategory> = emptyList(),
    val moodArtwork: Map<MoodCategory, String?> = emptyMap(),
    val recent: List<MusicItem> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val connected: Boolean = false,
    val settings: UserSettings? = null,
    val hasHistory: Boolean = false,
    val historyLoaded: Boolean = false,
    val error: String? = null,
) {
    val beginner: Boolean get() = historyLoaded && settings != null && !hasHistory && !connected
}

sealed interface HomeEvent {
    data class LoadMood(val mood: MoodCategory) : HomeEvent
    data object Refresh : HomeEvent
    data class Open(val item: MusicItem, val shelf: HomeShelf) : HomeEvent
    data class Navigate(val nav: Nav) : HomeEvent
    data object DismissBanner : HomeEvent
    data object PlayReplay : HomeEvent
}

sealed interface HomeEffect {
    data class Navigate(val nav: Nav) : HomeEffect
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    private val player: PlayerController,
    private val personal: PersonalRepository,
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
) : MviViewModel<HomeUiState, HomeEvent, HomeEffect>(HomeUiState()) {
    private var loadJob: Job? = null
    private val moodRequests = mutableSetOf<MoodCategory>()
    private val moodSemaphore = kotlinx.coroutines.sync.Semaphore(3)

    init {
        viewModelScope.launch { library.recentItems().collect { setState { copy(recent = it.distinctBy(MusicItem::key)) } } }
        viewModelScope.launch { library.history(1).collect { setState { copy(hasHistory = it.isNotEmpty(), historyLoaded = true) } } }
        viewModelScope.launch { library.playlists().collect { setState { copy(playlists = it.filter { p -> p.source != PlaylistSource.ONDARA }) } } }
        viewModelScope.launch { accounts.accounts.collect { setState { copy(connected = it.anyConnected) } } }
        viewModelScope.launch { settings.settings.collect { setState { copy(settings = it) } } }
        load(false)
    }

    override fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.LoadMood -> viewModelScope.launch {
                if (!moodRequests.add(event.mood)) return@launch
                val url = moodSemaphore.withPermit { catalog.moodArtwork(event.mood) }
                setState { copy(moodArtwork = moodArtwork + (event.mood to url)) }
            }
            HomeEvent.Refresh -> load(true)
            is HomeEvent.Navigate -> emit(HomeEffect.Navigate(event.nav))
            is HomeEvent.Open -> open(event.item, event.shelf)
            HomeEvent.DismissBanner -> viewModelScope.launch { settings.update { it.copy(accountsBannerDismissed = true) } }
            HomeEvent.PlayReplay -> player.play(state.value.personal.replay, 0, context = PlaybackContext("Replay", "Tu Replay ${state.value.personal.replayYear}"))
        }
    }

    /** Filtra el carrusel una vez para reproducir sus canciones desde la elegida. O(n) en tiempo y espacio. */
    private fun open(item: MusicItem, shelf: HomeShelf) {
        item.toNav()?.let { emit(HomeEffect.Navigate(it)); return }
        val songs = shelf.items.mapNotNull { (it as? MusicItem.SongItem)?.song }
        val index = songs.indexOfFirst { it.id == (item as MusicItem.SongItem).song.id }
        if (index >= 0) player.play(songs, index, context = PlaybackContext("Inicio", shelf.title))
    }

    private fun load(force: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            setState { copy(loading = true, catalogLoading = true, error = null) }
            launch {
                personal.home(force).catch { setState { copy(loading = false, error = "No se pudo actualizar tu música.") } }
                    .collect { setState { copy(loading = false, personal = it) } }
            }
            launch {
                catalog.moods().collect { res ->
                    when (res) {
                        is Resource.Success -> setState { copy(moods = res.data) }
                        is Resource.Error -> setState { copy(moods = res.cached ?: moods) }
                        Resource.Loading -> Unit
                    }
                }
            }
            launch {
                catalog.news(force).collect { res ->
                    val shelves = when (res) {
                        is Resource.Success -> res.data
                        is Resource.Error -> res.cached.orEmpty()
                        Resource.Loading -> emptyList()
                    }
                    val albums = shelves.flatMap { it.items }.filterIsInstance<MusicItem.AlbumItem>().distinctBy { it.key }
                    if (albums.isNotEmpty()) setState { copy(catalogReleases = albums) }
                }
            }
            catalog.home(force).collect { res ->
                when (res) {
                    Resource.Loading -> Unit
                    is Resource.Success -> setState { copy(catalogLoading = false, shelves = res.data.shelves.filter { it.items.isNotEmpty() }) }
                    is Resource.Error -> setState {
                        copy(catalogLoading = false, error = res.message, shelves = res.cached?.shelves?.filter { it.items.isNotEmpty() } ?: shelves)
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
fun HomeScreen(
    onNavigate: (Nav) -> Unit, modifier: Modifier = Modifier, viewModel: HomeViewModel = hiltViewModel(), scrollRequest: Int = 0,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEffects(viewModel.effects) { if (it is HomeEffect.Navigate) onNavigate(it.nav) }
    HomeContent(state, viewModel::onEvent, modifier, scrollRequest = scrollRequest)
}

@Composable
internal fun HomeContent(
    state: HomeUiState, onEvent: (HomeEvent) -> Unit, modifier: Modifier = Modifier, greetingText: String = greeting(), scrollRequest: Int = 0,
) {
    val send = onEvent
    val name = state.settings?.profileName.orEmpty().trim()
    val subtitle = greetingText + if (name.isEmpty()) " · Tu música, en otra frecuencia." else ", $name · Tu música, en otra frecuencia."
    val pickWidth = LocalConfiguration.current.screenWidthDp.dp * 0.7f
    val listState = rememberLazyListState()
    var handledScrollRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(scrollRequest) {
        if (scrollRequest != handledScrollRequest) {
            listState.scrollToItem(0)
            handledScrollRequest = scrollRequest
        }
    }
    ScreenScaffold("Inicio", modifier, subtitle = subtitle, listState = listState, actions = { AvatarButton(onClick = { send(HomeEvent.Navigate(Nav.Account)) }) }) {
        if (!state.connected && state.settings?.accountsBannerDismissed == false) {
            item(key = "accounts", contentType = "banner") { AccountsBanner(send) }
        }
        if (state.loading || (state.beginner && state.catalogLoading)) {
            items(3, key = { "skeleton-$it" }, contentType = { "skeleton" }) { ShelfSkeleton() }
        }
        if (state.beginner && state.shelves.isNotEmpty()) {
            item(key = "starter-title", contentType = "title") { SectionTitle("Para empezar") }
            items(state.shelves.size, key = { "starter-$it" }, contentType = { "shelf" }) { i ->
                val shelf = state.shelves[i]
                Shelf(shelf, { send(HomeEvent.Open(it, shelf)) }, Modifier.staggeredEntry(i, enabled = true))
            }
        }
        if (state.personal.topPicks.isNotEmpty()) {
            item(key = "picks", contentType = "carousel") {
                val picks = state.personal.topPicks.distinctBy { it.item.key }
                val shelf = HomeShelf("Top Picks para ti", items = picks.map { it.item })
                Column(Modifier.staggeredEntry(0, enabled = true).padding(bottom = OndaraTheme.spacing.xl)) {
                    SectionTitle(shelf.title)
                    LazyRow(contentPadding = PaddingValues(horizontal = OndaraTheme.spacing.screen), horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md)) {
                        items(picks, key = { it.item.key }, contentType = { "pick" }) { pick ->
                            TopPickCard(pick, { send(HomeEvent.Open(pick.item, shelf)) }, Modifier.width(pickWidth))
                        }
                    }
                }
            }
        }
        if (state.recent.size >= 3) homeShelf("recent", "Escuchado recientemente", state.recent, 1, send, compact = true)
        homeShelf("mixes", "Hecho para ti", state.personal.mixes.map { MusicItem.PlaylistItem(it) }, 2, send)
        if (state.connected) homeShelf("playlists", "Tus playlists de YouTube y Spotify", state.playlists.map { MusicItem.PlaylistItem(it) }, 3, send)
        if (state.moods.isNotEmpty()) {
            item(key = "moods", contentType = "moods") {
                Column(Modifier.staggeredEntry(4, enabled = true).padding(bottom = OndaraTheme.spacing.xl)) {
                    SectionTitle("Estados de ánimo")
                    LazyRow(contentPadding = PaddingValues(horizontal = OndaraTheme.spacing.screen), horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
                        items(state.moods, key = { it.browseId + it.params }, contentType = { "mood" }) { mood ->
                            LaunchedEffect(mood) { send(HomeEvent.LoadMood(mood)) }
                            MoodChip(mood, state.moodArtwork[mood]) { send(HomeEvent.Navigate(Nav.Mood(mood.title, mood.browseId, mood.params))) }
                        }
                    }
                }
            }
        }
        homeShelf("releases", "Lanzamientos recientes", state.personal.releases.ifEmpty { state.catalogReleases }, 5, send)
        homeShelf("stations", "Estaciones para ti", state.personal.stations.map { MusicItem.ArtistItem(it) }, 6, send, stations = true)
        if (state.personal.replay.isNotEmpty()) {
            item(key = "replay", contentType = "replay") { ReplayCard(state.personal, send) }
        }
        if (!state.loading && state.error != null) {
            item(key = "error", contentType = "error") {
                EmptyState(OndaraIcons.Wave, "Sin conexión", message = state.error, action = "Reintentar", onAction = { send(HomeEvent.Refresh) })
            }
        }
    }
}

private fun LazyListScope.homeShelf(
    key: String,
    title: String,
    data: List<MusicItem>,
    index: Int,
    send: (HomeEvent) -> Unit,
    compact: Boolean = false,
    stations: Boolean = false,
) {
    if (data.isEmpty()) return
    item(key = key, contentType = "carousel") {
        val shelf = HomeShelf(title, items = data.distinctBy(MusicItem::key))
        Column(Modifier.staggeredEntry(index, enabled = true).padding(bottom = OndaraTheme.spacing.xl)) {
            SectionTitle(title)
            LazyRow(contentPadding = PaddingValues(horizontal = OndaraTheme.spacing.screen), horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md)) {
                items(shelf.items, key = { it.key }, contentType = { it::class }) { item ->
                    MusicCard(item, {
                        if (stations && item is MusicItem.ArtistItem) send(HomeEvent.Navigate(Nav.Playlist(MixIds.STATION_PREFIX + item.artist.id)))
                        else send(HomeEvent.Open(item, shelf))
                    }, size = if (compact) 132.dp else 148.dp)
                }
            }
        }
    }
}

@Composable
private fun AccountsBanner(send: (HomeEvent) -> Unit) {
    Surface(Modifier.padding(horizontal = OndaraTheme.spacing.screen).padding(bottom = OndaraTheme.spacing.xl).fillMaxWidth().staggeredEntry(0, enabled = true), shape = OndaraTheme.shapes.large) {
        Column(Modifier.padding(OndaraTheme.spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Conecta tus cuentas", Modifier.weight(1f), style = OndaraTheme.type.title)
                OndaraIconButton(OndaraIcons.Close, "Descartar invitación", { send(HomeEvent.DismissBanner) })
            }
            Text("Trae tus playlists y descubre más de tu música.", style = OndaraTheme.type.body, color = OndaraTheme.colors.onMuted)
            Spacer(Modifier.height(OndaraTheme.spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
                OndaraButton("YouTube", { send(HomeEvent.Navigate(Nav.Login)) }, Modifier.weight(1f), icon = OndaraIcons.YouTube)
                OndaraButton("Spotify", { send(HomeEvent.Navigate(Nav.SpotifyConnect)) }, Modifier.weight(1f), style = ButtonStyle.TONAL, icon = OndaraIcons.Spotify)
            }
        }
    }
}

@Composable
private fun ReplayCard(home: PersonalHome, send: (HomeEvent) -> Unit) {
    val colors = OndaraTheme.colors
    Surface(
        Modifier.padding(horizontal = OndaraTheme.spacing.screen).fillMaxWidth().staggeredEntry(7, enabled = true)
            .pressable { send(HomeEvent.Navigate(Nav.Playlist(MixIds.REPLAY))) },
        shape = OndaraTheme.shapes.large, color = colors.blockPrimary, contentColor = colors.onBlock,
    ) {
        Row(Modifier.padding(OndaraTheme.spacing.lg), horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
                Text("Tu Replay ${home.replayYear}", style = OndaraTheme.type.title)
                Text("${home.replay.size} canciones", style = OndaraTheme.type.body)
                OndaraButton("Reproducir", { send(HomeEvent.PlayReplay) }, icon = OndaraIcons.Play)
            }
            Column(Modifier.width(132.dp), verticalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.xxs)) {
                home.replay.take(4).chunked(2).forEach { songs ->
                    Row(horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.xxs)) {
                        songs.forEach { song -> Artwork(song.thumbnailUrl, Modifier.weight(1f).aspectRatio(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodChip(mood: MoodCategory, artwork: String?, onClick: () -> Unit) {
    Surface(Modifier.heightIn(min = OndaraTheme.spacing.touch).pressable(haptic = true, onClick = onClick), shape = OndaraTheme.shapes.control, color = OndaraTheme.colors.surfaceHigh) {
        Row(Modifier.padding(horizontal = OndaraTheme.spacing.md, vertical = OndaraTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Artwork(artwork, Modifier.size(OndaraTheme.spacing.touch), shape = OndaraTheme.shapes.small)
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
        if (state.loading) items(3, key = { "sk$it" }, contentType = { "skeleton" }) { ShelfSkeleton() }
        items(state.shelves.size, key = { "shelf-$it" }, contentType = { "shelf" }) { i ->
            Shelf(state.shelves[i], { item -> item.toNav()?.let(onNavigate) })
        }
        if (!state.loading && state.shelves.isEmpty()) {
            item(key = "empty", contentType = "empty") { EmptyState(OndaraIcons.Wave, "Nada por aquí", message = state.error) }
        }
    }
}
