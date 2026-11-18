// Adaptado de Echo Music (https://github.com/EchoMusicApp/Echo-Music), GPL-3.0.
package app.ondara.source.canvas

import app.ondara.core.model.Canvas
import app.ondara.core.model.CanvasState
import app.ondara.core.model.CanvasSource
import app.ondara.core.model.Lyrics
import app.ondara.core.model.Song
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Resultado con identidad comprobable y caducidad de la URL. */
@Serializable
data class CanvasResult(val canvas: Canvas, val artist: String, val expiresAtMs: Long)

fun interface CanvasProvider {
    suspend fun resolve(song: Song): CanvasResult?
}

/** Normaliza acentos, puntuación y espacios. O(c) en caracteres. */
fun normalizeCanvasText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/** Comparación estricta por artista; evita coincidencias parciales como «Air» y «Air Supply». O(c). */
fun canvasArtistMatches(song: Song, artist: String): Boolean {
    val returned = normalizeCanvasText(artist.removeSuffix("VEVO").removeSuffix(" - Topic"))
    return returned.isNotEmpty() && (normalizeCanvasText(song.artistLine) == returned ||
        song.artists.any { normalizeCanvasText(it.name) == returned })
}

/** Los errores de un proveedor delegan; la cancelación del consumidor nunca se absorbe. */
suspend fun <T> canvasAttempt(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

/**
 * Cadena ordenada y LRU de 64 entradas. O(p) proveedores por fallo, O(1) por acierto en memoria.
 * La persistencia opcional escribe O(64) entradas; la exclusión mutua comparte cargas y precargas.
 * Los negativos duran cinco minutos; las URLs conservan la caducidad del proveedor menos un minuto.
 */
class CanvasChain(
    private val providers: List<CanvasProvider>,
    private val file: File? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class Entry(val result: CanvasResult?, val expires: Long)
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, Entry>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 64
    }
    private var loaded = false

    /** Emisión de estado; O(p) al resolver y O(1) con caché. */
    fun observeCanvas(song: Song, youtubeAllowed: Boolean = true) = flow<CanvasState> {
        emit(CanvasState.Loading(song.id))
        val result = resolve(song, youtubeAllowed)
        emit(result?.let { CanvasState.Ready(it.canvas) } ?: CanvasState.None(song.id))
    }

    suspend fun resolve(song: Song, youtubeAllowed: Boolean = true): CanvasResult? = mutex.withLock {
        if (!loaded) {
            loaded = true
            canvasAttempt { file?.takeIf { it.exists() }?.readText()?.let { Json.decodeFromString<Map<String, Entry>>(it) } }
                ?.entries?.toList()?.takeLast(64)?.forEach { cache[it.key] = it.value }
        }
        // Separar políticas impide que un negativo móvil suprima el respaldo al recuperar Wi-Fi.
        val key = "${song.id}|$youtubeAllowed"
        cache[key]?.takeIf { it.expires > now() }?.let { return@withLock it.result }
        var result: CanvasResult? = null
        for (provider in providers) {
            val candidate = canvasAttempt { provider.resolve(song) } ?: continue
            if (!youtubeAllowed && candidate.canvas.source == CanvasSource.YOUTUBE_CLIP) continue
            if (!canvasArtistMatches(song, candidate.artist) || candidate.expiresAtMs <= now() + 60_000) continue
            result = candidate
            break
        }
        cache[key] = Entry(result, result?.expiresAtMs?.minus(60_000) ?: (now() + 300_000))
        canvasAttempt {
            file?.let {
                it.parentFile?.mkdirs()
                val temporary = File(it.parentFile, "${it.name}.tmp")
                temporary.writeText(Json.encodeToString(cache.toMap()))
                check(temporary.renameTo(it))
            }
        }
        result
    }

    /** Olvida lo resuelto para [songId] (p. ej. tras un 403 de una URL caducada). O(64). */
    suspend fun invalidate(songId: String) = mutex.withLock {
        cache.keys.removeAll { it.startsWith("$songId|") }
        Unit
    }
}

/**
 * Clip de siete segundos centrado o desde la primera línea más repetida (empate: primera aparición).
 * O(n + c) tiempo y espacio, n líneas y c caracteres normalizados; límites O(1).
 */
fun canvasClip(durationMs: Long, lyrics: Lyrics? = null): Pair<Long, Long> {
    val duration = durationMs.coerceAtLeast(0)
    val length = minOf(7_000L, duration)
    val lines = if (lyrics?.synced == true) lyrics.lines else emptyList()
    val normalized = lines.map { normalizeCanvasText(it.text) }
    val counts = normalized.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
    val repeated = normalized.indices.maxByOrNull { counts[normalized[it]] ?: 0 }
        ?.takeIf { (counts[normalized[it]] ?: 0) >= 2 }
    val start = (repeated?.let { lines[it].startMs } ?: ((duration - length) / 2)).coerceIn(0, duration - length)
    return start to start + length
}

/** TTL máximo de un día, reducido por caducidades explícitas de la URL. O(u) caracteres de URL. */
fun canvasUrlExpiry(url: String, nowMs: Long): Long {
    val expiry = Regex("(?:[?&])(?:expire|expires|exp)=([0-9]+)", RegexOption.IGNORE_CASE)
        .find(url)?.groupValues?.get(1)?.toLongOrNull()
    val epochMs = expiry?.let { if (it < 10_000_000_000L) it * 1000 else it }
    return minOf(nowMs + 86_400_000, epochMs ?: Long.MAX_VALUE)
}
