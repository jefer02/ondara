package app.ondara.source.resolver

import app.ondara.core.model.AudioQuality
import app.ondara.core.model.ResolvedStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Un motor capaz de convertir un `videoId` en una URL de audio (patrón Strategy). */
interface StreamStrategy {
    val name: String
    suspend fun resolve(videoId: String, quality: AudioQuality): Result<ResolvedStream>
}

class StreamUnavailableException(val videoId: String, val errors: List<Throwable>) :
    Exception("No se pudo resolver $videoId: " + errors.joinToString("; ") { it.message ?: it.javaClass.simpleName })

/**
 * Núcleo de la reproducción: caché + cadena de estrategias (Chain of Responsibility).
 *
 * - Acierto de caché: **O(1)**.
 * - Fallo de caché: **O(k)** llamadas de red en el peor caso, con k estrategias (se prueban en orden de prioridad).
 * - Espacio: O(c) con c = capacidad de la caché.
 *
 * Las resoluciones concurrentes del mismo id se serializan con un mutex por id, de modo que la
 * pre-resolución de la canción siguiente y el salto del usuario nunca duplican trabajo de red.
 */
class StreamResolver(
    private val strategies: List<StreamStrategy>,
    private val cache: ResolvedStreamCache,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun resolve(videoId: String, quality: AudioQuality): ResolvedStream {
        val key = cacheKey(videoId, quality)
        cache.get(key)?.let { return it }
        val lock = locks.getOrPut(key) { Mutex() }
        try {
            return lock.withLock {
                cache.get(key) ?: runChain(videoId, quality, skip = null).also { cache.put(key, it) }
            }
        } finally {
            if (!lock.isLocked) locks.remove(key, lock)
        }
    }

    /**
     * Recuperación tras un HTTP 403 de [failed]: invalida su entrada y reintenta **con la siguiente
     * estrategia** de la cadena (la que falló se salta). Quien llama decide hacerlo una sola vez.
     */
    suspend fun resolveAfterFailure(videoId: String, quality: AudioQuality, failed: ResolvedStream?): ResolvedStream {
        val key = cacheKey(videoId, quality)
        cache.invalidate(key)
        return runChain(videoId, quality, skip = failed?.source).also { cache.put(key, it) }
    }

    /** Pre-resuelve sin propagar errores; devuelve `true` si quedó en caché. */
    suspend fun prefetch(videoId: String, quality: AudioQuality): Boolean = try {
        resolve(videoId, quality)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    fun cached(videoId: String, quality: AudioQuality): ResolvedStream? = cache.get(cacheKey(videoId, quality))

    fun invalidate(videoId: String, quality: AudioQuality) = cache.invalidate(cacheKey(videoId, quality))

    private suspend fun runChain(videoId: String, quality: AudioQuality, skip: String?): ResolvedStream {
        val errors = ArrayList<Throwable>()
        val ordered = if (skip == null) strategies else strategies.filter { it.name != skip }.ifEmpty { strategies }
        for (strategy in ordered) {
            val result = try {
                strategy.resolve(videoId, quality)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.onSuccess { return it }.onFailure { errors += it }
        }
        throw StreamUnavailableException(videoId, errors)
    }

    private fun cacheKey(videoId: String, quality: AudioQuality) = "$videoId/${quality.name}"
}

/**
 * Caché LRU con TTL para URLs resueltas.
 *
 * `LinkedHashMap(accessOrder = true)` mantiene el orden de uso: `get`/`put` son **O(1)** amortizado
 * y el desalojo del menos usado también. El TTL sale del propio `expire` de la URL de YouTube
 * (menos un margen de seguridad para que una canción no caduque a mitad de reproducción).
 * Espacio: O(capacity).
 */
class ResolvedStreamCache(
    private val capacity: Int = 64,
    private val safetyMarginMs: Long = 10 * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val map = object : LinkedHashMap<String, ResolvedStream>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedStream>?) = size > capacity
    }

    @Synchronized
    fun get(key: String): ResolvedStream? {
        val hit = map[key] ?: return null
        if (hit.isExpired(now() + safetyMarginMs)) {
            map.remove(key)
            return null
        }
        return hit
    }

    @Synchronized
    fun put(key: String, stream: ResolvedStream) {
        map[key] = stream
    }

    @Synchronized
    fun invalidate(key: String) {
        map.remove(key)
    }

    @Synchronized
    fun clear() = map.clear()

    val size: Int @Synchronized get() = map.size
}

internal object StreamUrls {
    private val EXPIRE = Regex("""[?&/]expire[=/](\d{9,11})""")
    private const val DEFAULT_TTL_MS = 5 * 60 * 60_000L

    /** Lee el parámetro `expire` (epoch en segundos) de una URL de googlevideo; si falta asume 5 h. */
    fun expiresAtMs(url: String, nowMs: Long = System.currentTimeMillis()): Long =
        EXPIRE.find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000) ?: (nowMs + DEFAULT_TTL_MS)
}

/** Candidato de audio independiente del motor, para compartir el criterio de selección. */
internal data class AudioCandidate(val url: String, val mimeType: String, val bitrate: Int, val contentLength: Long?, val loudnessDb: Float?)

/**
 * Elige la pista según la calidad: la de mayor bitrate que no supere `quality.maxBitrate`;
 * a igualdad práctica prefiere Opus (mejor calidad por bit). Si todas superan el tope, la más ligera.
 * O(f) con f formatos.
 */
internal fun List<AudioCandidate>.pick(quality: AudioQuality): AudioCandidate? {
    if (isEmpty()) return null
    val allowed = filter { it.bitrate <= quality.maxBitrate }
    if (allowed.isEmpty()) return minByOrNull { it.bitrate }
    return allowed.maxWithOrNull(compareBy<AudioCandidate> { it.bitrate / 16_000 }.thenBy { if ("opus" in it.mimeType) 1 else 0 })
}
