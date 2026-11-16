package app.ondara.source.innertube

import app.ondara.core.model.AlbumPage
import app.ondara.core.model.ArtistPage
import app.ondara.core.model.HomeShelf
import app.ondara.core.model.MoodCategory
import app.ondara.core.model.PlaylistPage
import app.ondara.core.model.SearchFilter
import app.ondara.core.model.SearchPage
import app.ondara.core.model.Song
import app.ondara.core.network.findAll
import app.ondara.core.network.string
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

/** Identidad con la que nos presentamos a InnerTube. */
data class YouTubeClient(
    val name: String,
    val version: String,
    val id: String,
    val userAgent: String,
    val host: String = "https://www.youtube.com",
    val extra: Map<String, String> = emptyMap(),
    val androidSdk: Int? = null,
) {
    companion object {
        val WEB_REMIX = YouTubeClient(
            name = "WEB_REMIX", version = "1.20261101.01.00", id = "67",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
            host = "https://music.youtube.com",
        )
        val ANDROID = YouTubeClient(
            name = "ANDROID", version = "20.10.38", id = "3",
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
            extra = mapOf("osName" to "Android", "osVersion" to "14"), androidSdk = 34,
        )
        val IOS = YouTubeClient(
            name = "IOS", version = "20.10.4", id = "5",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            extra = mapOf(
                "deviceMake" to "Apple", "deviceModel" to "iPhone16,2",
                "osName" to "iPhone", "osVersion" to "18.3.2.22D82",
            ),
        )
        val ANDROID_VR = YouTubeClient(
            name = "ANDROID_VR", version = "1.61.48", id = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; " +
                "Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
            extra = mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "osName" to "Android", "osVersion" to "12"),
            androidSdk = 32,
        )

        /** Orden de rotación para el endpoint `player` (verificado: ANDROID e IOS devuelven URLs directas). */
        val PLAYER_ROTATION = listOf(ANDROID, IOS, ANDROID_VR)
    }
}

/** Idioma/país del contenido y cookie opcional de sesión; se lee en cada petición. */
data class InnerTubeConfig(val language: String = "en", val country: String = "US", val cookie: String = "")

/**
 * Cliente de la API interna de YouTube Music (`youtubei/v1`): search, browse, next y player.
 *
 * Solo hace transporte; el parseo vive en [InnerTubeParser] para poder probarlo con respuestas grabadas.
 */
