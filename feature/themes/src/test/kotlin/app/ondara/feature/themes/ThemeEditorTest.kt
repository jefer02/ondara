package app.ondara.feature.themes

import app.ondara.core.designsystem.theme.BackgroundStyle
import app.ondara.core.designsystem.theme.ColorMath
import app.ondara.core.designsystem.theme.NavStyle
import app.ondara.core.designsystem.theme.PlayerLayout
import app.ondara.core.designsystem.theme.ThemeJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ThemeEditorTest {
    private val bases = File("../../core/designsystem/src/main/assets/themes").listFiles { f -> f.extension == "json" }!!
        .map { ThemeJson.decode(it.readText()) }

    @Test
    fun `any accent hue on any base theme stays WCAG AA`() {
        assertEquals(7, bases.size)
        for (base in bases) {
            for (hue in 0..360 step 15) {
                val theme = buildCustomTheme(base, "Prueba", hue.toFloat(), 18, 20, PlayerLayout.VINYL, NavStyle.SIDE_RAIL, BackgroundStyle.SOLID)
                for (palette in listOfNotNull(theme.colors.dark, theme.colors.light)) {
                    assertEquals(emptyList<String>(), ColorMath.contrastIssues(palette), "base=${base.id} hue=$hue")
                }
            }
        }
    }

    @Test
    fun `editor choices are applied and the result round trips as json`() {
        val theme = buildCustomTheme(bases.first { it.id == "ondara" }, "Mi Tema Ñ!", 300f, 30, 0, PlayerLayout.MINIMAL, NavStyle.BOTTOM_BAR, BackgroundStyle.BLURRED_ART)
        assertEquals("custom-mi-tema", theme.id)
        assertTrue(theme.custom)
        assertEquals(30, theme.shapes.artwork)
        assertEquals(0, theme.materials.blur)
        assertEquals(PlayerLayout.MINIMAL, theme.playerLayout)
        assertEquals(NavStyle.BOTTOM_BAR, theme.navigation)
        assertEquals(BackgroundStyle.BLURRED_ART, theme.background)
        assertEquals(theme, ThemeJson.decode(ThemeJson.encode(theme)))
    }
}
