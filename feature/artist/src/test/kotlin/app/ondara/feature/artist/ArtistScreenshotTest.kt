package app.ondara.feature.artist

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

/** Captura reproducible de Artist en los siete temas, sin repositorios ni red. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ArtistScreenshotTest(private val themeId: String) {
    @get:Rule val compose = createComposeRule()

    @Before
    fun prepararRuntimeNativo() {
        // Inicializa java.nio en el hilo del test antes de medir texto en segundo plano.
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(16))
    }

    @Test
    fun capturaPorTema() {
        val artist = Artist("aurora", "Aurora", subscribers = "24 mil")
        val songs = listOf("Otra frecuencia", "Luz de noviembre", "Mar abierto").mapIndexed { index, title ->
            Song("cancion-$index", title, listOf(ArtistRef(artist.id, artist.name)), durationSec = 214)
        }
        val state = ArtistUiState(loading = false, saved = true, page = ArtistPage(
            artist, description = "Melodías para acompañar tus días.", topSongs = songs,
            shelves = listOf(HomeShelf("Álbumes", items = listOf(MusicItem.AlbumItem(
                Album("mar", "Mar abierto", year = 2026),
            )))),
        ))
        val spec = ThemeJson.decode(File("../../core/designsystem/src/main/assets/themes/$themeId.json").readText())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OndaraTheme(spec, reduceMotion = false, animateColors = false) {
                // Como en :app: el fondo del tema lo pinta la raíz, no cada pantalla.
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(OndaraTheme.colors.background),
                ) {
                    ArtistContent(state, {}, artist.id, {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithContentDescription("Reproducir artista").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/artist_$themeId.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = listOf("ondara", "cupertino", "mono", "vinilo", "neon", "dinamico", "tinta")
    }
}
