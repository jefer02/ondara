package app.ondara.core.datastore

import app.ondara.core.model.AudioQuality
import app.ondara.core.model.EqualizerSettings
import app.ondara.core.model.UserSettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SerializerTest {
    @Test
    fun `settings survive a protobuf round trip`() = runTest {
        val settings = UserSettings(
            themeId = "vinilo", streamQuality = AudioQuality.LOW, crossfadeSeconds = 6,
            equalizer = EqualizerSettings(true, listOf(300, -150, 0, 0, 600)), youtubeCookie = "SAPISID=x",
        )
        val out = ByteArrayOutputStream()
        UserSettingsSerializer.writeTo(settings, out)
        assertEquals(settings, UserSettingsSerializer.readFrom(ByteArrayInputStream(out.toByteArray())))
        // Un archivo vacío (primera ejecución) equivale a los valores por defecto.
        assertEquals(UserSettings(), UserSettingsSerializer.readFrom(ByteArrayInputStream(ByteArray(0))))
    }
}
