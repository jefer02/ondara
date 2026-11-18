package app.ondara.source.resolver

/** Una pista de vídeo reproducible (URL directa de googlevideo). */
data class VideoStream(
    val url: String,
    /** Contenedor y códec, p. ej. `video/mp4; codecs="avc1.4d401e"`. */
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    /** `true` si es un stream progresivo (muxed) que trae su propio audio. */
    val hasAudio: Boolean,
)

/**
 * Resultado de resolver el **vídeo** de un `videoId` (layout "Cine").
 *
 * - [videoOnly]: pista adaptativa sin audio y de baja calidad (360p–480p preferido) para el fondo mudo.
 * - [muxed]: stream progresivo con audio, para el modo "vídeo completo".
 * - [audioUrl]: mejor pista de audio suelta; junto con [videoOnly] forma el par vídeo+audio cuando no hay [muxed].
 * - [isArtTrack]: `true` si YouTube solo tiene una imagen fija (canción de YouTube Music sin vídeo).
 */
data class ResolvedVideo(
    val videoId: String,
    val videoOnly: VideoStream?,
    val muxed: VideoStream?,
    val audioUrl: String?,
    val isArtTrack: Boolean,
    /** Duración en segundos, 0 si la estrategia no la conoce. */
    val durationSec: Int,
    /** Epoch en milisegundos a partir del cual las URLs dejan de servir. */
    val expiresAtMs: Long,
    /** Nombre de la estrategia que lo produjo. */
    val source: String,
) {
    /** Hay algo que pintar: una pista de solo vídeo o, en su defecto, la progresiva. */
    val hasVideo: Boolean get() = videoOnly != null || muxed != null

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

/** Un resultado de buscar vídeos musicales (candidato a "vídeo oficial" de una canción). */
data class VideoSearchHit(val videoId: String, val title: String, val author: String, val durationSec: Int)

/** Estrategia que además sabe resolver las pistas de vídeo de un `videoId`. */
interface VideoStreamStrategy {
    val name: String
    suspend fun resolveVideo(videoId: String): Result<ResolvedVideo>
}

/** Estrategia que sabe buscar vídeos musicales por texto. */
interface VideoSearchStrategy {
    val name: String
    suspend fun searchVideos(query: String): Result<List<VideoSearchHit>>
}

/**
 * Caché LRU con TTL para vídeos resueltos; mismo diseño que [ResolvedStreamCache]:
 * `LinkedHashMap(accessOrder = true)`, `get`/`put` **O(1)** amortizado, espacio O(capacity).
 */
class ResolvedVideoCache(
    private val capacity: Int = 32,
    private val safetyMarginMs: Long = 10 * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val map = object : LinkedHashMap<String, ResolvedVideo>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedVideo>?) = size > capacity
    }

    @Synchronized
    fun get(key: String): ResolvedVideo? {
        val hit = map[key] ?: return null
        if (hit.isExpired(now() + safetyMarginMs)) {
            map.remove(key)
            return null
        }
        return hit
    }

    @Synchronized
    fun put(key: String, video: ResolvedVideo) {
        map[key] = video
    }

    @Synchronized
    fun invalidate(key: String) {
        map.remove(key)
    }

    val size: Int @Synchronized get() = map.size
}

/** Candidato de vídeo independiente del motor, para compartir el criterio de selección. */
internal data class VideoCandidate(
    val url: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val hasAudio: Boolean,
) {
    fun toStream() = VideoStream(url, mimeType, width, height, bitrate, hasAudio)
}

/** Criterio de selección de pistas de vídeo y detección de "art tracks". Todo puro. */
internal object VideoSelection {
    const val PREFERRED_MIN_HEIGHT = 360
    const val PREFERRED_MAX_HEIGHT = 480
    const val MAX_HEIGHT = 720

    /**
     * Preferencia de códec: H.264 (decodificación por hardware en todos los dispositivos) → VP9 →
     * el resto (AV1 suele decodificarse por software y gasta batería).
     */
    fun codecRank(mimeType: String): Int {
        val mime = mimeType.lowercase()
        return when {
            "avc" in mime || "h264" in mime -> 0
            "vp9" in mime || "vp09" in mime -> 1
            "av01" in mime || "av1" in mime -> 3
            "mp4" in mime -> 0
            "webm" in mime -> 1
            else -> 2
        }
    }

