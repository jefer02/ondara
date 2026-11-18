package app.ondara.core.model

import kotlinx.serialization.Serializable

enum class AudioQuality(val maxBitrate: Int) {
    /** ~50–70 kbps (Opus 50/70, AAC 48). */
    LOW(72_000),

    /** La mejor pista disponible sin iniciar sesión (Opus ~160 kbps / AAC 128). */
    HIGH(Int.MAX_VALUE),
}

/** Resultado de resolver un `videoId` a una URL de audio reproducible. */
data class ResolvedStream(
    val videoId: String,
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    /** Epoch en milisegundos a partir del cual la URL deja de servir. */
    val expiresAtMs: Long,
    /** Loudness relativa que reporta YouTube; `null` si la estrategia no la conoce. */
    val loudnessDb: Float?,
    val contentLength: Long?,
    /** Nombre de la estrategia que la produjo (diagnóstico y reintentos tras 403). */
    val source: String,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

enum class RepeatMode { OFF, ALL, ONE }

/** Origen de la cola en curso: [type] es "Playlist", "Álbum", "Radio", "Artista"… y [title] su nombre. */
data class PlaybackContext(val type: String, val title: String)

@Serializable
enum class CanvasSource { APPLE_MUSIC, SPOTIFY, TIDAL, YOUTUBE_CLIP }

/** Canvas resuelto de una canción: un vídeo corto que se repite en bucle. */
@Serializable
data class Canvas(
    val songId: String,
    val url: String,
    val source: CanvasSource,
    /** Recorte en ms dentro del vídeo; null indica que el vídeo entero ya es corto. */
    val clipStartMs: Long = 0,
    val clipEndMs: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
)

sealed interface CanvasState {
    data object Idle : CanvasState
    data class Loading(val songId: String) : CanvasState
    data class Ready(val canvas: Canvas) : CanvasState

    /** Sin canvas o no permitido: la UI muestra la portada. */
    data class None(val songId: String?) : CanvasState
}

/** Ranura visible y superficies que ya pintaron su primer fotograma. */
data class CanvasSurfaces(val active: Int = 0, val firstFrame: Set<Int> = emptySet(), val width: Int = 0, val height: Int = 0)

/** Estado observable del reproductor, compartido por todas las pantallas. */
data class PlayerState(
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** Fin del temporizador de sueño (epoch ms) o `null`. */
    val sleepTimerEndsAtMs: Long? = null,
    val error: String? = null,
    /** Desde dónde se lanzó la cola ("Reproduciendo desde Playlist · Radio de blue"). */
    val context: PlaybackContext? = null,
) {
    val current: Song? get() = queue.getOrNull(currentIndex)
    val hasNext: Boolean get() = currentIndex in 0 until queue.lastIndex
}

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED }

data class DownloadInfo(val song: Song, val status: DownloadStatus, val progress: Float, val quality: AudioQuality, val bytes: Long)

@Serializable
data class EqualizerSettings(
    val enabled: Boolean = false,
    /** Ganancia por banda en milibelios (mB), en el orden de bandas que reporta el dispositivo. */
    val bandLevelsMb: List<Int> = emptyList(),
)

data class EqualizerBand(val index: Int, val centerHz: Int, val minMb: Int, val maxMb: Int)
