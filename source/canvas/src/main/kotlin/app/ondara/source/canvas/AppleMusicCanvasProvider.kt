// Adaptado de Echo Music (https://github.com/EchoMusicApp/Echo-Music), GPL-3.0.
package app.ondara.source.canvas

import app.ondara.core.model.Canvas
import app.ondara.core.model.CanvasSource
import app.ondara.core.model.Song
import app.ondara.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Busca álbum y después canción en iTunes; solo admite portada editorial animada, nunca un vídeo completo. */
class AppleMusicCanvasProvider(private val http: HttpClient) : CanvasProvider {
    private val tokens = AppleMusicTokenProvider(http)

    /** O(r + a) resultados y assets; como máximo dos búsquedas y r consultas de álbum. */
    override suspend fun resolve(song: Song): CanvasResult? {
        val searches = listOfNotNull(song.album?.title?.let { it to "album" }, song.title to "song")
        for ((term, entity) in searches) {
            val root = HttpClientFactory.json.parseToJsonElement(http.get("https://itunes.apple.com/search") {
                parameter("term", "$term ${song.artistLine}")
                parameter("entity", entity)
                parameter("limit", 10)
                parameter("country", "US")
            }.bodyAsText())
            for (item in root["results"].array) {
                if (!canvasArtistMatches(song, item["artistName"].string.orEmpty())) continue
                val name = item[if (entity == "album") "collectionName" else "trackName"].string.orEmpty()
                if (normalizeCanvasText(name) != normalizeCanvasText(term)) continue
                val albumId = item["collectionId"].string?.takeIf { it.all(Char::isDigit) } ?: continue
                val result = canvasAttempt { album(song, albumId) }
                if (result != null) return result
            }
        }
        return null
    }

    private suspend fun album(song: Song, id: String): CanvasResult? {
        repeat(2) { attempt ->
            val token = tokens.token() ?: return null
            try {
                val root = HttpClientFactory.json.parseToJsonElement(http.get("https://amp-api.music.apple.com/v1/catalog/us/albums/$id") {
                    header("Authorization", "Bearer $token")
                    header("Origin", "https://music.apple.com")
                    header("Referer", "https://music.apple.com/")
                    parameter("extend", "editorialVideo")
                }.bodyAsText())
                return parseAppleCanvas(root, song)
            } catch (error: ClientRequestException) {
                if (error.response.status.value != 401 || attempt != 0) throw error
                tokens.clear()
            }
        }
        return null
    }
}

/** O(a) assets editoriales; prefiere vertical y máxima resolución. HLS conserva su escalera de calidad. */
@Suppress("ReturnCount") // parser tolerante: cada dato ausente es una salida temprana
fun parseAppleCanvas(root: JsonElement, song: Song, nowMs: Long = System.currentTimeMillis()): CanvasResult? {
    val attributes = root["data"].array.firstOrNull()?.get("attributes") ?: return null
    val artist = attributes["artistName"].string ?: return null
    if (!canvasArtistMatches(song, artist)) return null
    val editorial = attributes["editorialVideo"] as? JsonObject ?: return null
    val assets = editorial.entries.filter { "Static" !in it.key }.mapNotNull { (key, value) ->
        val url = listOf("video", "videoUrl", "hlsUrl", "url").firstNotNullOfOrNull { value[it].string }
            ?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
        Triple(key, value, url)
    }
    val best = assets.maxWithOrNull(compareBy<Triple<String, JsonElement, String>> { "Tall" in it.first || "3x4" in it.first }
        .thenBy { (it.second["height"].int ?: 0).toLong() * (it.second["width"].int ?: 0) }) ?: return null
    return CanvasResult(
        Canvas(song.id, best.third, CanvasSource.APPLE_MUSIC, width = best.second["width"].int ?: 0, height = best.second["height"].int ?: 0),
        artist, canvasUrlExpiry(best.third, nowMs),
    )
}
