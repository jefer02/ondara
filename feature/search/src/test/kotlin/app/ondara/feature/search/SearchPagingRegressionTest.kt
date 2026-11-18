package app.ondara.feature.search

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.paging.PagingData
import app.ondara.core.designsystem.theme.OndaraTheme
import app.ondara.core.designsystem.theme.ThemeJson
import app.ondara.core.model.MusicItem
import app.ondara.core.model.SearchFilter
import app.ondara.core.model.Song
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class SearchPagingRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun prepararRuntimeNativo() {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(16))
    }

    @Test
    fun cambiarFiltroConIdsRepetidosYMenosResultadosNoLanza() {
        val initial = List<MusicItem>(30) { MusicItem.SongItem(Song("repetido", "Anterior $it")) }
        val flow = MutableStateFlow(PagingData.from(initial))
        val state = mutableStateOf(SearchUiState(query = "blue yung kai", submitted = "blue yung kai", moodsLoading = false))
        val spec = ThemeJson.decode(File("../../core/designsystem/src/main/assets/themes/ondara.json").readText())
        compose.setContent {
            OndaraTheme(spec, reduceMotion = true, animateColors = false) {
                SearchContent(state.value, {}, {}, flow)
            }
        }
        compose.onAllNodesWithText("Anterior 0").onFirst().assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(filter = SearchFilter.SONGS)
            flow.value = PagingData.from(List<MusicItem>(2) { MusicItem.SongItem(Song("repetido", "Nuevo $it")) })
        }
        compose.onAllNodesWithText("Nuevo 0").onFirst().assertIsDisplayed()
        for (filter in SearchFilter.entries) {
            compose.runOnIdle {
                state.value = state.value.copy(filter = filter)
                flow.value = PagingData.from(emptyList())
            }
            compose.waitForIdle()
            compose.runOnIdle {
                flow.value = PagingData.from(List<MusicItem>(2) { MusicItem.SongItem(Song("repetido", "Nuevo $it")) })
            }
            compose.onAllNodesWithText("Nuevo 0").onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun sugerenciasEHistorialRepetidosNoDuplicanClaves() {
        val state = mutableStateOf(SearchUiState(query = "blue", suggestions = listOf("blue", "blue"), moodsLoading = false))
        val flow = MutableStateFlow(PagingData.empty<MusicItem>())
        val spec = ThemeJson.decode(File("../../core/designsystem/src/main/assets/themes/ondara.json").readText())
        compose.setContent {
            OndaraTheme(spec, reduceMotion = true, animateColors = false) { SearchContent(state.value, {}, {}, flow) }
        }
        compose.waitForIdle()
        compose.runOnIdle { state.value = SearchUiState(history = listOf("blue", "blue"), moodsLoading = false) }
        compose.waitForIdle()
    }
}
