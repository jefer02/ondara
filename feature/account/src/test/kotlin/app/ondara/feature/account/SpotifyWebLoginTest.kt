package app.ondara.feature.account

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.designsystem.theme.ThemeJson
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class SpotifyWebLoginTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun formularioBloqueadoPermiteReintentarOUsarOtraConexion() {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(16))
        val spec = ThemeJson.decode(File("../../core/designsystem/src/main/assets/themes/ondara.json").readText())
        var alternateSelected = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OndaraTheme(spec, reduceMotion = true, animateColors = false) {
                SpotifyWebLogin(connecting = false, event = {}, onAlternative = { alternateSelected = true })
            }
        }

        compose.onNodeWithText("Abriendo Spotify…").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(15_500)
        compose.onNodeWithText("Spotify tardó demasiado en abrir el formulario.").assertIsDisplayed()
        compose.onNodeWithText("Reintentar").performClick()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Abriendo Spotify…").assertIsDisplayed()
        compose.onNodeWithText("Usar Client ID").performClick()
        assertTrue(alternateSelected)
    }
}
