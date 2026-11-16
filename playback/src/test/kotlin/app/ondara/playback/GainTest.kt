package app.ondara.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GainTest {
    @Test
    fun `normalization attenuates loud tracks and never boosts`() {
        assertEquals(1f, Gain.normalization(null))
        assertEquals(1f, Gain.normalization(-4f))
        assertEquals(0.5012f, Gain.normalization(6f), 0.001f)
        assertEquals(0.25f, Gain.normalization(40f))
    }

    @Test
    fun `crossfade is equal power`() {
        for (i in 0..20) {
            val t = i / 20f
            val power = Gain.fadeIn(t) * Gain.fadeIn(t) + Gain.fadeOut(t) * Gain.fadeOut(t)
            assertEquals(1f, power, 1e-5f)
        }
        assertEquals(0f, Gain.fadeIn(0f), 1e-6f)
        assertEquals(1f, Gain.fadeIn(1f), 1e-6f)
    }
}
