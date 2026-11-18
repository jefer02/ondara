package app.ondara.feature.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.designsystem.theme.ThemeJson
import app.ondara.core.model.*
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.nio.ByteBuffer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captura reproducible de Library en los siete temas, sin repositorios ni red. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class LibraryScreenshotTest(private val themeId: String) {
    @get:Rule val compose = createComposeRule()

    @Before
    fun prepararRuntimeNativo() {
        // Inicializa java.nio en el hilo del test antes de medir texto en segundo plano.
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(16))
    }

    @Test
    fun capturaPorTema() {
        val album = MusicItem.AlbumItem(Album("mar", "Mar abierto", listOf(ArtistRef("aurora", "Aurora")), year = 2026))
        val playlist = Playlist("noches", "Noches tranquilas", songCount = 18, source = PlaylistSource.SPOTIFY)
        val state = LibraryUiState(
            pins = listOf(album, MusicItem.PlaylistItem(playlist)), playlists = listOf(playlist),
            saved = listOf(SavedItem(album, 1_700_000_000_000L)),
        )
        val spec = ThemeJson.decode(File("../../core/designsystem/src/main/assets/themes/$themeId.json").readText())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OndaraTheme(spec, reduceMotion = false, animateColors = false) {
                // Como en :app: el fondo del tema lo pinta la raíz, no cada pantalla.
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(OndaraTheme.colors.background),
                ) {
                    LibraryContent(state, {}, {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onAllNodesWithText("Fijados").onFirst().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/library_$themeId.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = listOf("ondara", "cupertino", "mono", "vinilo", "neon", "dinamico", "tinta")
    }
}
