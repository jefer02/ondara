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
    // --- v2: los campos nuevos se añaden SIEMPRE al final (el número de campo protobuf es posicional). ---
    val profileName: String = "",
    /** Ruta absoluta de la foto de perfil copiada a `files/`, o vacío para el avatar de iniciales. */
    val avatarPath: String = "",
    /** Primer arranque (epoch ms); el perfil lo muestra como "Escuchando desde". */
    val joinedAtMs: Long = 0,
    val onboardingDone: Boolean = false,
    val accountsBannerDismissed: Boolean = false,
    /** Client ID de la app que el usuario creó en Spotify for Developers (modo oficial, PKCE). */
    val spotifyClientId: String = "",
    /** Modo experimental de Spotify (cookie `sp_dc`); desactivado por defecto. */
    val spotifyExperimental: Boolean = false,
    /** Dar "Me gusta" en Ondara lo marca también en YouTube Music. */
    val syncLikes: Boolean = false,
    val translateLyrics: Boolean = true,
    /** Portadas tramadas a dos tonos en los temas que lo soportan (Tinta). */
    val ditherArtwork: Boolean = true,
    val lastSpotifySyncMs: Long = 0,
    /**
     * Layout del reproductor elegido por el usuario (nombre de `PlayerLayout`), o [PLAYER_LAYOUT_THEME]
     * para usar el del tema. En una instalación nueva es "Cine".
     */
    val playerLayout: String = "CINEMATIC",
    /** Vídeo de fondo en el layout Cine. */
    val videoBackground: Boolean = true,
    val videoWifiOnly: Boolean = true,
    /** Tema Tinta: tramado de dos tonos también sobre el vídeo. */
    val ditherVideo: Boolean = true,
) {
    companion object {
        const val PLAYER_LAYOUT_THEME = "THEME"
    }
}