class InnerTubeClient(
    private val http: HttpClient,
    private val config: () -> InnerTubeConfig = { InnerTubeConfig() },
) {
    suspend fun search(query: String, filter: SearchFilter): SearchPage {
        val json = call("search") {
            put("query", query)
            filter.params()?.let { put("params", it) }
        }
        return InnerTubeParser.search(json)
    }

    suspend fun searchContinuation(token: String): SearchPage =
        InnerTubeParser.searchContinuation(call("search", continuation = token) {})

    suspend fun suggestions(query: String): List<String> =
        InnerTubeParser.suggestions(call("music/get_search_suggestions") { put("input", query) })

    /** Estanterías del inicio; sigue hasta [continuations] páginas extra. */
    suspend fun home(continuations: Int = 1): List<HomeShelf> {
        var json = call("browse") { put("browseId", "FEmusic_home") }
        val shelves = InnerTubeParser.shelves(json).toMutableList()
        var token = InnerTubeParser.sectionContinuation(json)
        var left = continuations
        while (token != null && left-- > 0) {
            json = call("browse", continuation = token) {}
            shelves += InnerTubeParser.shelves(json)
            token = InnerTubeParser.sectionContinuation(json)
        }
        return shelves
    }

    suspend fun charts(): List<HomeShelf> = InnerTubeParser.shelves(call("browse") { put("browseId", "FEmusic_charts") })

    suspend fun moods(): List<MoodCategory> =
        InnerTubeParser.moods(call("browse") { put("browseId", "FEmusic_moods_and_genres") })

    suspend fun moodShelves(browseId: String, params: String?): List<HomeShelf> = InnerTubeParser.shelves(
        call("browse") {
            put("browseId", browseId)
            params?.let { put("params", it) }
        },
    )

    suspend fun album(browseId: String): AlbumPage = InnerTubeParser.album(browseId, call("browse") { put("browseId", browseId) })

    suspend fun artist(browseId: String): ArtistPage = InnerTubeParser.artist(browseId, call("browse") { put("browseId", browseId) })

    /** [playlistId] con o sin prefijo `VL`. Sigue las continuaciones hasta [maxSongs]. */
    suspend fun playlist(playlistId: String, maxSongs: Int = 500): PlaylistPage {
        val id = playlistId.removePrefix("VL")
        var page = InnerTubeParser.playlist(id, call("browse") { put("browseId", "VL$id") })
        var token = page.continuation
        while (token != null && page.songs.size < maxSongs) {
            val more = InnerTubeParser.playlistContinuation(call("browse", continuation = token) {})
            if (more.first.isEmpty()) break
            page = page.copy(songs = page.songs + more.first, continuation = more.second)
            token = more.second
        }
        return page
    }

    /** Cola relacionada. Sin [playlistId] se usa la radio automática `RDAMVM<videoId>`. */
    suspend fun next(videoId: String, playlistId: String? = null): List<Song> {
        val json = call("next") {
            put("videoId", videoId)
            put("playlistId", playlistId ?: "RDAMVM$videoId")
            put("isAudioOnly", true)
        }
        return InnerTubeParser.queue(json)
    }

    /** Letra en texto plano de la pestaña "Lyrics" de YouTube Music, si existe. */
    suspend fun lyrics(videoId: String): String? {
        val next = call("next") { put("videoId", videoId) }
        val browseId = next.findAll("browseId").firstNotNullOfOrNull { it.string?.takeIf { id -> id.startsWith("MPLYt") } }
            ?: return null
        return InnerTubeParser.lyrics(call("browse") { put("browseId", browseId) })
    }

    /** Respuesta cruda de `player` haciéndose pasar por [client]. */
    suspend fun player(videoId: String, client: YouTubeClient): JsonObject = call("player", client) {
        put("videoId", videoId)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
    }

    private suspend fun call(
        endpoint: String,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
        continuation: String? = null,
        body: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val cfg = config()
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    put("hl", cfg.language)
                    put("gl", cfg.country)
                    client.extra.forEach { (k, v) -> put(k, v) }
                    client.androidSdk?.let { put("androidSdkVersion", it) }
                }
            }
            continuation?.let { put("continuation", it) }
            body()
        }
        return http.post("${client.host}/youtubei/v1/$endpoint") {
            parameter("prettyPrint", "false")
            continuation?.let {
                parameter("ctoken", it)
                parameter("continuation", it)
                parameter("type", "next")
            }
            contentType(ContentType.Application.Json)
            header("User-Agent", client.userAgent)
            header("X-YouTube-Client-Name", client.id)
            header("X-YouTube-Client-Version", client.version)
            header("Origin", client.host)
            header("Referer", client.host + "/")
            // La sesión solo viaja con el cliente web: es el único que acepta cookies.
            if (cfg.cookie.isNotBlank() && client == YouTubeClient.WEB_REMIX) {
                header("Cookie", cfg.cookie)
                sapisidHash(cfg.cookie, client.host)?.let { header("Authorization", it) }
            }
            setBody(payload)
        }.body()
    }

    private fun SearchFilter.params(): String? = when (this) {
        SearchFilter.ALL -> null
        SearchFilter.SONGS -> "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        SearchFilter.ALBUMS -> "EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D"
        SearchFilter.ARTISTS -> "EgWKAQIgAWoKEAkQBRAKEAMQBA%3D%3D"
        SearchFilter.PLAYLISTS -> "EgeKAQQoAEABagoQCRAFEAoQAxAE"
    }

    companion object {
        /** Cabecera `Authorization: SAPISIDHASH …` que YouTube exige junto a la cookie de sesión. */
        fun sapisidHash(cookie: String, origin: String, nowSec: Long = System.currentTimeMillis() / 1000): String? {
            val sapisid = cookie.split(';').map { it.trim() }
                .firstOrNull { it.startsWith("SAPISID=") || it.startsWith("__Secure-3PAPISID=") }
                ?.substringAfter('=') ?: return null
            val digest = MessageDigest.getInstance("SHA-1").digest("$nowSec $sapisid $origin".toByteArray())
            return "SAPISIDHASH ${nowSec}_" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}
