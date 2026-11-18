package app.ondara.feature.lyrics

import android.graphics.Bitmap
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.designsystem.theme.ThemeJson
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** La letra real conserva su caja al ajustar fuente y aplicar elipsis; O(n) caracteres medidos. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class CurrentLyricLineTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun lineaLargaConservaLaAlturaDuranteYDespuesDeLaTransicion() {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(16))
        val spec = ThemeJson.decode(File("../../core/designsystem/src/main/assets/themes/ondara.json").readText())
        var text by mutableStateOf("Luz")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OndaraTheme(spec, reduceMotion = false, animateColors = false) {
                CurrentLyricLineContent(text, Modifier.width(320.dp).testTag("línea"))
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        val node = compose.onNodeWithTag("línea")
        val bounds = node.getBoundsInRoot()
        compose.runOnIdle { text = "Una letra muy larga que necesita reducir su tamaño ".repeat(80) }
        compose.mainClock.advanceTimeBy(32)
        assertEquals(bounds, node.getBoundsInRoot())
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(bounds, node.getBoundsInRoot())
    }
}
