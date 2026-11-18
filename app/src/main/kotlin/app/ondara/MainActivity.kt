package app.ondara

import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.TransformOrigin
import app.ondara.core.designsystem.component.BottomFade
import app.ondara.core.designsystem.component.GlassOverlaySheet
import app.ondara.core.designsystem.component.GlassSurface
import app.ondara.core.designsystem.component.LocalChromeState
import app.ondara.core.designsystem.component.NavPillHeight
import app.ondara.core.designsystem.component.NavPillMargin
import app.ondara.core.designsystem.component.Text
import app.ondara.core.designsystem.component.rememberChromeState
import app.ondara.core.domain.AccountRepository
import app.ondara.core.domain.SyncRepository
import app.ondara.core.ui.LocalDitherArtwork
import app.ondara.core.ui.LocalProfileBadge
import app.ondara.core.ui.ProfileBadge
import app.ondara.feature.account.AccountSheetContent
import app.ondara.feature.lyrics.CurrentLyricLine
import app.ondara.feature.lyrics.LyricsPreview
import app.ondara.feature.player.LyricsSlots
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.ondara.core.designsystem.component.LocalBottomInset
import app.ondara.core.designsystem.component.LocalHapticsEnabled
import app.ondara.core.designsystem.component.LocalHazeState
import app.ondara.core.designsystem.component.LocalStartInset
import app.ondara.core.designsystem.component.LocalThemeTransition
import app.ondara.core.designsystem.component.NavItem
import app.ondara.core.designsystem.component.OndaraBackground
import app.ondara.core.designsystem.component.OndaraNavigation
import app.ondara.core.designsystem.component.RailWidth
import app.ondara.core.designsystem.component.ThemeTransitionHost
import app.ondara.core.designsystem.component.rememberThemeTransitionState
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.LocalAudioAmplitude
import app.ondara.core.designsystem.theme.NavStyle
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.designsystem.theme.ThemeSpec
import app.ondara.core.designsystem.theme.ThemeStore
import app.ondara.core.designsystem.theme.rememberReduceMotion
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.ui.Artwork
import app.ondara.core.ui.LocalNowPlaying
import app.ondara.core.ui.Nav
import app.ondara.core.ui.NowPlaying
import app.ondara.core.ui.rememberArtworkPalette
import app.ondara.feature.lyrics.LyricsView
import app.ondara.feature.player.MiniPlayerHeight
import app.ondara.feature.player.PlayerHost
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AppUiState(
    val theme: ThemeSpec,
    val haptics: Boolean = true,
    val nowPlaying: NowPlaying = NowPlaying(),
    val artworkUrl: String? = null,
    val profile: ProfileBadge = ProfileBadge(),
    val ditherArtwork: Boolean = true,
    /** `null` mientras DataStore no ha entregado el primer valor: evita mostrar el onboarding por error. */
    val onboardingDone: Boolean? = null,
)

