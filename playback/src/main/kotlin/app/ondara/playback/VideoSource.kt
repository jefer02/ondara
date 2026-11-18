// Adaptado de Echo Music (https://github.com/EchoMusicApp/Echo-Music), GPL-3.0.
package app.ondara.playback

import app.ondara.core.domain.LyricsRepository
import app.ondara.core.model.Canvas
import app.ondara.core.model.CanvasSource
import app.ondara.core.model.Song
import app.ondara.source.canvas.*
import app.ondara.source.resolver.StreamResolver
import app.ondara.source.resolver.VideoSearchHit
import java.text.Normalizer
import kotlin.math.abs

/** Respaldo independiente del audio; busca el vídeo oficial antes de probar el ID de la canción. */
class YouTubeCanvasProvider(
    private val resolver: StreamResolver,
    private val lyrics: LyricsRepository,
    private val allowed: () -> Boolean,
) : CanvasProvider {
    /** O(r log r) candidatos; cálculo del clip O(n) líneas. */
    override suspend fun resolve(song: Song): CanvasResult? {
        if (!allowed()) return null
        val hits = resolver.searchVideos("${song.title} ${song.artistLine}")
            .filter { canvasArtistMatches(song, it.author) }
            .map { it to VideoSearchScore.score(song, it) }.filter { it.second > 0 }.sortedByDescending { it.second }
        val ids = (hits.map { it.first.videoId } + song.id).distinct()
        for (id in ids) {
            val video = resolver.resolveVideo(id)?.takeIf { it.hasVideo && !it.isArtTrack && it.durationSec > 0 } ?: continue
            if (id == song.id && !canvasArtistMatches(song, video.author.orEmpty())) continue
            if (video.author?.let { canvasArtistMatches(song, it) } == false) continue
            val stream = video.videoOnly ?: continue
            val words = canvasAttempt { lyrics.lyrics(song) }
            val clip = canvasClip(video.durationSec * 1000L, words)
            return CanvasResult(
                Canvas(song.id, stream.url, CanvasSource.YOUTUBE_CLIP, clip.first, clip.second, stream.width, stream.height),
                song.artistLine, video.expiresAtMs,
            )
        }
        return null
    }
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