    /** Distancia en líneas al rango preferido 360p–480p (0 si está dentro). */
    fun distanceToPreferred(height: Int): Int = when {
        height < PREFERRED_MIN_HEIGHT -> PREFERRED_MIN_HEIGHT - height
        height > PREFERRED_MAX_HEIGHT -> height - PREFERRED_MAX_HEIGHT
        else -> 0
    }

    /**
     * Pista para el fondo: la de **menor calidad útil**. Dentro de 360p–480p gana la más baja; si no hay
     * ninguna en el rango, la más cercana a él sin pasar de 720p. A igualdad: mejor códec y menor bitrate.
     * Por encima de 720p no se elige nada (no compensa el gasto de datos para un fondo). O(f) con f formatos.
     */
    fun pickBackground(candidates: List<VideoCandidate>): VideoCandidate? = candidates
        .filter { it.height in 1..MAX_HEIGHT }
        .minWithOrNull(
            compareBy<VideoCandidate> { distanceToPreferred(it.height) }
                .thenBy { it.height }
                .thenBy { codecRank(it.mimeType) }
                .thenBy { it.bitrate },
        )

    /**
     * Stream progresivo (con audio) para el modo "vídeo completo": el de mayor resolución hasta 720p;
     * si todos la superan, el más pequeño. O(f).
     */
    fun pickMuxed(candidates: List<VideoCandidate>): VideoCandidate? {
        val known = candidates.filter { it.height > 0 }
        if (known.isEmpty()) return candidates.firstOrNull()
        return known.filter { it.height <= MAX_HEIGHT }.maxWithOrNull(compareBy<VideoCandidate> { it.height }.thenBy { -codecRank(it.mimeType) })
            ?: known.minByOrNull { it.height }
    }

    /**
     * ¿Es una "art track" (imagen fija)? InnerTube lo dice con `musicVideoType`
     * (`MUSIC_VIDEO_TYPE_ATV` = art track; `OMV`/`UGC` = vídeo). Sin ese dato se usa la heurística de
     * los canales autogenerados: autor "Artista - Topic" o descripción "Provided to YouTube by …".
     */
    fun isArtTrack(musicVideoType: String?, author: String?, description: String?): Boolean = when {
        musicVideoType == "MUSIC_VIDEO_TYPE_ATV" -> true
        musicVideoType != null && musicVideoType.startsWith("MUSIC_VIDEO_TYPE_") -> false
        author.orEmpty().trim().endsWith("- Topic", ignoreCase = true) -> true
        else -> description.orEmpty().trimStart().startsWith("Provided to YouTube by", ignoreCase = true)
    }

    /** La caducidad conjunta es la de la URL que antes caduca. O(u) con u URLs. */
    fun expiresAtMs(urls: List<String>, nowMs: Long = System.currentTimeMillis()): Long =
        urls.minOfOrNull { StreamUrls.expiresAtMs(it, nowMs) } ?: nowMs

    /** Lee "360p", "480p60"… cuando el motor no da la altura en un campo propio. */
    fun heightOf(resolution: String?): Int = resolution?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

    fun build(
        videoId: String,
        source: String,
        videoOnly: List<VideoCandidate>,
        muxed: List<VideoCandidate>,
        audioUrl: String?,
        isArtTrack: Boolean,
        durationSec: Int,
    ): ResolvedVideo {
        val background = pickBackground(videoOnly)
        val progressive = pickMuxed(muxed)
        check(background != null || progressive != null) { "$source: sin pistas de vídeo con URL directa" }
        return ResolvedVideo(
            videoId = videoId,
            videoOnly = background?.toStream(),
            muxed = progressive?.toStream(),
            audioUrl = audioUrl,
            isArtTrack = isArtTrack,
            durationSec = durationSec,
            expiresAtMs = expiresAtMs(listOfNotNull(background?.url, progressive?.url, audioUrl)),
            source = source,
        )
    }
}
