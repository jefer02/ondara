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

    @Test
    fun `first run defaults to the Cine player layout and keeps an explicit choice`() = runTest {
        // Instalación nueva: archivo vacío → layout "Cine".
        val fresh = UserSettingsSerializer.readFrom(ByteArrayInputStream(ByteArray(0)))
        assertEquals("CINEMATIC", fresh.playerLayout)
        assertEquals(true, fresh.videoWifiOnly)
        assertEquals(false, fresh.onboardingDone)
        // Ajustes escritos por la v1 (sin los campos nuevos): los campos v2 toman su valor por defecto.
        val out = ByteArrayOutputStream()
        UserSettingsSerializer.writeTo(UserSettings(themeId = "mono"), out)
        assertEquals("CINEMATIC", UserSettingsSerializer.readFrom(ByteArrayInputStream(out.toByteArray())).playerLayout)
        // La elección del usuario se conserva tal cual.
        val chosen = ByteArrayOutputStream()
        UserSettingsSerializer.writeTo(UserSettings(playerLayout = "VINYL"), chosen)
        assertEquals("VINYL", UserSettingsSerializer.readFrom(ByteArrayInputStream(chosen.toByteArray())).playerLayout)
        val theme = ByteArrayOutputStream()
        UserSettingsSerializer.writeTo(UserSettings(playerLayout = UserSettings.PLAYER_LAYOUT_THEME), theme)
        assertEquals("THEME", UserSettingsSerializer.readFrom(ByteArrayInputStream(theme.toByteArray())).playerLayout)
    }
}
