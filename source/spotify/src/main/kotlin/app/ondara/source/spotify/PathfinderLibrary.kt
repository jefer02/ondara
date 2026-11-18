/*
 * Consultas GraphQL persistidas del reproductor web de Spotify (`api-partner.spotify.com/pathfinder`).
 * Nombres de operación, hashes, variables y forma de las respuestas adaptados de:
 *
 *  - Metrolist (GPL-3.0), `meow/nyx/spotlib/Pathfinder.kt` y `SpotifyClient.kt`.
 *    https://github.com/MetrolistGroup/Metrolist/tree/main/shared/src/jvmSharedMain/kotlin/meow/nyx/spotlib
 *  - Echo Music (GPL-3.0), `spotify/Spotify.kt` y `SpotifyHashProvider.kt`.
 *    https://github.com/EchoMusicApp/Echo-Music/tree/main/app/src/main/kotlin/com/music/echo/spotify
 */
package app.ondara.source.spotify

import app.ondara.core.network.array
import app.ondara.core.network.get
import app.ondara.core.network.string
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Biblioteca de Spotify a través de la API interna del reproductor web; solo la usa el modo experimental.
 *
 * Cada consulta se identifica por un hash que Spotify rota al publicar una versión nueva del reproductor,
 * por eso cada [Operation] lleva varios candidatos y se prueban en orden.
 */
internal class PathfinderLibrary(
    private val http: HttpClient,
    private val send: AuthorizedSend,
    private val clientToken: suspend () -> String?,
) {
    suspend fun me(): SpotifyUser = PathfinderParsers.user(query(Operation.Profile) {})

    suspend fun playlists(): List<SpotifyPlaylist> = paged { offset ->
        PathfinderParsers.playlists(query(Operation.Library) { libraryVariables("Playlists", offset) })
    }

    suspend fun playlistTracks(playlistId: String): List<SpotifyTrack> = paged { offset ->
        PathfinderParsers.playlistTracks(
            query(Operation.PlaylistContents) {
                put("uri", "spotify:playlist:$playlistId")
                put("offset", offset)
                put("limit", PageSize)
                put("includeEpisodeContentRatingsV2", false)
                put("enableWatchFeedEntrypoint", false)
            },
        )
    }

    suspend fun savedTracks(): List<SpotifyTrack> = paged { offset ->
        PathfinderParsers.savedTracks(
            query(Operation.LibraryTracks) {
                put("offset", offset)
                put("limit", PageSize)
            },
        )
    }

    suspend fun savedAlbums(): List<SpotifyAlbum> = paged { offset ->
        PathfinderParsers.albums(query(Operation.Library) { libraryVariables("Albums", offset) })
    }

    /** Avanza por las entradas crudas recibidas, incluso si la página es corta o contiene huecos. O(n). */
    private suspend fun <T> paged(fetch: suspend (offset: Int) -> PathfinderPage<T>): List<T> {
        val out = ArrayList<T>()
        var offset = 0
        do {
            val page = fetch(offset)
            out += page.items
            offset += page.received
            if (offset >= page.total) return out
            if (page.received == 0) break
        } while (offset < MaxItems)
        throw SpotifyApiException(200, "Spotify devolvió una biblioteca incompleta. Inténtalo de nuevo.")
    }

    /**
     * Lanza la consulta probando los hashes de [operation] en orden. O(h) peticiones con h ≤ 2 candidatos.
     *
     * @throws SpotifyApiException si ningún hash conocido funciona (Spotify cambió su API interna).
     */
    private suspend fun query(operation: Operation, variables: JsonObjectBuilder.() -> Unit): JsonElement {
        var lastStatus = 0
        for ((name, hash) in operation.candidates) {
            val body = buildJsonObject {
                putJsonObject("variables", variables)
                put("operationName", name)
                putJsonObject("extensions") {
                    putJsonObject("persistedQuery") {
                        put("version", 1)
                        put("sha256Hash", hash)
                    }
                }
            }.toString()
            val response = send { token ->
                val client = clientToken()
                http.post(Url) {
                    webPlayerHeaders()
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("spotify-app-version", WebPlayer.AppVersion)
                    client?.let { header("client-token", it) }
                    setBody(TextContent(body, ContentType.Application.Json))
                }
            }
            lastStatus = response.status.value
            if (lastStatus == 401) throw SpotifyAuthException("La sesión web de Spotify caducó. Vuelve a pegar la cookie sp_dc.")
            // 400/404/412 es lo que responde pathfinder a un hash retirado: se prueba el siguiente.
            if (lastStatus in StaleHashStatuses) continue
            val json = response.takeIf { it.status.isSuccess() }?.jsonOrNull() ?: break
            val errors = json["errors"].array
            if (errors.isEmpty()) return json
            if (errors.none { it["message"].string.orEmpty().contains("PersistedQueryNotFound", ignoreCase = true) }) break
        }
        throw SpotifyApiException(lastStatus, "$MsgApiChanged (HTTP $lastStatus)")
    }

    private fun JsonObjectBuilder.libraryVariables(filter: String, offset: Int) {
        putJsonArray("filters") { add(filter) }
        put("order", JsonNull)
        put("textFilter", "")
        putJsonArray("features") { LibraryFeatures.forEach { add(it) } }
        put("limit", PageSize)
        put("offset", offset)
        // flatten = true devuelve también las playlists que están dentro de carpetas.
        put("flatten", true)
        putJsonArray("expandedFolders") {}
        put("folderUri", JsonNull)
        put("includeFoldersWhenFlattening", false)
    }

    /** Consulta persistida con sus (nombre de operación, hash) conocidos, del más reciente al más antiguo. */
    internal enum class Operation(val candidates: List<Pair<String, String>>) {
        Profile(
            listOf(
                "profileAttributes" to "08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0",
                "profileAttributes" to "53bcb064f6cd18c23f752bc324a791194d20df612d8e1239c735144ab0399ced",
            ),
        ),
        Library(
            listOf(
                "libraryV3" to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
                "libraryV3" to "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3",
            ),
        ),
        PlaylistContents(
            listOf(
                "fetchPlaylistContents" to "e4b2953f160e58e38ac025d79b5a9b3aceee5c4c716598e9830bfceb69faff5f",
                "fetchPlaylist" to "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763",
            ),
        ),
        LibraryTracks(listOf("fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240")),
    }

    internal companion object {
        const val Url = "https://api-partner.spotify.com/pathfinder/v2/query"
        const val PageSize = 50
        const val MsgApiChanged = "Spotify cambió la API de su reproductor web; usa el modo oficial"

        /** Tope de seguridad contra un `totalCount` disparatado. */
        private const val MaxItems = 20_000
        private val StaleHashStatuses = setOf(400, 404, 412)
        private val LibraryFeatures = listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "PRERELEASES_V2", "CLIPS", "EVENTS")
    }
}

/** Página de pathfinder: [received] cuenta las entradas crudas (antes de filtrar) y [total] es el `totalCount`. */
internal data class PathfinderPage<T>(val items: List<T>, val received: Int, val total: Int)
