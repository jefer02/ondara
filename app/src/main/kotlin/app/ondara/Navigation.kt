package app.ondara

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.ondara.core.designsystem.ShowcaseScreen
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.ui.Nav
import app.ondara.feature.album.AlbumScreen
import app.ondara.feature.artist.ArtistScreen
import app.ondara.feature.home.HomeScreen
import app.ondara.feature.home.MoodScreen
import app.ondara.feature.library.LibraryScreen
import app.ondara.feature.playlist.PlaylistScreen
import app.ondara.feature.search.SearchScreen
import app.ondara.feature.settings.LoginScreen
import app.ondara.feature.settings.SettingsScreen
import app.ondara.feature.themes.ThemeEditorScreen
import app.ondara.feature.themes.ThemesScreen
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute

@Serializable data object SearchRoute

@Serializable data object LibraryRoute

@Serializable data class AlbumRoute(val id: String)

@Serializable data class ArtistRoute(val id: String)

@Serializable data class PlaylistRoute(val id: String)

@Serializable data class MoodRoute(val title: String, val browseId: String, val params: String? = null)

@Serializable data object SettingsRoute

@Serializable data object ThemesRoute

@Serializable data object ThemeEditorRoute

@Serializable data object ShowcaseRoute

@Serializable data object LoginRoute

/**
 * Grafo raíz. `:app` es el único módulo que conoce todos los features: cada pantalla emite [Nav]
 * y aquí se traduce a rutas tipadas.
 */
@Composable
fun OndaraNavHost(navController: NavHostController, onNavigate: (Nav) -> Unit) {
    val motion = OndaraTheme.motion
    val duration = if (motion.reduced) 150 else motion.mediumMs
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        enterTransition = {
            if (motion.reduced) fadeIn(tween(duration)) else fadeIn(tween(duration)) + slideInHorizontally(tween(duration)) { it / 10 }
        },
        exitTransition = { fadeOut(tween(duration / 2)) },
        popEnterTransition = { fadeIn(tween(duration)) },
        popExitTransition = {
            if (motion.reduced) fadeOut(tween(duration)) else fadeOut(tween(duration)) + slideOutHorizontally(tween(duration)) { it / 10 } + scaleOut(tween(duration), 0.97f)
        },
    ) {
        composable<HomeRoute>(enterTransition = { fadeIn(tween(duration)) + scaleIn(tween(duration), 0.98f) }) { HomeScreen(onNavigate) }
        composable<SearchRoute>(enterTransition = { fadeIn(tween(duration)) + scaleIn(tween(duration), 0.98f) }) { SearchScreen(onNavigate) }
        composable<LibraryRoute>(enterTransition = { fadeIn(tween(duration)) + scaleIn(tween(duration), 0.98f) }) { LibraryScreen(onNavigate) }
        composable<AlbumRoute> { AlbumScreen(it.toRoute<AlbumRoute>().id, onNavigate) }
        composable<ArtistRoute> { ArtistScreen(it.toRoute<ArtistRoute>().id, onNavigate) }
        composable<PlaylistRoute> { PlaylistScreen(it.toRoute<PlaylistRoute>().id, onNavigate) }
        composable<MoodRoute> {
            val route = it.toRoute<MoodRoute>()
            MoodScreen(Nav.Mood(route.title, route.browseId, route.params), onNavigate)
        }
        composable<SettingsRoute> { SettingsScreen(onNavigate) }
        composable<ThemesRoute> { ThemesScreen(onNavigate) }
        composable<ThemeEditorRoute> { ThemeEditorScreen(onNavigate) }
        composable<ShowcaseRoute> { ShowcaseScreen(onBack = { onNavigate(Nav.Back) }) }
        composable<LoginRoute> { LoginScreen(onNavigate) }
    }
}
