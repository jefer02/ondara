package app.ondara.source.lyrics

import app.ondara.core.model.Lyrics
import app.ondara.core.model.LyricsQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Lo que devuelve un proveedor: la letra y los metadatos con los que se puntúa la coincidencia. */
data class LyricsCandidate(val lyrics: Lyrics, val title: String?, val artist: String?, val durationSec: Int?)

/** Interfaz común de proveedores (patrón Adapter: cada API externa se adapta a este contrato). */
interface LyricsProvider {
    val name: String

    /** Candidatos para [query]; lista vacía si no hay nada. Puede lanzar: el agregador lo aísla. */
    suspend fun search(query: LyricsQuery): List<LyricsCandidate>
}

data class ScoredLyrics(val lyrics: Lyrics, val score: Double)

/**
 * Lanza todos los proveedores **en paralelo** y elige la mejor letra por puntuación.
 *
 * Es una carrera con salida anticipada: si llega un candidato con puntuación ≥ [goodEnough] se
 * cancela al resto y se devuelve de inmediato; si no, se espera a todos (cada uno con [timeoutMs])
 * y gana el máximo.
 *
 * Complejidad: tiempo de pared = max(latencias) en vez de la suma; CPU **O(p · m)** con p proveedores
 * y m candidatos por proveedor (cada puntuación es O(|título|²) por Jaro–Winkler, acotado y pequeño).
 */
class LyricsAggregator(
    private val providers: List<LyricsProvider>,
    private val timeoutMs: Long = 7_000,
    private val goodEnough: Double = 0.9,
) {
    suspend fun find(query: LyricsQuery): ScoredLyrics? = coroutineScope {
        val results = Channel<List<LyricsCandidate>>(providers.size.coerceAtLeast(1))
        val jobs = providers.map { provider ->
            launch {
                val found = try {
                    withTimeoutOrNull(timeoutMs) { provider.search(query) }.orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                results.send(found)
            }
        }
        var best: ScoredLyrics? = null
        repeat(jobs.size) {
            for (candidate in results.receive()) {
                val score = LyricsScorer.score(query, candidate) ?: continue
                if (best == null || score > best!!.score) best = ScoredLyrics(candidate.lyrics, score)
            }
            if ((best?.score ?: 0.0) >= goodEnough) {
                coroutineContext.job.cancelChildren()
                return@coroutineScope best
            }
        }
        best
    }
}

/** Puntuación ponderada de un candidato de letra. */
object LyricsScorer {
    private const val W_DURATION = 0.35
    private const val W_SYNC = 0.35
    private const val W_TITLE = 0.20
    private const val W_ARTIST = 0.10
    private const val MIN_TITLE_SIMILARITY = 0.72
    private const val MAX_DURATION_DIFF_SEC = 12.0

    /**
     * Puntuación en [0, 1] o `null` si el candidato claramente es otra canción.
     *
     * `score = 0.35·duración + 0.35·sincronía + 0.20·título + 0.10·artista`
     * - duración: `1 − min(|Δs| / 12, 1)` (0.5 si el proveedor no la informa).
     * - sincronía: palabra a palabra 1.0 · por línea 0.7 · texto plano 0.0.
     * - título/artista: similitud Jaro–Winkler sobre texto normalizado.
     *
     * O(|a|·|b|) por comparación de cadenas; constante en la práctica (títulos cortos).
     */
    fun score(query: LyricsQuery, c: LyricsCandidate): Double? {
        val title = c.title?.let { JaroWinkler.similarity(normalize(query.title), normalize(it)) } ?: 0.85
        if (title < MIN_TITLE_SIMILARITY) return null
        val artist = c.artist?.let { artistSimilarity(query.artist, it) } ?: 0.6
        val duration = if (c.durationSec == null || query.durationSec <= 0) {
            0.5
        } else {
            1.0 - min(abs(c.durationSec - query.durationSec) / MAX_DURATION_DIFF_SEC, 1.0)
        }
        val sync = when {
            c.lyrics.wordSynced -> 1.0
            c.lyrics.synced -> 0.7
            else -> 0.0
        }
        return W_DURATION * duration + W_SYNC * sync + W_TITLE * title + W_ARTIST * artist
    }

    private fun artistSimilarity(wanted: String, got: String): Double {
        val a = normalize(wanted)
        val b = normalize(got)
        // "Daft Punk" vs "Daft Punk/Pharrell Williams": basta con que uno contenga al otro.
        if (a.isNotEmpty() && b.isNotEmpty() && (a in b || b in a)) return 1.0
        return JaroWinkler.similarity(a, b)
    }

    private val NOISE = Regex("""\s*[(\[][^)\]]*(feat|ft\.|remaster|version|versión|live|en vivo|explicit|official|video|audio|lyric)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

    /** Minúsculas, sin paréntesis de ruido ("(Remastered 2011)", "(feat. X)") ni puntuación. */
    fun normalize(s: String): String = NON_ALNUM.replace(NOISE.replace(s, "").lowercase(), " ").trim()
}

/** Similitud Jaro–Winkler en [0, 1]. */
object JaroWinkler {
    private const val PREFIX_SCALE = 0.1
    private const val MAX_PREFIX = 4

    /** Complejidad: **O(|a| · w)** tiempo con ventana w = max(|a|,|b|)/2 − 1 (≤ O(|a|·|b|)), O(|a| + |b|) espacio. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val window = max(0, max(a.length, b.length) / 2 - 1)
        val aMatched = BooleanArray(a.length)
        val bMatched = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val from = max(0, i - window)
            val to = min(b.length - 1, i + window)
            for (j in from..to) {
                if (!bMatched[j] && a[i] == b[j]) {
                    aMatched[i] = true
                    bMatched[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatched[i]) continue
            while (!bMatched[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
        var prefix = 0
        while (prefix < min(MAX_PREFIX, min(a.length, b.length)) && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * PREFIX_SCALE * (1 - jaro)
    }
}
