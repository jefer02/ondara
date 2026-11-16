package app.ondara.core.model

import kotlinx.serialization.Serializable

/**
 * Preferencias del usuario. Se persisten con DataStore tipado codificado en Protocol Buffers
 * (kotlinx.serialization-protobuf), por eso todos los campos tienen valor por defecto.
 */
@Serializable
data class UserSettings(
    val themeId: String = "ondara",
    val streamQuality: AudioQuality = AudioQuality.HIGH,
    val downloadQuality: AudioQuality = AudioQuality.HIGH,
    val crossfadeSeconds: Int = 0,
    val normalizeVolume: Boolean = true,
    val skipSilence: Boolean = false,
    val equalizer: EqualizerSettings = EqualizerSettings(),
    /** Radio infinita: al acabarse la cola se añaden canciones relacionadas. */
    val ondaraFlow: Boolean = true,
    val crashReports: Boolean = false,
    val haptics: Boolean = true,
    /** URL base de un servidor de extensión de audio (vacío = desactivado). */
    val extensionEndpoint: String = "",
    /** Cookie de music.youtube.com si el usuario inició sesión (opcional). */
    val youtubeCookie: String = "",
    val contentLanguage: String = "es",
    val contentCountry: String = "CO",
)
