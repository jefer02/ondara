package app.ondara.source.spotify

import app.ondara.core.model.Song
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Emparejamiento de una canción de Spotify con su equivalente en YouTube Music. Es puro: el llamador
 * busca [query] en YouTube y pasa los resultados a [best].
 */
object TrackMatcher {
    /** Puntuación a partir de la cual el llamador puede aceptar la coincidencia sin preguntar. */
    const val ACCEPT_SCORE = 0.8

    /** Cuántos resultados de la búsqueda se puntúan. */
    const val CANDIDATES = 5

    private const val TITLE_WEIGHT = 0.45
    private const val ARTIST_WEIGHT = 0.30
    private const val DURATION_WEIGHT = 0.25
    private const val DURATION_TOLERANCE_MS = 10_000.0
    private const val WINKLER_PREFIX = 4
    private const val WINKLER_SCALE = 0.1
    private const val WINKLER_THRESHOLD = 0.7

    /** Palabras que delatan una coletilla de edición ("Remastered 2011", "Live at…", "Deluxe Edition"). */
    private const val EDITION = "remaster(?:ed|izado|izada)?|live|en vivo|en directo|deluxe|version|versión|edit|mix|mono|stereo|" +
        "bonus|explicit|clean|anniversary|aniversario|expanded|acoustic|acústico|acustico|official|oficial|video|audio|lyrics?|" +
        "from|single|album|original|demo|instrumental"
    private const val FEATURING = "feat|ft|featuring|with"

    private val brackets = Regex("\\[[^\\]]*]")
    private val featuringGroup = Regex("\\(\\s*(?:$FEATURING)\\b[^)]*\\)", RegexOption.IGNORE_CASE)
    private val editionGroup = Regex("\\([^)]*\\b(?:$EDITION)\\b[^)]*\\)", RegexOption.IGNORE_CASE)
    private val editionSuffix = Regex("\\s[-–—]\\s[^-–—]*\\b(?:$EDITION)\\b.*$", RegexOption.IGNORE_CASE)
    private val featuringSuffix = Regex("\\s(?:feat|ft|featuring)\\b\\.?\\s.*$", RegexOption.IGNORE_CASE)
    private val diacritics = Regex("\\p{Mn}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    data class Match(val song: Song, val score: Double)

    /** Texto de búsqueda: "{título} {artista principal}". */
    fun query(track: SpotifyTrack): String = listOfNotNull(track.title, track.artists.firstOrNull()).joinToString(" ").trim()

    /**
     * Forma canónica para comparar: minúsculas, sin acentos (NFD), sin "(feat. …)", "[…]", "- Remastered 2011",
     * "- Remaster", "- Live", "(Deluxe)" y similares, y con la puntuación convertida en espacios colapsados.
     * Si al quitar las coletillas no queda nada (p. ej. "(Live)"), se conserva el texto completo. O(n).
     */
    fun normalize(text: String): String {
        val stripped = listOf(brackets, featuringGroup, editionGroup, editionSuffix, featuringSuffix)
            .fold(text) { acc, regex -> regex.replace(acc, " ") }
        return fold(stripped).ifEmpty { fold(text) }
    }

    private fun fold(text: String): String =
        separators.replace(diacritics.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(), " ").trim()

    /**
     * Similitud de Jaro–Winkler en 0..1 (1 = iguales): Jaro cuenta coincidencias dentro de una ventana de
     * ⌊max(|a|,|b|)/2⌋−1 y transposiciones; Winkler premia hasta 4 caracteres de prefijo común cuando Jaro > 0,7.
     * O(|a|·|b|) en tiempo y O(|a|+|b|) en espacio.
     */
    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val window = max(max(a.length, b.length) / 2 - 1, 0)
        val matchedA = BooleanArray(a.length)
        val matchedB = BooleanArray(b.length)
        val matches = matchCharacters(a, b, window, matchedA, matchedB)
        if (matches == 0) return 0.0
        var halfTranspositions = 0
        var j = 0
        for (i in a.indices) {
            if (!matchedA[i]) continue
            while (!matchedB[j]) j++
            if (a[i] != b[j]) halfTranspositions++
            j++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - halfTranspositions / 2) / m) / 3
        if (jaro <= WINKLER_THRESHOLD) return jaro
        return jaro + commonPrefix(a, b) * WINKLER_SCALE * (1 - jaro)
    }

    private fun commonPrefix(a: String, b: String): Int {
        var prefix = 0
        while (prefix < min(WINKLER_PREFIX, min(a.length, b.length)) && a[prefix] == b[prefix]) prefix++
        return prefix
    }

    private fun matchCharacters(a: String, b: String, window: Int, matchedA: BooleanArray, matchedB: BooleanArray): Int {
        var matches = 0
        for (i in a.indices) {
            for (j in max(0, i - window)..min(b.length - 1, i + window)) {
                if (!matchedB[j] && a[i] == b[j]) {
                    matchedA[i] = true
                    matchedB[j] = true
                    matches++
                    break
                }
            }
        }
        return matches
    }

    /**
     * score = 0.45·sim(título) + 0.30·sim(artista) + 0.25·(1 − min(|Δduración| / 10 s, 1)); sim = Jaro–Winkler
     * sobre [normalize]. El artista del candidato = mejor sim entre sus artistas y el artista principal del
     * track. O(m²) con m la longitud de las cadenas.
     */
    fun score(track: SpotifyTrack, candidate: Song): Double {
        val title = jaroWinkler(normalize(track.title), normalize(candidate.title))
        val mainArtist = normalize(track.artists.firstOrNull().orEmpty())
        val artist = candidate.artists.maxOfOrNull { jaroWinkler(mainArtist, normalize(it.name)) } ?: 0.0
        val deltaMs = abs(track.durationMs - candidate.durationSec * 1000L)
        val duration = 1 - min(deltaMs / DURATION_TOLERANCE_MS, 1.0)
        return TITLE_WEIGHT * title + ARTIST_WEIGHT * artist + DURATION_WEIGHT * duration
    }

    /**
     * Puntúa los primeros [CANDIDATES] y devuelve el mejor (aunque no llegue a [ACCEPT_SCORE]; el llamador
     * decide), o null si no hay candidatos. A igualdad gana el que YouTube puso antes. O(k·m²) con k
     * candidatos y m la longitud de las cadenas.
     */
    fun best(track: SpotifyTrack, candidates: List<Song>): Match? =
        candidates.take(CANDIDATES).map { Match(it, score(track, it)) }.maxByOrNull { it.score }
}