@HiltViewModel
class AppViewModel @Inject constructor(
    themes: ThemeStore,
    private val settings: SettingsRepository,
    private val player: PlayerController,
    private val accounts: AccountRepository,
    private val sync: SyncRepository,
) : ViewModel() {
    val state: StateFlow<AppUiState> = combine(
        settings.settings,
        themes.themes,
        player.state.map { Triple(it.current?.id, it.isPlaying, it.current?.thumbnailUrl) }.distinctUntilChanged(),
        accounts.accounts,
    ) { prefs, all, (songId, playing, artwork), linked ->
        AppUiState(
            theme = all.firstOrNull { it.id == prefs.themeId } ?: themes.default,
            haptics = prefs.haptics,
            nowPlaying = NowPlaying(songId, playing),
            artworkUrl = artwork,
            profile = ProfileBadge(prefs.profileName, prefs.avatarPath, linked.anyConnected),
            ditherArtwork = prefs.ditherArtwork,
            onboardingDone = prefs.onboardingDone,
        )
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState(themes.default))

    /** Avisos breves de una sola vez (resultado de conectar Spotify). */
    private val _notices = Channel<String>(Channel.BUFFERED)
    val notices = _notices.receiveAsFlow()

    init {
        viewModelScope.launch {
            // La fecha de "Escuchando desde" se fija en el primer arranque.
            if (settings.settings.first().joinedAtMs == 0L) settings.update { it.copy(joinedAtMs = System.currentTimeMillis()) }
        }
        // La re-sincronización periódica (12 h) existe solo mientras haya cuentas conectadas.
        viewModelScope.launch { accounts.accounts.map { it.anyConnected }.distinctUntilChanged().collect { sync.schedulePeriodic() } }
    }

    /** Se lee en la fase de dibujo (tema Neón); no provoca recomposiciones. */
    val amplitude: () -> Float = { player.amplitude.value }

    fun finishOnboarding() {
        viewModelScope.launch { settings.update { it.copy(onboardingDone = true) } }
    }

    /** Redirect de OAuth de Spotify (`ondara://spotify-callback?...`). */
    fun onSpotifyCallback(uri: String) {
        viewModelScope.launch {
            runCatching { accounts.completeSpotifyAuthorization(uri) }
                .onSuccess {
                    _notices.send("Spotify conectado como ${it.name}. Importando tu biblioteca…")
                    sync.syncNow()
                }
                .onFailure { _notices.send(it.message ?: "No se pudo conectar con Spotify") }
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Sin este permiso no se ven los controles de reproducción en la notificación.
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleDeepLink(intent)
        setContent { OndaraRoot(appViewModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "ondara" && data.host == "spotify-callback") appViewModel.onSpotifyCallback(data.toString())
    }
}

private val TABS = listOf(
    NavItem(OndaraIcons.Home, "Inicio") to HomeRoute,
    NavItem(OndaraIcons.News, "Novedades") to NewsRoute,
    NavItem(OndaraIcons.Radio, "Radio") to RadioRoute,
    NavItem(OndaraIcons.Library, "Biblioteca") to LibraryRoute,
)
private val SEARCH_TAB = NavItem(OndaraIcons.Search, "Buscar")

/** Flujos a pantalla completa: sin barra de pestañas ni mini-reproductor. */
private val FULLSCREEN_ROUTES = listOf(OnboardingRoute::class, LoginRoute::class, SpotifyConnectRoute::class)

@Composable
private fun OndaraRoot(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()
    val transition = rememberThemeTransitionState()
    val palette = rememberArtworkPalette(state.artworkUrl)
    val theme = state.theme

    ThemeTransitionHost(transition, reduceMotion, themeKey = { viewModel.state.value.theme.id }) {
        OndaraTheme(theme, artwork = palette, reduceMotion = reduceMotion) {
            val dark = OndaraTheme.colors.isDark
            val view = LocalView.current
            SideEffect {
                (view.context as? ComponentActivity)?.window?.let { window ->
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
            }
            CompositionLocalProvider(
                LocalHapticsEnabled provides state.haptics,
                LocalNowPlaying provides state.nowPlaying,
                LocalAudioAmplitude provides viewModel.amplitude,
                LocalThemeTransition provides transition,
                LocalProfileBadge provides state.profile,
                LocalDitherArtwork provides state.ditherArtwork,
            ) {
                AppScaffold(theme, state, viewModel)
                AppUpdatePrompt()
            }
        }
    }
}

@Composable
private fun AppScaffold(theme: ThemeSpec, state: AppUiState, viewModel: AppViewModel) {
    val navController = rememberNavController()
    val haze = rememberHazeState()
    val chrome = rememberChromeState()
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    var accountOpen by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val rail = theme.navigation == NavStyle.SIDE_RAIL
    val pill = theme.navigation == NavStyle.FLOATING_GLASS_PILL
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val fullscreen = FULLSCREEN_ROUTES.any { destination?.hasRoute(it) == true }
    val selected = when {
        destination?.hasRoute(SearchRoute::class) == true -> TABS.size
        else -> TABS.indexOfFirst { (_, route) -> destination?.hasRoute(route::class) == true }
    }
    val collapsed = pill && chrome.collapsed
    val navHeight = when {
        fullscreen -> 0.dp
        pill -> NavPillHeight + NavPillMargin
        theme.navigation == NavStyle.BOTTOM_BAR -> 58.dp
        else -> 0.dp
    }
    val hasPlayer = state.nowPlaying.songId != null && !fullscreen
    // Lo que las listas deben dejar libre abajo: barra + mini-reproductor + márgenes.
    val bottomInset = systemBottom + navHeight + if (hasPlayer) MiniPlayerHeight + 12.dp else 0.dp

    fun selectTab(route: Any) {
        chrome.expand()
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val onNavigate: (Nav) -> Unit = { nav ->
        when (nav) {
            Nav.Account -> accountOpen = true
            Nav.Search -> selectTab(SearchRoute)
            Nav.Radio -> selectTab(RadioRoute)
            else -> navController.navigateTo(nav)
        }
    }
    // Cada cambio de pantalla empieza con la barra expandida.
    LaunchedEffect(destination) { chrome.expand() }
    LaunchedEffect(state.onboardingDone) {
        if (state.onboardingDone == false && navController.currentDestination?.hasRoute(OnboardingRoute::class) != true) {
            navController.navigate(OnboardingRoute) { launchSingleTop = true }
        }
    }
    LaunchedEffect(Unit) { viewModel.notices.collect { notice = it } }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }
    BackHandler(enabled = accountOpen) { accountOpen = false }

    Box(Modifier.fillMaxSize()) {
        // Todo lo que queda detrás del vidrio (fondo + pantallas + velo inferior) es la fuente del desenfoque.
        OndaraBackground(
            Modifier.hazeSource(haze),
            blurredArtwork = {
                if (state.artworkUrl != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Artwork(
                        state.artworkUrl,
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = 1.3f
                            scaleY = 1.3f
                        }.blur(80.dp),
                        shape = RectangleShape,
                        sizePx = 240,
                        playing = true,
                    )
                }
            },
        ) {
            CompositionLocalProvider(
                LocalBottomInset provides bottomInset,
                LocalStartInset provides if (rail) RailWidth + 1.dp else 0.dp,
                LocalChromeState provides chrome,
                // Dentro de la fuente de la app no puede haber efectos de su haze: cada pantalla usa el suyo.
                LocalHazeState provides null,
            ) {
                OndaraNavHost(
                    navController,
                    onNavigate,
                    onOnboardingFinished = {
                        viewModel.finishOnboarding()
                        navController.popBackStack(HomeRoute, inclusive = false)
                    },
                )
            }
            // El contenido se funde con el fondo detrás de la barra y del mini-reproductor.
            if (!fullscreen && !rail) BottomFade(bottomInset + 28.dp, Modifier.align(Alignment.BottomCenter))
        }

        CompositionLocalProvider(LocalHazeState provides haze, LocalStartInset provides if (rail) RailWidth + 1.dp else 0.dp) {
            if (!fullscreen && (!playerExpanded || rail)) {
                OndaraNavigation(
                    items = remember { TABS.map { it.first } },
                    selected = selected,
                    onSelect = { index -> selectTab(if (index == TABS.size) SearchRoute else TABS[index].second) },
                    modifier = Modifier.align(if (rail) Alignment.CenterStart else Alignment.BottomCenter),
                    search = SEARCH_TAB,
                    collapsed = collapsed,
                    onExpand = { chrome.expand() },
                )
            }
            if (!fullscreen) {
                // Con la barra encogida el mini-reproductor baja y se coloca entre la pestaña activa y Buscar.
                val motion = OndaraTheme.motion
                val side by animateDpAsState(if (collapsed) NavPillMargin + NavPillHeight + 8.dp else NavPillMargin, motion.spring(), label = "miniSide")
                val bottom by animateDpAsState(
                    systemBottom + when {
                        collapsed -> NavPillMargin + (NavPillHeight - MiniPlayerHeight) / 2
                        else -> navHeight + 8.dp
                    },
                    motion.spring(),
                    label = "miniBottom",
                )
                PlayerHost(
                    expanded = playerExpanded,
                    onExpandedChange = { playerExpanded = it },
                    // El riel lateral (tema Mono) ocupa el borde izquierdo: el mini-reproductor empieza después.
                    miniPadding = PaddingValues(start = side + if (rail) RailWidth + 1.dp else 0.dp, end = side, bottom = bottom),
                    onNavigate = onNavigate,
                    lyrics = remember {
                        LyricsSlots(
                            full = { modifier -> LyricsView(modifier) },
                            currentLine = { modifier -> CurrentLyricLine(modifier) },
                            preview = { modifier -> LyricsPreview(modifier) },
                        )
                    },
                )
            }
            // Hoja de Cuenta: vidrio real (hermana de la fuente), crece desde la esquina del avatar.
            GlassOverlaySheet(accountOpen, { accountOpen = false }, origin = TransformOrigin(0.92f, 0f)) {
                AccountSheetContent(onNavigate = onNavigate, onDismiss = { accountOpen = false })
            }
            notice?.let { text ->
                GlassSurface(
                    Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(OndaraTheme.spacing.md),
                    shape = OndaraTheme.shapes.medium,
                ) { Text(text, Modifier.padding(OndaraTheme.spacing.md), style = OndaraTheme.type.caption, maxLines = 3) }
            }
        }
    }
}

private const val NOTICE_MS = 4_000L

internal fun NavHostController.navigateTo(nav: Nav) {
    when (nav) {
        Nav.Back -> popBackStack()
        is Nav.Album -> navigate(AlbumRoute(nav.id))
        is Nav.Artist -> navigate(ArtistRoute(nav.id))
        is Nav.Playlist -> navigate(PlaylistRoute(nav.id))
        is Nav.Mood -> navigate(MoodRoute(nav.title, nav.browseId, nav.params))
        Nav.ThemeEditor -> navigate(ThemeEditorRoute)
        Nav.Showcase -> navigate(ShowcaseRoute)
        Nav.Downloads -> navigate(LibraryCategoryRoute("downloads"))
        is Nav.LibraryCategory -> navigate(LibraryCategoryRoute(nav.category))
        // Los gestiona AppScaffold (hoja de Cuenta y pestañas); aquí no hay nada que hacer.
        Nav.Account, Nav.Search, Nav.Radio -> Unit
        else -> singleTopRoute(nav)?.let { route -> navigate(route) { launchSingleTop = true } }
    }
}

/** Pantallas que no deben apilarse dos veces si se abren repetidamente. */
private fun singleTopRoute(nav: Nav): Any? = when (nav) {
    Nav.Settings -> SettingsRoute
    Nav.Themes -> ThemesRoute
    Nav.Login -> LoginRoute
    Nav.Profile -> ProfileRoute
    Nav.SpotifyConnect -> SpotifyConnectRoute
    Nav.Unmatched -> UnmatchedRoute
    Nav.About -> AboutRoute
    Nav.PlayerSettings -> PlayerSettingsRoute
    else -> null
}
