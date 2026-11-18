package app.ondara.feature.player

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.designsystem.theme.PlayerLayout
import app.ondara.core.designsystem.theme.ThemeJson
import app.ondara.core.model.ArtistRef
import app.ondara.core.model.PlayerState
import app.ondara.core.model.Song
import app.ondara.core.model.UserSettings
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Invariantes de geometría, gesto y ciclo de vida del canvas; O(l) para l cambios de layout. */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class PlayerLayoutStabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectorViniloEmiteLaPreferenciaExplicita() {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(16))
        val spec = ThemeJson.decode(File("../../core/designsystem/src/main/assets/themes/ondara.json").readText())
        val events = mutableListOf<PlayerSettingsEvent>()
        compose.setContent {
            OndaraTheme(spec, reduceMotion = true, animateColors = false) {
                PlayerSettingsContent(PlayerSettingsUiState(), events::add, {}, flowOf(0L))
            }
        }
        compose.onNodeWithTag("selector-layout").performScrollToNode(hasText("Vinilo"))
        compose.onNodeWithText("Vinilo").performClick()
        compose.runOnIdle { assertEquals(listOf(PlayerSettingsEvent.Layout("VINYL")), events) }
    }

    @Test
    fun controlesEstablesYCanvasSeDetieneAlCambiarDeLayout() {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(16))
        val short = Song("cancion", "Título", listOf(ArtistRef("artista", "Artista")))
        var song by mutableStateOf(short)
        var line by mutableStateOf("Una línea")
        var layout by mutableStateOf(PlayerLayout.CINEMATIC)
        val events = mutableListOf<PlayerEvent>()
        val spec = ThemeJson.decode(File("../../core/designsystem/src/main/assets/themes/cupertino.json").readText())
        val position = flowOf(0L)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OndaraTheme(spec, reduceMotion = false, animateColors = false) {
                SharedTransitionLayout {
                    val shared = this
                    AnimatedVisibility(visible = true) {
                        FullPlayer(
                            PlayerUiState(
                                player = PlayerState(queue = listOf(song), currentIndex = 0),
                                settings = UserSettings(playerLayout = layout.name, ditherVideo = false),
                            ),
                            events::add, position, {}, {},
                            LyricsSlots(
                                full = { modifier, close ->
                                    app.ondara.core.designsystem.component.OndaraIconButton(
                                        app.ondara.core.designsystem.icon.OndaraIcons.ChevronDown, "Cerrar letra", close, modifier,
                                    )
                                },
                                currentLine = { BasicText(line, it.fillMaxSize()) }, preview = {},
                            ), shared, this, Modifier, active = true,
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        val play = compose.onNodeWithContentDescription("Reproducir")
        val originalY = play.getBoundsInRoot().top
        for (longTitle in listOf(false, true)) {
            for (longLine in listOf(false, true)) {
                compose.runOnIdle {
                    song = short.copy(title = if (longTitle) "Título muy largo ".repeat(40) else short.title)
                    line = if (longLine) "Una línea extremadamente larga ".repeat(80) else "Una línea"
                }
                compose.mainClock.advanceTimeBy(600)
                assertEquals(originalY, play.getBoundsInRoot().top)
            }
        }
        compose.onNodeWithText("Vista previa de la letra").assertIsNotDisplayed()
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Ver letra completa").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Cerrar letra").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(events.contains(PlayerEvent.CanvasActive(true))) }
        for (chosen in PlayerLayout.entries.filter { it != PlayerLayout.CINEMATIC }) {
            compose.runOnIdle { layout = chosen }
            compose.mainClock.advanceTimeBy(1_500)
            play.assertIsDisplayed().performClick()
            compose.runOnIdle {
                assertEquals(PlayerEvent.CanvasActive(false), events.filterIsInstance<PlayerEvent.CanvasActive>().last())
                assertTrue(events.contains(PlayerEvent.TogglePlay))
                assertEquals(1, events.count { it == PlayerEvent.CanvasActive(true) })
            }
        }
    }
}
