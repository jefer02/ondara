package app.ondara

import android.Manifest
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
import androidx.hilt.navigation.compose.hiltViewModel
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

data class AppUiState(val theme: ThemeSpec, val haptics: Boolean = true, val nowPlaying: NowPlaying = NowPlaying(), val artworkUrl: String? = null)

@HiltViewModel
class AppViewModel @Inject constructor(
    themes: ThemeStore,
    settings: SettingsRepository,
    private val player: PlayerController,
) : ViewModel() {
    val state: StateFlow<AppUiState> = combine(
        settings.settings.map { it.themeId to it.haptics }.distinctUntilChanged(),
        themes.themes,
        player.state.map { Triple(it.current?.id, it.isPlaying, it.current?.thumbnailUrl) }.distinctUntilChanged(),
    ) { (themeId, haptics), all, (songId, playing, artwork) ->
        AppUiState(all.firstOrNull { it.id == themeId } ?: themes.default, haptics, NowPlaying(songId, playing), artwork)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState(themes.default))

    /** Se lee en la fase de dibujo (tema Neón); no provoca recomposiciones. */
    val amplitude: () -> Float = { player.amplitude.value }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Sin este permiso no se ven los controles de reproducción en la notificación.
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { OndaraRoot() }
    }
}

private val TABS = listOf(
    NavItem(OndaraIcons.Home, "Inicio") to HomeRoute,
    NavItem(OndaraIcons.Search, "Buscar") to SearchRoute,
    NavItem(OndaraIcons.Library, "Biblioteca") to LibraryRoute,
)

@Composable
private fun OndaraRoot(viewModel: AppViewModel = hiltViewModel()) {
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
            ) {
                AppScaffold(theme, state)
            }
        }
    }
}

@Composable
private fun AppScaffold(theme: ThemeSpec, state: AppUiState) {
    val navController = rememberNavController()
    val haze = rememberHazeState()
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val rail = theme.navigation == NavStyle.SIDE_RAIL
    val navHeight = when (theme.navigation) {
        NavStyle.FLOATING_GLASS_PILL -> 72.dp
        NavStyle.BOTTOM_BAR -> 58.dp
        NavStyle.SIDE_RAIL -> 0.dp
    }
    val hasPlayer = state.nowPlaying.songId != null
    val bottomInset = systemBottom + navHeight + if (hasPlayer) MiniPlayerHeight + 12.dp else 0.dp
    val onNavigate: (Nav) -> Unit = { navController.navigateTo(it) }

    Box(Modifier.fillMaxSize()) {
        // Todo lo que queda detrás del vidrio (fondo + pantallas) es la fuente del desenfoque.
        OndaraBackground(
            Modifier.hazeSource(haze),
            blurredArtwork = {
                if (state.artworkUrl != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Artwork(state.artworkUrl, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }.blur(80.dp), shape = RectangleShape, sizePx = 240)
                }
            },
        ) {
            CompositionLocalProvider(
                LocalBottomInset provides bottomInset,
                LocalStartInset provides if (rail) RailWidth + 1.dp else 0.dp,
                // Dentro de la fuente no puede haber efectos de haze: las barras internas usan tinte translúcido.
                LocalHazeState provides null,
            ) {
                OndaraNavHost(navController, onNavigate)
            }
        }

        CompositionLocalProvider(LocalHazeState provides haze, LocalStartInset provides if (rail) RailWidth + 1.dp else 0.dp) {
            val entry by navController.currentBackStackEntryAsState()
            val selected = TABS.indexOfFirst { (_, route) -> entry?.destination?.hasRoute(route::class) == true }
            if (!playerExpanded || rail) {
                OndaraNavigation(
                    items = remember { TABS.map { it.first } },
                    selected = selected,
                    onSelect = { index ->
                        navController.navigate(TABS[index].second) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.align(if (rail) Alignment.CenterStart else Alignment.BottomCenter),
                )
            }
            PlayerHost(
                expanded = playerExpanded,
                onExpandedChange = { playerExpanded = it },
                miniBottomPadding = systemBottom + navHeight + 8.dp,
                onNavigate = onNavigate,
                lyrics = { modifier -> LyricsView(modifier) },
            )
        }
    }
}

internal fun NavHostController.navigateTo(nav: Nav) {
    when (nav) {
        Nav.Back -> popBackStack()
        is Nav.Album -> navigate(AlbumRoute(nav.id))
        is Nav.Artist -> navigate(ArtistRoute(nav.id))
        is Nav.Playlist -> navigate(PlaylistRoute(nav.id))
        is Nav.Mood -> navigate(MoodRoute(nav.title, nav.browseId, nav.params))
        Nav.Settings -> navigate(SettingsRoute) { launchSingleTop = true }
        Nav.Themes -> navigate(ThemesRoute) { launchSingleTop = true }
        Nav.ThemeEditor -> navigate(ThemeEditorRoute)
        Nav.Showcase -> navigate(ShowcaseRoute)
        Nav.Login -> navigate(LoginRoute)
    }
}
