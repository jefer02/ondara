package app.ondara.core.designsystem

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.component.GlassIconButton
import app.ondara.core.designsystem.component.ImmersiveHeroScaffold
import app.ondara.core.designsystem.component.ImmersiveSecondaryColor
import app.ondara.core.designsystem.component.OndaraIconButton
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.ColorMath
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.designsystem.theme.ThemeJson
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ImmersiveContrastTest(private val white: Boolean) {
    @get:Rule val compose = createComposeRule()

    @Test fun iconosContrastanConLaPortada() {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(16))
        val theme = ThemeJson.decode(File("src/main/assets/themes/ondara.json").readText())
        val background = if (white) Color.White else Color.Black
        val text = if (white) Color.Black else Color.White
        compose.setContent {
            OndaraTheme(theme, reduceMotion = true) {
                ImmersiveHeroScaffold(
                    "Título del álbum", background, text, {}, hero = { androidx.compose.foundation.layout.Box(it.background(background)) },
                    topActions = {
                        GlassIconButton(OndaraIcons.Share, "Compartir", {})
                        GlassIconButton(OndaraIcons.More, "Opciones", {})
                    },
                    centerActions = {
                        GlassIconButton(OndaraIcons.Info, "Información", {})
                        GlassIconButton(OndaraIcons.Star, "Guardar", {})
                    },
                    meta = "Artista · 2026",
                ) {
                    items(14, key = { it }) { index ->
                        app.ondara.core.designsystem.component.GlassCard(Modifier.fillMaxWidth().height(60.dp)) {
                            OndaraIconButton(OndaraIcons.More, "Menú de canción ${index + 1}", {}, tint = ImmersiveSecondaryColor())
                        }
                    }
                }
            }
        }
        for (label in listOf("Atrás", "Compartir", "Opciones", "Información", "Guardar", "Menú de canción 1")) {
            val image = compose.onNodeWithContentDescription(label).assertIsDisplayed().captureToImage().toPixelMap()
            val pixels = buildList {
                for (y in image.height / 4 until image.height * 3 / 4) {
                    for (x in image.width / 4 until image.width * 3 / 4) add(image[x, y].toArgb())
                }
            }
            val backing = pixels.groupingBy { it }.eachCount().maxBy { it.value }.key
            assertTrue("$label sobre $background", pixels.count { ColorMath.contrast(it, backing) >= 4.5 } > 8)
        }
        val suffix = if (white) "white" else "black"
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/detail_$suffix.png")
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        compose.onAllNodesWithContentDescription("Compartir").assertCountEquals(1)
        compose.onNodeWithContentDescription("Compartir").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/detail_${suffix}_colapsado.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "blanco={0}")
        fun covers() = listOf(true, false)
    }
}
