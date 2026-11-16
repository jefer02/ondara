package app.ondara.feature.lyrics

import app.ondara.core.model.LyricLine
import app.ondara.core.model.LyricWord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SungCharsTest {
    private val line = LyricLine(
        1000, 4000, "Up all night",
        listOf(LyricWord(1000, 1400, "Up "), LyricWord(1400, 2000, "all "), LyricWord(3000, 4000, "night")),
    )

    @Test
    fun `progress advances word by word and interpolates inside a word`() {
        assertEquals(0f, sungChars(line, 500))
        assertEquals(1.5f, sungChars(line, 1200), 1e-4f)
        assertEquals(3f, sungChars(line, 1400), 1e-4f)
        assertEquals(5f, sungChars(line, 1700), 1e-4f)
        // Silencio entre palabras: el barrido se detiene al final de la anterior.
        assertEquals(7f, sungChars(line, 2500), 1e-4f)
        assertEquals(9.5f, sungChars(line, 3500), 1e-4f)
        assertEquals(12f, sungChars(line, 9000), 1e-4f)
    }

    @Test
    fun `line-synced lyrics light up the whole line`() {
        assertEquals(Float.MAX_VALUE, sungChars(LyricLine(0, 1000, "hola"), 10))
    }
}
