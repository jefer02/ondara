package app.ondara.feature.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.ondara.core.designsystem.theme.PlayerLayout
import org.junit.Assert.assertEquals
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

/** Capturas de los seis layouts en siete temas y contrato de transporte; O(t·l) casos, sin red. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class CinematicPlayerScreenshotTest(private val themeId: String, private val layout: PlayerLayout) {
    @get:Rule val compose = createComposeRule()

    @Before
    fun prepararRuntimeNativo() {
        // Inicializa java.nio en el hilo del test antes de medir texto en segundo plano.
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(16))
    }

    @Test
    fun capturaPorTema() {
        val song = Song("frecuencia", "Otra frecuencia", listOf(ArtistRef("aurora", "Aurora")), durationSec = 214)
        val state = PlayerUiState(
            player = PlayerState(queue = listOf(song), currentIndex = 0, isPlaying = true, durationMs = 214_000,
                context = PlaybackContext("Playlist", "Noches tranquilas")),
            canvas = CanvasState.None(song.id),
            // Sin tramado de vídeo: en Robolectric el RenderEffect AGSL del fondo se extiende a las capas
            // hermanas (texto y controles), algo que no ocurre en un dispositivo. El shader se cubre aparte.
            settings = UserSettings(playerLayout = layout.name, ditherVideo = false),
        )
        val position = kotlinx.coroutines.flow.MutableStateFlow(55_000L)
        val spec = ThemeJson.decode(File("../../core/designsystem/src/main/assets/themes/$themeId.json").readText())
        val events = mutableListOf<PlayerEvent>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OndaraTheme(spec, reduceMotion = false, animateColors = false) {
                // Como en :app: el fondo del tema lo pinta la raíz, no cada pantalla.
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(OndaraTheme.colors.background),
                ) {
                    androidx.compose.animation.SharedTransitionLayout {
                        val shared = this
                        androidx.compose.animation.AnimatedVisibility(visible = true) {
                            FullPlayer(state, events::add, position, {}, {},
                                LyricsSlots(full = { _, _ -> }, currentLine = { modifier ->
                                    androidx.compose.foundation.layout.Box(modifier, contentAlignment = androidx.compose.ui.Alignment.BottomStart) {
                                        app.ondara.core.designsystem.component.Text("Y volvemos a cantar", style = OndaraTheme.type.lyric)
                                    }
                                }, preview = {}), shared, this, androidx.compose.ui.Modifier, active = true)
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onAllNodesWithText("Otra frecuencia").onFirst().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/player_${layout.name.lowercase()}_$themeId.png")
        compose.onNodeWithContentDescription("Pausar").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, events.count { it == PlayerEvent.TogglePlay }) }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun themes(): List<Array<Any>> =
            listOf("ondara", "cupertino", "mono", "vinilo", "neon", "dinamico", "tinta").flatMap { theme ->
                PlayerLayout.entries.map { layout -> arrayOf<Any>(theme, layout) }
            }
    }
}
