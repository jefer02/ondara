// Adaptado de Echo Music (https://github.com/EchoMusicApp/Echo-Music), GPL-3.0.
package app.ondara.source.canvas

import app.ondara.core.model.Canvas
import app.ondara.core.model.CanvasSource
import app.ondara.core.model.Song
import app.ondara.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonElement

/** Portadas de vídeo del catálogo público del reproductor incrustado de Tidal. */
class TidalCanvasProvider(private val http: HttpClient) : CanvasProvider {
    override suspend fun resolve(song: Song): CanvasResult? {
        val root = HttpClientFactory.json.parseToJsonElement(http.get("https://api.tidal.com/v1/search") {
            header("X-Tidal-Token", "vNVdglQOjFJJGG2U")
            parameter("query", "${song.title} ${song.artistLine}")
            parameter("types", "TRACKS")
            parameter("limit", 10)
            parameter("countryCode", "US")
        }.bodyAsText())
        return parseTidalCanvas(root, song)
    }
}

/** O(n) nodos y resultados; exige identidad de artista y título antes de aceptar la portada 1280×1280. */
fun parseTidalCanvas(root: JsonElement, song: Song, nowMs: Long = System.currentTimeMillis()): CanvasResult? {
    val section = root.findFirst("tracks") ?: return null
    for (item in section["items"].array) {
        if (normalizeCanvasText(item["title"].string.orEmpty()) != normalizeCanvasText(song.title)) continue
        val artists = listOfNotNull(item["artist"]["name"].string) + item["artists"].array.mapNotNull { it["name"].string }
        val artist = artists.firstOrNull { canvasArtistMatches(song, it) } ?: continue
        val id = item["album"]["videoCover"].string ?: continue
        if (!Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}").matches(id)) continue
        val url = "https://resources.tidal.com/videos/${id.replace('-', '/')}/1280x1280.mp4"
        return CanvasResult(Canvas(song.id, url, CanvasSource.TIDAL, width = 1280, height = 1280), artist, canvasUrlExpiry(url, nowMs))
    }
    return null
}
