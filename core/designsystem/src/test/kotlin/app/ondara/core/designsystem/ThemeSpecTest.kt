package app.ondara.core.designsystem

import androidx.compose.ui.graphics.toArgb
import app.ondara.core.designsystem.icon.HandDrawnIcons
import app.ondara.core.designsystem.icon.OndaraIcons
import app.ondara.core.designsystem.theme.FontToken
import app.ondara.core.designsystem.theme.IconStyle
import app.ondara.core.designsystem.theme.toColors
import app.ondara.core.designsystem.theme.ColorMath
import app.ondara.core.designsystem.theme.NavStyle
import app.ondara.core.designsystem.theme.PlayerLayout
import app.ondara.core.designsystem.theme.ThemeJson
import app.ondara.core.designsystem.theme.ThemeSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

class ThemeSpecTest {
    private val themes: List<ThemeSpec> =
        File("src/main/assets/themes").listFiles { f -> f.extension == "json" }!!.map { ThemeJson.decode(it.readText()) }

    @Test
    fun `seven built-in themes load from json`() {
        assertEquals(setOf("ondara", "cupertino", "mono", "vinilo", "neon", "dinamico", "tinta"), themes.map { it.id }.toSet())
    }

    @Test
    fun `tinta is matte, bento, hand drawn and dithered`() {
        val tinta = themes.first { it.id == "tinta" }
        assertEquals(0, tinta.materials.blur)
        assertEquals(PlayerLayout.BENTO, tinta.playerLayout)
        assertEquals(IconStyle.HAND_DRAWN, tinta.icons)
        assertTrue(tinta.ditherArtwork)
        assertEquals(FontToken.ROUNDED, tinta.typography.display)
        assertEquals("#06080F", tinta.colors.dark.background)
        assertTrue(tinta.shapes.medium in 24..28 && tinta.shapes.large in 24..28)
    }

    @Test
    fun `bento blocks keep AA text contrast in every theme`() {
        for (theme in themes) {
            for (palette in listOfNotNull(theme.colors.dark, theme.colors.light)) {
                val c = palette.toColors()
                fun ratio(fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) =
                    ColorMath.contrast(fg.toArgb(), bg.toArgb())
                assertTrue(ratio(c.onBlock, c.blockPrimary) >= 4.5, "onBlock/blockPrimary en ${theme.id}")
                assertTrue(ratio(c.onBlock, c.blockLight) >= 4.5, "onBlock/blockLight en ${theme.id}")
                assertTrue(ratio(c.onBlockMuted, c.blockMuted) >= 4.5, "onBlockMuted/blockMuted en ${theme.id}")
            }
        }
    }

    @Test
    fun `hand drawn set replaces navigation and playback icons by name`() {
        for (icon in listOf(OndaraIcons.Home, OndaraIcons.News, OndaraIcons.Radio, OndaraIcons.Library, OndaraIcons.Search, OndaraIcons.Play, OndaraIcons.Pause, OndaraIcons.Next, OndaraIcons.Previous)) {
            assertTrue(HandDrawnIcons.replacement(icon) !== icon, "falta versión a mano de ${icon.name}")
        }
        assertTrue(HandDrawnIcons.replacement(OndaraIcons.Equalizer) === OndaraIcons.Equalizer)
    }

    @Test
    fun `every palette of every theme meets WCAG AA`() {
        for (theme in themes) {
            for (palette in listOfNotNull(theme.colors.dark, theme.colors.light)) {
                assertEquals(emptyList<String>(), ColorMath.contrastIssues(palette), "tema ${theme.id}")
            }
        }
    }

    @Test
    fun `themes differ in more than color`() {
        assertTrue(themes.map { it.navigation }.toSet().containsAll(NavStyle.entries))
        // CINEMATIC no pertenece a ningún tema: es la elección por defecto del usuario.
        assertTrue(themes.map { it.playerLayout }.toSet().containsAll(PlayerLayout.entries - PlayerLayout.CINEMATIC))
        assertTrue(themes.map { it.typography.display }.distinct().size >= 4)
        assertTrue(themes.map { it.shapes }.distinct().size == themes.size)
        assertTrue(themes.map { it.motion }.distinct().size >= 5)
    }

    @Test
    fun `json round trip is lossless`() {
        for (theme in themes) assertEquals(theme, ThemeJson.decode(ThemeJson.encode(theme)))
    }

    @Test
    fun `dynamic palettes are AA for arbitrary artwork colors`() {
        val random = Random(1)
        repeat(2000) {
            val dominant = random.nextInt() or (0xFF shl 24)
            val vibrant = random.nextInt() or (0xFF shl 24)
            for (dark in listOf(true, false)) {
                val issues = ColorMath.contrastIssues(ColorMath.paletteFrom(dominant, vibrant, dark))
                assertEquals(emptyList<String>(), issues, "dominant=${ColorMath.toHex(dominant)} vibrant=${ColorMath.toHex(vibrant)} dark=$dark")
            }
        }
    }

    @Test
    fun `contrast math matches WCAG reference values`() {
        assertEquals(21.0, ColorMath.contrast(ColorMath.parseHex("#000"), ColorMath.parseHex("#FFFFFF")), 0.001)
        assertEquals(4.54, ColorMath.contrast(ColorMath.parseHex("#767676"), ColorMath.parseHex("#FFFFFF")), 0.01)
        val fixed = ColorMath.ensureContrast(ColorMath.parseHex("#777777"), ColorMath.parseHex("#808080"))
        assertTrue(ColorMath.contrast(fixed, ColorMath.parseHex("#808080")) >= 4.5)
    }
}
