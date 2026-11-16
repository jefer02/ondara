package app.ondara.source.lyrics

import app.ondara.core.model.LyricsQuery
import app.ondara.core.network.array
import app.ondara.core.network.double
import app.ondara.core.network.get
import app.ondara.core.network.int
import app.ondara.core.network.string
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

private const val USER_AGENT = "Ondara/0.1 (https://github.com/ondara-app/ondara)"

/** LRCLIB (`lrclib.net`): base de datos abierta de letras sincronizadas por línea. */
class LrcLibProvider(private val http: HttpClient, private val baseUrl: String = "https://lrclib.net") : LyricsProvider {
    override val name = "LRCLIB"

    override suspend fun search(query: LyricsQuery): List<LyricsCandidate> {
        val results: JsonArray = http.get("$baseUrl/api/search") {
            header("User-Agent", USER_AGENT)
            parameter("track_name", LyricsScorer.normalize(query.title))
            parameter("artist_name", query.artist)
        }.body()
        return results.mapNotNull { candidate(it, query) }
    }

    private fun candidate(e: JsonElement, query: LyricsQuery): LyricsCandidate? {
        val durationSec = e["duration"].double?.roundToInt()
        val lyrics = e["syncedLyrics"].string?.takeIf { it.isNotBlank() }
            ?.let { LrcParser.parse(it, name, (durationSec ?: query.durationSec) * 1000L) }
            ?: e["plainLyrics"].string?.let { LrcParser.plain(it, name) }
            ?: return null
        return LyricsCandidate(lyrics, e["trackName"].string, e["artistName"].string, durationSec)
    }
}

/** KuGou: letras KRC con sincronía palabra a palabra. */
class KuGouProvider(private val http: HttpClient, private val baseUrl: String = "https://lyrics.kugou.com") : LyricsProvider {
    override val name = "KuGou"

    override suspend fun search(query: LyricsQuery): List<LyricsCandidate> = coroutineScope {
        // KuGou responde `text/plain`, por eso se parsea a mano.
        val found = json(
            http.get("$baseUrl/search") {
                parameter("ver", 1)
                parameter("man", "yes")
                parameter("client", "pc")
                parameter("keyword", "${query.artist} - ${LyricsScorer.normalize(query.title)}")
                if (query.durationSec > 0) parameter("duration", query.durationSec * 1000L)
            }.bodyAsText(),
        )
        found["candidates"].array.take(MAX_CANDIDATES).map { c ->
            async {
                val id = c["id"].string ?: return@async null
                val key = c["accesskey"].string ?: return@async null
                val file = json(
                    http.get("$baseUrl/download") {
                        parameter("fmt", "krc")
                        parameter("charset", "utf8")
                        parameter("client", "pc")
                        parameter("ver", 1)
                        parameter("id", id)
                        parameter("accesskey", key)
                    }.bodyAsText(),
                )
                val content = file["content"].string ?: return@async null
                val lyrics = KrcParser.parse(KrcParser.decode(content), name) ?: return@async null
                LyricsCandidate(lyrics, c["song"].string, c["singer"].string, c["duration"].int?.div(1000))
            }
        }.awaitAll().filterNotNull()
    }

    private fun json(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private companion object {
        const val MAX_CANDIDATES = 2
    }
}

/**
 * Adapta cualquier fuente "letra por videoId" (p. ej. la pestaña de letra de YouTube Music,
 * que da texto plano) al contrato [LyricsProvider]. Es el último recurso cuando nadie tiene sincronía.
 */
class PlainTextProvider(override val name: String, private val fetch: suspend (videoId: String) -> String?) : LyricsProvider {
    override suspend fun search(query: LyricsQuery): List<LyricsCandidate> {
        val lyrics = fetch(query.videoId)?.let { LrcParser.plain(it, name) } ?: return emptyList()
        // Viene del propio vídeo: título y duración coinciden por construcción.
        return listOf(LyricsCandidate(lyrics, query.title, query.artist, query.durationSec.takeIf { it > 0 }))
    }
}
