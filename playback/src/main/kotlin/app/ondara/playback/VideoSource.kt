package app.ondara.playback

import app.ondara.core.model.Song
import app.ondara.source.resolver.ResolvedVideo
import app.ondara.source.resolver.StreamResolver
import app.ondara.source.resolver.VideoSearchHit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer
import kotlin.math.abs

/** Fuente intercambiable: null delega en la siguiente estrategia. */
fun interface VideoSource {
    suspend fun resolve(song: Song): ResolvedVideo?
}

class OfficialVideoSource(private val resolver: StreamResolver) : VideoSource {
    override suspend fun resolve(song: Song): ResolvedVideo? = resolver.resolveVideo(song.id)?.takeIf { it.hasVideo && !it.isArtTrack }
}

class SearchVideoSource(private val resolver: StreamResolver) : VideoSource {
    override suspend fun resolve(song: Song): ResolvedVideo? {
        val candidates = resolver.searchVideos("${song.title} ${song.artistLine}")
            .map { it to VideoSearchScore.score(song, it) }.filter { it.second > 0 }.sortedByDescending { it.second }
        for ((hit, _) in candidates) {
            val video = resolver.resolveVideo(hit.videoId)
            if (video != null && video.hasVideo && !video.isArtTrack) return video
        }
        return null
    }
}

/** Última fuente: la interfaz presenta la portada cuando no hay vídeo. */
class ArtworkVideoSource : VideoSource {
    override suspend fun resolve(song: Song): ResolvedVideo? = null
}

/**
 * Cadena con caché LRU, incluidos resultados negativos (5 minutos). Serializa las consultas para
 * compartir pre-resoluciones. O(k) fuentes por fallo, O(1) por acierto; espacio O(64).
 */
class VideoSourceChain(private val sources: List<VideoSource>, private val now: () -> Long = System::currentTimeMillis) : VideoSource {
    private data class Entry(val video: ResolvedVideo?, val expires: Long)
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 64
    }

    override suspend fun resolve(song: Song): ResolvedVideo? = mutex.withLock {
        cache[song.id]?.takeIf { it.expires > now() }?.let { return@withLock it.video }
        val video = sources.firstNotNullOfOrNull { it.resolve(song) }
        cache[song.id] = Entry(video, video?.expiresAtMs?.minus(60_000) ?: (now() + 300_000))
        video
    }

    suspend fun invalidate(songId: String) = mutex.withLock { cache.remove(songId); Unit }
}

object VideoSearchScore {
    private val marks = Regex("\\p{Mn}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val decoration = setOf("official", "oficial", "video", "music", "hd", "4k")

    private fun words(text: String): Set<String> = separators.split(
        marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(),
    ).filter { it.isNotBlank() && it !in decoration }.toSet()

    /**
     * Cobertura del título (mínimo 80 %) y penalización por palabras ajenas y duración.
     * Rechaza duraciones desconocidas o diferencias >8 s. Las palabras del artista pueden aparecer
     * en el título del vídeo. O(n+m) tiempo y espacio con n/m caracteres de canción/candidato.
     */
    fun score(song: Song, hit: VideoSearchHit): Double {
        val delta = abs(song.durationSec.toLong() - hit.durationSec)
        if (song.durationSec <= 0 || hit.durationSec <= 0 || delta > 8) return 0.0
        val title = words(song.title)
        val candidate = words(hit.title)
        if (title.isEmpty()) return 0.0
        val coverage = title.count { it in candidate }.toDouble() / title.size
        if (coverage < 0.8) return 0.0
        val allowed = title + words(song.artistLine)
        val precision = candidate.count { it in allowed }.toDouble() / candidate.size.coerceAtLeast(1)
        if (precision < 0.6) return 0.0
        return coverage * 0.6 + precision * 0.25 + (1 - delta / 8.0) * 0.15
    }
}
