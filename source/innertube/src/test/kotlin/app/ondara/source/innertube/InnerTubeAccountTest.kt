package app.ondara.source.innertube

import app.ondara.core.model.MusicItem
import app.ondara.core.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Endpoints de cuenta y de novedades.
 *
 * `new_releases`, `explore` y `account_menu_signed_out` son respuestas reales grabadas (hl=es, gl=CO);
 * el resto de fixtures de cuenta están escritos a mano siguiendo la estructura que documentan
 * ytmusicapi y Metrolist, porque exigen una sesión real.
 */
class InnerTubeAccountTest {
    private val cookie = "SID=x; SAPISID=abc123; __Secure-3PAPISID=abc123"

    private fun fixtureText(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name.json")) { "falta el fixture $name" }.readText()

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(fixtureText(name)).jsonObject

    private fun MockRequestHandleScope.json(text: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun client(
        cookie: String = this.cookie,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = InnerTubeClient(HttpClientFactory.create(MockEngine(handler))) { InnerTubeConfig("es", "CO", cookie) }

    private suspend fun HttpRequestData.bodyJson(): JsonObject = Json.parseToJsonElement(String(body.toByteArray())).jsonObject

    // ---- Parseo --------------------------------------------------------------------------------

    @Test
    fun `new releases yield titled shelves with albums and videos`() {
        val shelves = InnerTubeParser.newReleases(fixture("new_releases"))
        assertTrue(shelves.size >= 2)
        assertTrue(shelves.all { it.title.isNotBlank() && it.items.isNotEmpty() })
        assertTrue(shelves.any { s -> s.items.size >= 10 && s.items.all { it is MusicItem.AlbumItem } })
        assertTrue(shelves.any { s -> s.items.size >= 10 && s.items.all { it is MusicItem.SongItem } })
        val albums = shelves.flatMap { it.items }.filterIsInstance<MusicItem.AlbumItem>().map { it.album }
        assertTrue(albums.all { it.id.startsWith("MPRE") && it.thumbnailUrl != null && it.artists.isNotEmpty() })
    }

    @Test
    fun `explore yields new albums, trending songs and videos but no mood buttons`() {
        val shelves = InnerTubeParser.shelves(fixture("explore"))
        assertTrue(shelves.size >= 3)
        assertTrue(shelves.all { it.title.isNotBlank() && it.items.isNotEmpty() })
        assertTrue(shelves.any { s -> s.items.all { it is MusicItem.AlbumItem } })
        val songs = shelves.flatMap { it.items }.filterIsInstance<MusicItem.SongItem>().map { it.song }
        assertTrue(songs.size >= 20)
        assertTrue(songs.all { it.id.length == 11 && it.title.isNotBlank() && it.artists.isNotEmpty() })
    }

    @Test
    fun `account info is read from the active account header`() {
        val info = InnerTubeParser.accountInfo(fixture("account_menu"))
        assertEquals(YouTubeAccountInfo("Ana Pérez", "@anaperez", "https://yt3.ggpht.com/abcDEF123=s108-c-k-c0x00ffffff-no-rj"), info)
    }

    @Test
    fun `signed out account menu yields no account`() {
        assertNull(InnerTubeParser.accountInfo(fixture("account_menu_signed_out")))
    }

    @Test
    fun `library playlists skip the new playlist card and strip VL`() {
        val (playlists, token) = InnerTubeParser.libraryPlaylists(fixture("library_playlists"))
        assertEquals(listOf("LM", "SE", "PLx1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "PLx2bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), playlists.map { it.id })
        assertTrue(playlists.none { it.title == "Nueva playlist" })
        val running = playlists[2]
        assertEquals("Para correr", running.title)
        assertEquals("Ana Pérez", running.author)
        assertEquals(34, running.songCount)
        assertNotNull(running.thumbnailUrl)
        assertEquals(1204, playlists[3].songCount)
        assertNull(playlists[0].songCount)
        assertNotNull(token)
    }

    @Test
    fun `library continuation is parsed in both formats`() {
        val (legacy, legacyToken) = InnerTubeParser.libraryPlaylists(fixture("library_playlists_continuation"))
        assertEquals(listOf("Salsa clásica", "Éxitos de Colombia"), legacy.map { it.title })
        assertEquals(1, legacy.first().songCount)
        assertNotNull(legacyToken)
        val (modern, modernToken) = InnerTubeParser.libraryPlaylists(fixture("library_playlists_continuation_items"))
        assertEquals("Vallenato", modern.first().title)
        assertNull(modernToken)
    }

    @Test
    fun `history flattens the day shelves without duplicates`() {
        val songs = InnerTubeParser.history(fixture("history"))
        assertEquals(listOf("5NV6Rdv1a3I", "fJ9rUzIMcZQ", "kJQP7kiw5Fk"), songs.map { it.id })
        val first = songs.first()
        assertEquals("Get Lucky", first.title)
        assertEquals("Daft Punk", first.artistLine)
        assertEquals("Random Access Memories", first.album?.title)
        assertEquals(370, first.durationSec)
        assertTrue(songs.last().explicit)
    }

    @Test
    fun `signed out pages yield empty lists instead of failing`() {
        // Respuesta real (recortada) de FEmusic_history / FEmusic_liked_playlists sin sesión: solo un mensaje.
        val signedOut = Json.parseToJsonElement(
            """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
               {"itemSectionRenderer":{"contents":[{"messageRenderer":{"text":{"runs":[{"text":"Inicia sesión para ver tu historial"}]}}}]}}]}}}}]}}}""",
        ).jsonObject
        assertTrue(InnerTubeParser.history(signedOut).isEmpty())
        assertEquals(emptyList<Any>() to null, InnerTubeParser.libraryPlaylists(signedOut))
    }

    // ---- Transporte ----------------------------------------------------------------------------

    @Test
    fun `authenticated requests carry the session headers`() = runTest {
        var request: HttpRequestData? = null
        val info = client { request = it; json(fixtureText("account_menu")) }.accountInfo()
        assertEquals("Ana Pérez", info?.name)
        val sent = checkNotNull(request)
        assertEquals("https://music.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false", sent.url.toString())
        assertEquals(cookie, sent.headers["Cookie"])
        assertTrue(sent.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
        assertEquals("0", sent.headers["X-Goog-AuthUser"])
        assertEquals("https://music.youtube.com", sent.headers["X-Origin"])
    }

    @Test
    fun `anonymous requests carry no session headers`() = runTest {
        var request: HttpRequestData? = null
        val shelves = client(cookie = "") { request = it; json(fixtureText("explore")) }.explore()
        assertTrue(shelves.isNotEmpty())
        val sent = checkNotNull(request)
        assertTrue(String(sent.body.toByteArray()).contains("\"browseId\":\"FEmusic_explore\""))
        listOf("Cookie", "Authorization", "X-Goog-AuthUser", "X-Origin").forEach { assertNull(sent.headers[it], it) }
    }

    @Test
    fun `player clients other than web never receive the cookie`() = runTest {
        var request: HttpRequestData? = null
        client { request = it; json("{}") }.player("5NV6Rdv1a3I", YouTubeClient.ANDROID)
        listOf("Cookie", "Authorization", "X-Goog-AuthUser").forEach { assertNull(checkNotNull(request).headers[it], it) }
    }

    @Test
    fun `account info without cookie makes no request`() = runTest {
        var calls = 0
        assertNull(client(cookie = "") { calls++; json(fixtureText("account_menu")) }.accountInfo())
        assertEquals(0, calls)
    }

    @Test
    fun `account info returns null on 401 and 403`() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            assertNull(client { json("""{"error":{"code":${status.value}}}""", status) }.accountInfo())
        }
        assertNull(client { json(fixtureText("account_menu_signed_out")) }.accountInfo())
    }

    @Test
    fun `explicit cookie overrides the configured one`() = runTest {
        var request: HttpRequestData? = null
        val other = "SAPISID=other999"
        val info = client(cookie = "") { request = it; json(fixtureText("account_menu")) }.accountInfo(other)
        assertEquals("@anaperez", info?.handle)
        assertEquals(other, checkNotNull(request).headers["Cookie"])
        val now = checkNotNull(request).headers["Authorization"]!!.substringAfter(' ').substringBefore('_').toLong()
        assertEquals(InnerTubeClient.sapisidHash(other, "https://music.youtube.com", now), checkNotNull(request).headers["Authorization"])
    }

    @Test
    fun `rate posts the target to like and removelike`() = runTest {
        val seen = ArrayList<Pair<String, JsonObject>>()
        val api = client { seen += it.url.encodedPath to it.bodyJson(); json("{}") }
        assertTrue(api.rate("5NV6Rdv1a3I", LikeStatus.LIKE))
        assertTrue(api.rate("5NV6Rdv1a3I", LikeStatus.INDIFFERENT))
        assertEquals(listOf("/youtubei/v1/like/like", "/youtubei/v1/like/removelike"), seen.map { it.first })
        seen.forEach { (_, body) ->
            assertEquals("""{"videoId":"5NV6Rdv1a3I"}""", body["target"].toString())
            assertNotNull(body["context"])
        }
    }

    @Test
    fun `rate returns false when the server rejects it`() = runTest {
        assertFalse(client(cookie = "") { json("""{"error":{"code":401}}""", HttpStatusCode.Unauthorized) }.rate("5NV6Rdv1a3I", LikeStatus.LIKE))
    }

    @Test
    fun `library playlists follow continuations and drop non playlists`() = runTest {
        val bodies = ArrayList<JsonObject>()
        val api = client {
            val body = it.bodyJson()
            bodies += body
            when (body["continuation"]?.toString()?.trim('"')) {
                null -> json(fixtureText("library_playlists"))
                "4qmFsgLIBRIYRkVtdXNpY19saWtlZF9wbGF5bGlzdHM_page2" -> json(fixtureText("library_playlists_continuation"))
                else -> json(fixtureText("library_playlists_continuation_items"))
            }
        }
        val playlists = api.libraryPlaylists()
        assertEquals(3, bodies.size)
        assertEquals("\"FEmusic_liked_playlists\"", bodies[0]["browseId"].toString())
        assertEquals("\"4qmFsgLIBRIYRkVtdXNpY19saWtlZF9wbGF5bGlzdHM_page3\"", bodies[2]["continuation"].toString())
        // 4 + 2 + 2, menos "Para correr" repetida en la última página.
        assertEquals(7, playlists.size)
        assertEquals(playlists.size, playlists.map { it.id }.distinct().size)
        assertTrue(playlists.none { it.id.startsWith("VL") || it.title == "Nueva playlist" })
        assertEquals("Vallenato", playlists.last().title)
    }

    @Test
    fun `liked songs browse the LM playlist and respect the limit`() = runTest {
        var body: JsonObject? = null
        val songs = client { body = it.bodyJson(); json(fixtureText("playlist")) }.likedSongs(maxSongs = 5)
        assertEquals("\"VLLM\"", checkNotNull(body)["browseId"].toString())
        assertEquals(5, songs.size)
    }

    @Test
    fun `account history browses FEmusic_history`() = runTest {
        var body: JsonObject? = null
        val songs = client { body = it.bodyJson(); json(fixtureText("history")) }.accountHistory()
        assertEquals("\"FEmusic_history\"", checkNotNull(body)["browseId"].toString())
        assertEquals(3, songs.size)
    }
}
