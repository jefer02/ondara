package app.ondara.feature.search

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

/** Captura reproducible de Search en los siete temas, sin repositorios ni red. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class SearchScreenshotTest(private val themeId: String) {
    @get:Rule val compose = createComposeRule()

    @Before
    fun prepararRuntimeNativo() {
        // Inicializa java.nio en el hilo del test antes de medir texto en segundo plano.
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(16))
    }

    @Test
    fun capturaPorTema() {
        val state = SearchUiState(moodsLoading = false, moods = listOf(
            MoodCategory("Pop", "pop", null, 0xFFE04165),
            MoodCategory("Rock", "rock", null, 0xFF7453C4),
            MoodCategory("Para concentrarte", "concentracion", null, 0xFF007E91),
            MoodCategory("Jazz", "jazz", null, 0xFFAD6415),
            MoodCategory("Indie", "indie", null, 0xFF3853B0),
            MoodCategory("Para relajarte", "calma", null, 0xFF36813D),
        ))
        val results = kotlinx.coroutines.flow.flowOf(androidx.paging.PagingData.empty<MusicItem>())
        val spec = ThemeJson.decode(File("../../core/designsystem/src/main/assets/themes/$themeId.json").readText())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OndaraTheme(spec, reduceMotion = false, animateColors = false) {
                // Como en :app: el fondo del tema lo pinta la raíz, no cada pantalla.
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(OndaraTheme.colors.background),
                ) {
                    SearchContent(state, {}, {}, results)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onAllNodesWithText("Pop").onFirst().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/search_$themeId.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = listOf("ondara", "cupertino", "mono", "vinilo", "neon", "dinamico", "tinta")
    }
}
