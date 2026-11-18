package app.ondara.feature.player

import app.ondara.core.designsystem.theme.PlayerLayout
import app.ondara.core.designsystem.theme.ColorMath
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayerGeometryTest {
    @Test fun `letra blanca sobre portada blanca con scrim alcanza AA`() {
        val white = 0xFFFFFFFF.toInt()
        val effectiveBackground = ColorMath.mix(white, 0xFF000000.toInt(), CINEMATIC_LYRIC_SCRIM)
        assertTrue(ColorMath.contrast(white, effectiveBackground) >= ColorMath.AA_TEXT)
    }

    @Test fun `Cine es independiente del tema`() {
        PlayerLayout.entries.forEach { assertEquals(PlayerLayout.CINEMATIC, resolvePlayerLayout(app.ondara.core.model.UserSettings().playerLayout, it)) }
    }

    @Test fun `tema y valores desconocidos conservan el tema`() {
        PlayerLayout.entries.forEach {
            assertEquals(it, resolvePlayerLayout("THEME", it))
            assertEquals(it, resolvePlayerLayout("futuro", it))
        }
    }

    @Test fun `eleccion explicita se respeta`() {
        PlayerLayout.entries.forEach { chosen -> PlayerLayout.entries.forEach { theme -> assertEquals(chosen, resolvePlayerLayout(chosen.name, theme)) } }
    }

    @Test fun `crop conserva proporcion en ambas orientaciones`() {
        assertEquals(4f to 1f, centerCropScale(200, 100, 100, 200))
        assertEquals(1f to 4f, centerCropScale(100, 200, 200, 100))
        assertEquals(1f to 1f, centerCropScale(0, 0, 100, 200))
        assertEquals(1f to 1f, centerCropScale(100, 100, 100, 100))
    }

    @Test fun `doble toque salta diez segundos segun la mitad`() {
        assertEquals(-10_000L, doubleTapDelta(49f, 100f))
        assertEquals(10_000L, doubleTapDelta(50f, 100f))
    }
}
