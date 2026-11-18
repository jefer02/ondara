package app.ondara.core.domain

import app.ondara.core.model.MusicItem
import app.ondara.core.model.MusicVideoType
import app.ondara.core.model.Song
import java.text.Normalizer
import java.util.Locale

/** Tipo de vídeo con una posible versión de audio. O(1). */
fun Song.isMusicVideo(): Boolean = videoType == MusicVideoType.OFFICIAL_VIDEO || videoType == MusicVideoType.USER_VIDEO

private val FEATURED = Regex("""\b(?:ft|feat)\.?\s.*$""")
private val MARKS = Regex("\\p{M}+")
private val SPACES = Regex("\\s+")

/** Normaliza texto en O(l) tiempo y espacio respecto a su longitud. */
private fun normalized(text: String): String = SPACES.replace(
    MARKS.replace(Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD), ""), " ",
).trim()

/** Elimina paréntesis y corchetes decorativos en un recorrido O(l), incluso con delimitadores incompletos. */
private fun withoutDecoration(text: String): String = buildString {
    var depth = 0
    for (character in text) {
        when (character) {
            '(', '[' -> depth++
            ')', ']' -> if (depth > 0) depth--
            else -> if (depth == 0) append(character)
        }
    }
}

/** Firma de título y artista principal; O(l) tiempo y espacio. No empareja metadatos vacíos. */
private fun Song.signature(): Pair<String, String>? {
    val titleKey = normalized(FEATURED.replace(withoutDecoration(title.lowercase(Locale.ROOT)), ""))
    val artistKey = normalized(artists.firstOrNull()?.name.orEmpty())
    return if (titleKey.isEmpty() || artistKey.isEmpty()) null else titleKey to artistKey
}

/**
 * Índice de canciones por título, artista y segundo de duración. Construcción O(n + l), espacio O(n + l),
 * con l caracteres totales. Cada consulta mira como máximo 19 segundos: O(l) sin recorrer candidatos.
 * Conserva la primera coincidencia por duración para que ids repetidos no degraden el coste a O(n²).
 */
class SongEquivalenceIndex(items: List<MusicItem>) {
    private data class Candidates(val first: Song, val durations: MutableMap<Int, Song> = mutableMapOf())
    private val songs = mutableMapOf<Pair<String, String>, Candidates>()

    init {
        for (item in items) {
            val song = (item as? MusicItem.SongItem)?.song ?: continue
            if (song.videoType != MusicVideoType.SONG) continue
            val signature = song.signature() ?: continue
            songs.getOrPut(signature) { Candidates(song) }.durations.putIfAbsent(song.durationSec, song)
        }
    }

    /** Devuelve una canción equivalente a un vídeo. O(l) tiempo, O(l) espacio para la firma. */
    fun equivalent(video: Song): Song? {
        if (!video.isMusicVideo()) return null
        val candidates = video.signature()?.let(songs::get) ?: return null
        if (video.durationSec == 0) return candidates.first
        candidates.durations[0]?.let { return it }
        return (-9..9).firstNotNullOfOrNull { offset ->
            val second = video.durationSec.toLong() + offset
            if (second in 1..Int.MAX_VALUE.toLong()) candidates.durations[second.toInt()] else null
        }
    }
}
