package app.ondara.source.spotify

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SpotifyClientTest {
    private val requests = ArrayList<HttpRequestData>()
    private val api = "https://api.spotify.com/v1"

    private fun trackJson(id: String?, name: String = "Song $id", type: String = "track", local: Boolean = false) =
        """{"id":${id?.let { "\"$it\"" }},"name":"$name","type":"$type","is_local":$local,"duration_ms":201000,
           "artists":[{"name":"Main"},{"name":"Guest"}],"album":{"name":"LP","images":[{"url":"big.jpg"},{"url":"small.jpg"}]}}"""

    @Test
    fun `me maps the profile`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.OFFICIAL, "tok")
        val client = SpotifyClient(mockHttp(requests) { json("""{"id":"u1","display_name":"Jefe","images":[{"url":"me.jpg"}]}""") }) { auth }
        assertEquals(SpotifyUser("u1", "Jefe", "me.jpg"), client.me())
        assertEquals("$api/me", requests.single().url.toString())
        assertEquals("tok", requests.single().bearer)
    }

    @Test
    fun `playlists follows next until the last page`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.OFFICIAL, "tok")
        val client = SpotifyClient(
            mockHttp(requests) { request ->
                when (request.url.parameters["offset"]) {
                    "0" -> json(
                        """{"items":[
                            {"id":"p1","name":"Propia","owner":{"id":"u1","display_name":"Jefe"},"images":[{"url":"p1.jpg"}],
                             "items":{"total":120},"snapshot_id":"snap1"},
                            null,
                            {"id":"p2","name":"Seguida","owner":{"id":"spotify"},"images":null,"tracks":{"total":50}}
                           ],"next":"$api/me/playlists?offset=50&limit=50"}""",
                    )
                    "50" -> json("""{"items":[{"id":"p3","name":"Última","owner":null}],"next":null}""")
                    else -> error("página inesperada: ${request.url}")
                }
            },
        ) { auth }

        val playlists = client.playlists()

        assertEquals(
            listOf(
                SpotifyPlaylist("p1", "Propia", "Jefe", "p1.jpg", 120, "snap1"),
                SpotifyPlaylist("p2", "Seguida", "spotify", null, 50, null),
                SpotifyPlaylist("p3", "Última", null, null, 0, null),
            ),
            playlists,
        )
        assertEquals(listOf("$api/me/playlists?limit=50&offset=0", "$api/me/playlists?offset=50&limit=50"), requests.map { it.url.toString() })
        assertTrue(requests.all { it.bearer == "tok" })
    }

    @Test
    fun `playlist tracks paginates and skips nulls, local files and episodes`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.OFFICIAL, "tok")
        val client = SpotifyClient(
            mockHttp(requests) { request ->
                if (request.url.parameters["offset"] == "0") {
                    json(
                        """{"items":[
                            {"item":${trackJson("t1")}},
                            {"item":null},
                            {"is_local":true,"item":${trackJson("t-local")}},
                            {"item":${trackJson(null, local = true)}},
                            {"item":${trackJson("ep1", type = "episode")}},
                            null
                           ],"next":"$api/playlists/pl%201/items?offset=50&limit=50"}""",
                    )
                } else {
                    // Forma anterior a febrero de 2026: la canción viene en `track`.
                    json("""{"items":[{"track":${trackJson("t2")}},{"item":null,"track":${trackJson("t3")}}],"next":null}""")
                }
            },
        ) { auth }

        val tracks = client.playlistTracks("pl 1")

        assertEquals(listOf("t1", "t2", "t3"), tracks.map { it.id })
        assertEquals(SpotifyTrack("t1", "Song t1", listOf("Main", "Guest"), "LP", 201_000, "big.jpg"), tracks.first())
        assertEquals("$api/playlists/pl%201/items?limit=50&offset=0", requests.first().url.toString())
        assertEquals(2, requests.size)
    }

    @Test
    fun `next pointing outside the api is not followed`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.OFFICIAL, "tok")
        val client = SpotifyClient(mockHttp(requests) { json("""{"items":[{"track":${trackJson("t1")}}],"next":"https://evil.example/v1/me/tracks"}""") }) { auth }
        assertEquals(listOf("t1"), client.savedTracks().map { it.id })
        assertEquals("$api/me/tracks?limit=50&offset=0", requests.single().url.toString())
    }

    @Test
    fun `saved albums map artists, cover and year`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.OFFICIAL, "tok")
        val client = SpotifyClient(
            mockHttp(requests) {
                json(
                    """{"items":[
                        {"album":{"id":"a1","name":"A Night at the Opera","artists":[{"name":"Queen"}],"images":[{"url":"a1.jpg"}],"release_date":"1975-11-21"}},
                        {"album":{"id":"a2","name":"Sin fecha","artists":[]}},
                        {"album":null}
                       ],"next":null}""",
                )
            },
        ) { auth }
        assertEquals(
            listOf(SpotifyAlbum("a1", "A Night at the Opera", listOf("Queen"), "a1.jpg", 1975), SpotifyAlbum("a2", "Sin fecha", emptyList(), null, null)),
            client.savedAlbums(),
        )
        assertEquals("$api/me/albums?limit=50&offset=0", requests.single().url.toString())
    }

    @Test
    fun `401 invalidates the token once and retries with the new one`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.OFFICIAL, "stale", "fresh")
        val client = SpotifyClient(
            mockHttp(requests) { request ->
                if (request.bearer == "stale") json("""{"error":{"status":401,"message":"The access token expired"}}""", HttpStatusCode.Unauthorized)
                else json("""{"id":"u1","display_name":null}""")
            },
        ) { auth }
        assertEquals(SpotifyUser("u1", "u1", null), client.me())
        assertEquals(listOf("stale", "fresh"), requests.map { it.bearer })
        assertEquals(1, auth.invalidations)
    }

    @Test
    fun `persistent 401 becomes an auth error after a single retry`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.OFFICIAL, "a", "b", "c")
        val client = SpotifyClient(mockHttp(requests) { json("{}", HttpStatusCode.Unauthorized) }) { auth }
        assertThrows<SpotifyAuthException> { client.me() }
        assertEquals(listOf("a", "b"), requests.map { it.bearer })
    }

    @Test
    fun `429 waits for retry-after and then succeeds`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.OFFICIAL, "tok")
        val client = SpotifyClient(
            mockHttp(requests) {
                if (requests.size == 1) json("", HttpStatusCode.TooManyRequests, "Retry-After" to "7") else json("""{"items":[],"next":null}""")
            },
        ) { auth }
        assertTrue(client.savedTracks().isEmpty())
        assertEquals(2, requests.size)
        assertEquals(7_000, testScheduler.currentTime)
        assertEquals(0, auth.invalidations)
    }

    @Test
    fun `429 gives up when the wait is unreasonable or never ends`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.OFFICIAL, "tok")
        val long = SpotifyClient(mockHttp(requests) { json("", HttpStatusCode.TooManyRequests, "Retry-After" to "86400") }) { auth }
        assertEquals(429, assertThrows<SpotifyApiException> { long.me() }.status)
        assertEquals(1, requests.size)
        assertEquals(0, testScheduler.currentTime)

        requests.clear()
        val forever = SpotifyClient(mockHttp(requests) { json("", HttpStatusCode.TooManyRequests) }) { auth }
        assertEquals(429, assertThrows<SpotifyApiException> { forever.me() }.status)
        assertEquals(4, requests.size) // 1 intento + 3 reintentos con la espera por defecto de 2 s
        assertEquals(6_000, testScheduler.currentTime)
    }

    @Test
    fun `403 and other failures surface as api errors with their status`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.OFFICIAL, "tok")
        val forbidden = SpotifyClient(mockHttp { json("""{"error":{"status":403}}""", HttpStatusCode.Forbidden) }) { auth }
        val error = assertThrows<SpotifyApiException> { forbidden.playlistTracks("p1") }
        assertEquals(403, error.status)
        assertTrue(error.message!!.contains("modo desarrollo"))
        val missing = SpotifyClient(mockHttp { json("{}", HttpStatusCode.NotFound) }) { auth }
        assertEquals(404, assertThrows<SpotifyApiException> { missing.playlistTracks("nope") }.status)
    }

    @Test
    fun `auth errors from the provider propagate untouched`() = runTest {
        val signedOut = object : SpotifyAuthProvider {
            override val mode = SpotifyAuthMode.OFFICIAL
            override suspend fun isSignedIn() = false
            override suspend fun accessToken(): String = throw SpotifyAuthException("No has iniciado sesión en Spotify.")
            override suspend fun signOut() = Unit
        }
        val client = SpotifyClient(mockHttp(requests) { json("{}") }) { signedOut }
        assertThrows<SpotifyAuthException> { client.playlists() }
        assertTrue(requests.isEmpty())
    }

    // ── Modo experimental: API interna del reproductor web ────────────────────────────────────────────

    private fun gqlTrack(uri: String, name: String = "Canción", wrapperUriOnly: Boolean = false) =
        """{"__typename":"TrackResponseWrapper","_uri":"$uri","data":{"__typename":"Track",${if (wrapperUriOnly) "" else "\"uri\":\"$uri\","}
           "name":"$name","trackDuration":{"totalMilliseconds":215000},
           "artists":{"items":[{"uri":"spotify:artist:x","profile":{"name":"Rosalía"}},{"profile":{"name":"Otro"}}]},
           "albumOfTrack":{"name":"Disco","coverArt":{"sources":[{"url":"s.jpg","width":64},{"url":"l.jpg","width":640},{"url":"m.jpg","width":300}]}}}}"""

    @Test
    fun `experimental mode reads the profile through pathfinder with web player headers`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.EXPERIMENTAL, "web-token")
        val client = SpotifyClient(
            mockHttp(requests) {
                json("""{"data":{"me":{"profile":{"uri":"spotify:user:jefe","username":"jefe","name":"Jeferson","avatar":{"sources":[{"url":"av.jpg"}]}}}}}""")
            },
        ) { auth }
        assertEquals(SpotifyUser("jefe", "Jeferson", "av.jpg"), client.me())
        val request = requests.single()
        assertEquals("https://api-partner.spotify.com/pathfinder/v2/query", request.url.toString())
        assertEquals("web-token", request.bearer)
        assertEquals("WebPlayer", request.headers["app-platform"])
        assertEquals("https://open.spotify.com", request.headers["Origin"])
        assertNull(request.headers["client-token"])
        val body = request.text()
        assertTrue(body.contains("\"operationName\":\"profileAttributes\""))
        assertTrue(body.contains("\"sha256Hash\":\"08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0\""))
    }

    @Test
    fun `experimental playlists paginate libraryV3 and keep only playlists`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.EXPERIMENTAL, "web-token")
        fun playlist(id: String) = """{"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:$id","data":{"__typename":"Playlist",
            "name":"Lista $id","ownerV2":{"data":{"name":"Jefe"}},"images":{"items":[{"sources":[{"url":"$id.jpg"}]}]}}}}"""
        val client = SpotifyClient(
            mockHttp(requests) { request ->
                val first = request.text().contains("\"offset\":0")
                val items = if (first) {
                    listOf(
                        playlist("p1"),
                        """{"item":{"__typename":"PseudoPlaylistResponseWrapper","_uri":"spotify:collection:tracks","data":{"name":"Liked"}}}""",
                        """{"item":{"__typename":"FolderResponseWrapper","_uri":"spotify:user:jefe:folder:f1","data":{"name":"Carpeta"}}}""",
                    )
                } else {
                    listOf(playlist("p2"))
                }
                json("""{"data":{"me":{"libraryV3":{"totalCount":51,"items":[${items.joinToString(",")}]}}}}""")
            },
        ) { auth }

        assertEquals(
            listOf(SpotifyPlaylist("p1", "Lista p1", "Jefe", "p1.jpg", 0, null), SpotifyPlaylist("p2", "Lista p2", "Jefe", "p2.jpg", 0, null)),
            client.playlists(),
        )
        assertEquals(2, requests.size)
        val body = requests.first().text()
        assertTrue(body.contains("\"operationName\":\"libraryV3\"") && body.contains("\"filters\":[\"Playlists\"]") && body.contains("\"flatten\":true"))
        assertTrue(requests[1].text().contains("\"offset\":50"))
    }

    @Test
    fun `experimental playlist tracks fall back to the older hash and filter non tracks`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.EXPERIMENTAL, "web-token")
        val client = SpotifyClient(
            mockHttp(requests) { request ->
                if (request.text().contains("fetchPlaylistContents")) {
                    json("""{"errors":[{"message":"PersistedQueryNotFound"}]}""")
                } else {
                    json(
                        """{"data":{"playlistV2":{"content":{"totalCount":5,"items":[
                            {"itemV2":${gqlTrack("spotify:track:t1", "Malamente")}},
                            {"itemV2":${gqlTrack("spotify:local:Artista:Disco:Tema:200")}},
                            {"itemV2":${gqlTrack("spotify:episode:e1")}},
                            {"itemV2":{"__typename":"NotFound","_uri":"spotify:track:gone","data":null}},
                            {"itemV2":${gqlTrack("spotify:track:t2", wrapperUriOnly = true)}}
                        ]}}}}""",
                    )
                }
            },
        ) { auth }

        val tracks = client.playlistTracks("pl1")

        assertEquals(listOf("t1", "t2"), tracks.map { it.id })
        assertEquals(SpotifyTrack("t1", "Malamente", listOf("Rosalía", "Otro"), "Disco", 215_000, "l.jpg"), tracks.first())
        assertEquals(2, requests.size)
        val body = requests[1].text()
        assertTrue(body.contains("\"operationName\":\"fetchPlaylist\"") && body.contains("\"uri\":\"spotify:playlist:pl1\""))
    }

    @Test
    fun `experimental saved tracks and albums are parsed`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.EXPERIMENTAL, "web-token")
        val client = SpotifyClient(
            mockHttp(requests) { request ->
                if (request.text().contains("fetchLibraryTracks")) {
                    json("""{"data":{"me":{"library":{"tracks":{"totalCount":1,"items":[{"track":${gqlTrack("spotify:track:liked")}}]}}}}}""")
                } else {
                    json(
                        """{"data":{"me":{"libraryV3":{"totalCount":1,"items":[{"item":{"__typename":"AlbumResponseWrapper","_uri":"spotify:album:a1",
                           "data":{"name":"El Mal Querer","artists":{"items":[{"profile":{"name":"Rosalía"}}]},
                           "coverArt":{"sources":[{"url":"a1.jpg"}]},"date":{"isoString":"2018-11-02T00:00:00Z"}}}}]}}}}""",
                    )
                }
            },
        ) { auth }
        assertEquals(listOf("liked"), client.savedTracks().map { it.id })
        assertEquals(listOf(SpotifyAlbum("a1", "El Mal Querer", listOf("Rosalía"), "a1.jpg", 2018)), client.savedAlbums())
        assertTrue(requests.last().text().contains("\"filters\":[\"Albums\"]"))
    }

    @Test
    fun `experimental mode reports a clear error when every known hash is retired`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.EXPERIMENTAL, "web-token")
        val client = SpotifyClient(mockHttp(requests) { json("", HttpStatusCode.PreconditionFailed) }) { auth }
        val error = assertThrows<SpotifyApiException> { client.playlists() }
        assertTrue(error.message!!.contains("usa el modo oficial"))
        assertEquals(2, requests.size)
    }

    @Test
    fun `experimental 401 refreshes the web token once`() = runTest {
        val auth = FakeAuth(SpotifyAuthMode.EXPERIMENTAL, "stale", "fresh")
        val client = SpotifyClient(
            mockHttp(requests) { request ->
                if (request.bearer == "stale") json("", HttpStatusCode.Unauthorized)
                else json("""{"data":{"me":{"library":{"tracks":{"totalCount":0,"items":[]}}}}}""")
            },
        ) { auth }
        assertTrue(client.savedTracks().isEmpty())
        assertEquals(listOf("stale", "fresh"), requests.map { it.bearer })
    }
}
