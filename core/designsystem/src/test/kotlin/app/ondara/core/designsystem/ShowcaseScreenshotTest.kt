package app.ondara.core.designsystem

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.designsystem.theme.ThemeJson
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Un screenshot del Showcase por tema (Roborazzi). Salida: `build/outputs/roborazzi/showcase_<id>.png`. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1400dp-xhdpi")
class ShowcaseScreenshotTest(private val themeId: String) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun showcase() {
        val spec = ThemeJson.decode(File("src/main/assets/themes/$themeId.json").readText())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OndaraTheme(spec, reduceMotion = false, animateColors = false) { ShowcaseScreen() }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/showcase_$themeId.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = listOf("ondara", "cupertino", "mono", "vinilo", "neon", "dinamico")
    }
}
