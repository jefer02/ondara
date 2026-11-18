package app.ondara.feature.lyrics

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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

/** Captura reproducible de Lyrics en los siete temas, sin repositorios ni red. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class LyricsScreenshotTest(private val themeId: String) {
    @get:Rule val compose = createComposeRule()

    @Before
    fun prepararRuntimeNativo() {
        // Inicializa java.nio en el hilo del test antes de medir texto en segundo plano.
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(16))
    }

    @Test
    fun capturaPorTema() {
        val lines = listOf(
            LyricLine(0, 4000, "A new morning begins", translation = "Comienza una nueva mañana"),
            LyricLine(4000, 8000, "We follow the light", translation = "Seguimos la luz"),
            LyricLine(8000, 12000, "The sea is calling", translation = "El mar nos llama"),
            LyricLine(12000, 16000, "And we sing again", translation = "Y volvemos a cantar"),
        )
        val state = LyricsUiState(
            song = Song("luz", "Luz de noviembre", listOf(ArtistRef("aurora", "Aurora"))),
            lyrics = Lyrics(lines, synced = true, provider = "Letra de prueba"), translate = true,
        )
        val spec = ThemeJson.decode(File("../../core/designsystem/src/main/assets/themes/$themeId.json").readText())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OndaraTheme(spec, reduceMotion = false, animateColors = false) {
                // Como en :app: el fondo del tema lo pinta la raíz, no cada pantalla.
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(OndaraTheme.colors.background),
                ) {
                    LyricsContent(state, {}, { 5500L })
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onAllNodesWithContentDescription("Seguimos la luz", substring = true).onFirst().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/lyrics_$themeId.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = listOf("ondara", "cupertino", "mono", "vinilo", "neon", "dinamico", "tinta")
    }
}
