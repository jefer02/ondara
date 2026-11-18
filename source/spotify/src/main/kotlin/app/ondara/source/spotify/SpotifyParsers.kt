package app.ondara.source.spotify

import app.ondara.core.network.array
import app.ondara.core.network.bool
import app.ondara.core.network.get
import app.ondara.core.network.int
import app.ondara.core.network.long
import app.ondara.core.network.path
import app.ondara.core.network.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Respuestas de la API Web oficial (`api.spotify.com/v1`) → modelos. Todo es tolerante a campos ausentes. */
internal object SpotifyParsers {
    fun user(json: JsonElement): SpotifyUser {
        val id = json["id"].string ?: throw SpotifyApiException(200, "Spotify no devolvió el perfil del usuario.")
        return SpotifyUser(id, json["display_name"].string?.takeIf { it.isNotBlank() } ?: id, image(json["images"]))
    }

    fun playlist(json: JsonElement): SpotifyPlaylist? {
        val id = json["id"].string ?: return null
        return SpotifyPlaylist(
            id = id,
            name = json["name"].string.orEmpty(),
            owner = json.path("owner", "display_name").string ?: json.path("owner", "id").string,
            imageUrl = image(json["images"]),
            // `tracks` pasó a llamarse `items` en febrero de 2026; las apps con cuota extendida siguen viendo `tracks`.
            trackCount = json.path("items", "total").int ?: json.path("tracks", "total").int ?: 0,
            snapshotId = json["snapshot_id"].string,
        )
    }

    /** Entrada de una playlist: la canción va en `item` (API actual) o en `track` (API anterior a 2026). */
    fun playlistEntry(entry: JsonElement): SpotifyTrack? {
        if (entry["is_local"].bool == true) return null
        return track(entry["item"].takeIf { it is JsonObject } ?: entry["track"])
    }

    /** Devuelve null para huecos nulos, archivos locales (sin id) y episodios de pódcast. */
    fun track(json: JsonElement?): SpotifyTrack? {
        val id = json["id"].string?.takeIf { it.isNotBlank() } ?: return null
        if (json["is_local"].bool == true || (json["type"].string ?: "track") != "track") return null
        return SpotifyTrack(
            id = id,
            title = json["name"].string.orEmpty(),
            artists = artists(json),
            album = json.path("album", "name").string?.takeIf { it.isNotBlank() },
            durationMs = json["duration_ms"].long ?: 0,
            imageUrl = image(json.path("album", "images")),
        )
    }

    fun album(json: JsonElement?): SpotifyAlbum? {
        val id = json["id"].string?.takeIf { it.isNotBlank() } ?: return null
        return SpotifyAlbum(
            id = id,
            title = json["name"].string.orEmpty(),
            artists = artists(json),
            imageUrl = image(json["images"]),
            year = json["release_date"].string?.take(4)?.toIntOrNull(),
        )
    }

    private fun artists(json: JsonElement?): List<String> =
        json["artists"].array.mapNotNull { it["name"].string?.takeIf(String::isNotBlank) }

    /** La API Web ordena las imágenes de mayor a menor: la primera es la más grande. */
    private fun image(images: JsonElement?): String? = images.array.firstNotNullOfOrNull { it["url"].string }
}

/** Respuestas GraphQL del reproductor web (pathfinder) → modelos. */
internal object PathfinderParsers {
    fun user(json: JsonElement): SpotifyUser {
        val profile = json.path("data", "me", "profile")
        val id = profile["username"].string ?: profile["uri"].string?.substringAfterLast(':')
            ?: throw SpotifyApiException(200, PathfinderLibrary.MsgApiChanged)
        // `avatar` llega como URL suelta o como objeto con `sources`, según la versión de la consulta.
        val avatar = profile["avatar"].string ?: image(profile.path("avatar", "sources"))
        return SpotifyUser(id, profile["name"].string?.takeIf { it.isNotBlank() } ?: id, avatar)
    }

    fun playlists(json: JsonElement): PathfinderPage<SpotifyPlaylist> = libraryPage(json, "PlaylistResponseWrapper") { uri, data ->
        SpotifyPlaylist(
            id = uri.substringAfterLast(':'),
            name = data["name"].string.orEmpty(),
            owner = data.path("ownerV2", "data", "name").string,
            imageUrl = image(data.path("images", "items")[0]["sources"]),
            trackCount = data.path("content", "totalCount").int ?: data["totalCount"].int ?: 0,
            snapshotId = data["revisionId"].string,
        )
    }

    fun albums(json: JsonElement): PathfinderPage<SpotifyAlbum> = libraryPage(json, "AlbumResponseWrapper") { uri, data ->
        SpotifyAlbum(
            id = uri.substringAfterLast(':'),
            title = data["name"].string.orEmpty(),
            artists = artists(data),
            imageUrl = image(data.path("coverArt", "sources")),
            year = data.path("date", "year").int ?: data.path("date", "isoString").string?.take(4)?.toIntOrNull(),
        )
    }

    fun playlistTracks(json: JsonElement): PathfinderPage<SpotifyTrack> {
        val content = json.path("data", "playlistV2", "content")
        val entries = content["items"].array
        return PathfinderPage(entries.mapNotNull { track(it["itemV2"]) }, entries.size, content["totalCount"].int ?: 0)
    }

    fun savedTracks(json: JsonElement): PathfinderPage<SpotifyTrack> {
        val tracks = json.path("data", "me", "library", "tracks")
        val entries = tracks["items"].array
        return PathfinderPage(entries.mapNotNull { track(it["track"]) }, entries.size, tracks["totalCount"].int ?: 0)
    }

    /** Elementos de `libraryV3` cuyo envoltorio es de tipo [typename] (descarta carpetas, "Tus me gusta", etc.). */
    private fun <T> libraryPage(json: JsonElement, typename: String, build: (uri: String, data: JsonElement?) -> T): PathfinderPage<T> {
        val library = json.path("data", "me", "libraryV3")
        val entries = library["items"].array
        val items = entries.mapNotNull { entry ->
            val wrapper = entry["item"]
            val data = wrapper["data"]
            val uri = wrapper["_uri"].string ?: data["uri"].string
            if (wrapper["__typename"].string == typename && uri != null) build(uri, data) else null
        }
        return PathfinderPage(items, entries.size, library["totalCount"].int ?: 0)
    }

    /** Solo acepta URIs `spotify:track:`: así quedan fuera los archivos locales, los episodios y los huecos. */
    private fun track(wrapper: JsonElement?): SpotifyTrack? {
        val data = wrapper["data"]
        val uri = data["uri"].string ?: wrapper["_uri"].string ?: wrapper["uri"].string
        val title = data["name"].string
        if (uri == null || !uri.startsWith("spotify:track:") || title.isNullOrBlank()) return null
        val album = data["albumOfTrack"]
        return SpotifyTrack(
            id = uri.substringAfterLast(':'),
            title = title,
            artists = artists(data),
            album = album["name"].string?.takeIf { it.isNotBlank() },
            durationMs = data.path("trackDuration", "totalMilliseconds").long ?: data.path("duration", "totalMilliseconds").long ?: 0,
            imageUrl = image(album.path("coverArt", "sources")),
        )
    }

    private fun artists(data: JsonElement?): List<String> =
        data.path("artists", "items").array.mapNotNull { it.path("profile", "name").string?.takeIf(String::isNotBlank) }

    /** Pathfinder no garantiza el orden de `sources`: se elige la de mayor anchura. O(n). */
    private fun image(sources: JsonElement?): String? =
        sources.array.filter { it["url"].string != null }.maxByOrNull { it["width"].int ?: 0 }?.get("url").string
}
