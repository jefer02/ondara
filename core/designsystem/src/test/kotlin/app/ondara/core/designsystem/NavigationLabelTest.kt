package app.ondara.core.designsystem

import android.graphics.Bitmap
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.ondara.core.designsystem.component.NavItem
import app.ondara.core.designsystem.component.OndaraNavigation
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.designsystem.theme.ThemeJson
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** La navegación completa conserva todas sus etiquetas a escala 130 %, en los temas con barra. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class NavigationLabelTest(private val themeId: String) {
    @get:Rule val compose = createComposeRule()

    @Before
    fun prepararRuntimeNativo() {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(16))
    }

    @Test
    fun etiquetasSinDesbordamiento() {
        val spec = ThemeJson.decode(File("src/main/assets/themes/$themeId.json").readText())
        val labels = listOf("Inicio", "Novedades", "Radio", "Biblioteca")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                OndaraTheme(spec, reduceMotion = true, animateColors = false) {
                    OndaraNavigation(
                        labels.map { NavItem(OndaraIcons.Wave, it) }, 0, {}, Modifier.width(360.dp),
                        search = NavItem(OndaraIcons.Search, "Buscar"),
                    )
                }
            }
        }
        labels.forEach { label ->
            val results = mutableListOf<TextLayoutResult>()
            val text = if (spec.typography.uppercaseLabels) label.uppercase() else label
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue(results.isNotEmpty())
            results.forEach { assertFalse(it.hasVisualOverflow) }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = listOf("ondara", "cupertino", "vinilo", "neon", "dinamico", "tinta")
    }
}
