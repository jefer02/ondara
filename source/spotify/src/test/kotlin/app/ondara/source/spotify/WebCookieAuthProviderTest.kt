package app.ondara.source.spotify

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WebCookieAuthProviderTest {
    private val store = MemoryStore()
    private val requests = ArrayList<HttpRequestData>()
    private var now = 1_700_000_000_000L
    private val serverTime = 1_794_851_830L
    private val remoteSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" // Base32 de "12345678901234567890"

    /** Respuestas configurables de cada endpoint del reproductor web. */
    private var gist: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """[{"s":"OLD","v":60},{"s":"$remoteSecret","v":62}]"""
    private var serverTimeStatus = HttpStatusCode.OK
    private var token: (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to tokenJson("web-1") }

    private fun tokenJson(access: String, anonymous: Boolean = false, expiresAt: Long = now + 3_600_000) =
        """{"clientId":"x","accessToken":"$access","accessTokenExpirationTimestampMs":$expiresAt,"isAnonymous":$anonymous}"""

    private fun provider() = WebCookieAuthProvider(
        mockHttp(requests) { request ->
            when {
                request.url.host == "gist.githubusercontent.com" -> json(gist.second, gist.first)
                request.url.encodedPath == "/api/server-time" -> json("""{"serverTime":$serverTime}""", serverTimeStatus)
                request.url.encodedPath == "/api/token" -> token(request).let { json(it.second, it.first) }
                request.url.host == "clienttoken.spotify.com" ->
                    json("""{"response_type":"RESPONSE_GRANTED_TOKEN_RESPONSE","granted_token":{"token":"client-tok","refresh_after_seconds":1200}}""")
                else -> error("petición inesperada: ${request.url}")
            }
        },
        store,
        clock = { now },
    )

    private fun tokenRequests() = requests.filter { it.url.encodedPath == "/api/token" }

    @Test
    fun `totp matches the RFC 6238 SHA-1 vectors`() {
        // Los vectores del RFC son de 8 dígitos; con 6 dígitos se conservan los 6 últimos.
        assertEquals("287082", SpotifyTotp.code(remoteSecret, 59))
        assertEquals("081804", SpotifyTotp.code(remoteSecret, 1_111_111_109))
        assertEquals("005924", SpotifyTotp.code(remoteSecret, 1_234_567_890))
        assertEquals("12345678901234567890", String(SpotifyTotp.base32Decode("gezdgnbvgy3tqojqgezdgnbvgy3tqojq====")))
    }

    @Test
    fun `sign in sends cookie and totp from the newest remote secret and stores the session`() = runTest {
        val auth = provider()
        assertFalse(auth.isSignedIn())

        auth.signInWithCookie("  sp_dc=AQB-cookie; sp_key=zzz ")

        val request = tokenRequests().single()
        val expectedCode = SpotifyTotp.code(remoteSecret, serverTime)
        assertEquals("open.spotify.com", request.url.host)
        assertEquals(expectedCode, request.url.parameters["totp"])
        assertEquals(expectedCode, request.url.parameters["totpServer"])
        assertEquals("62", request.url.parameters["totpVer"])
        assertEquals("web-player", request.url.parameters["productType"])
        assertEquals("sp_dc=AQB-cookie", request.headers["Cookie"])
        assertTrue(request.headers["User-Agent"]!!.startsWith("Mozilla/5.0"))

        assertTrue(auth.isSignedIn())
        assertEquals("AQB-cookie", store.values[WebCookieAuthProvider.KeyCookie])
        assertEquals("web-1", auth.accessToken())
        assertEquals(1, tokenRequests().size) // el token recién obtenido se reutiliza
        assertEquals(SpotifyAuthMode.EXPERIMENTAL, auth.mode)
    }

    @Test
    fun `embedded secret and local clock are used when the remote sources fail`() = runTest {
        gist = HttpStatusCode.NotFound to "Not Found"
        serverTimeStatus = HttpStatusCode.NotFound
        provider().signInWithCookie("cookie")
        val request = tokenRequests().single()
        assertEquals(WebPlayerSecrets.Embedded.version.toString(), request.url.parameters["totpVer"])
        assertEquals(SpotifyTotp.code(WebPlayerSecrets.Embedded.secret, now / 1000), request.url.parameters["totp"])
    }

    @Test
    fun `falls back to the embedded secret when spotify rejects the remote one`() = runTest {
        token = { request -> if (request.url.parameters["totpVer"] == "62") HttpStatusCode.BadRequest to """{"error":"totp"}""" else HttpStatusCode.OK to tokenJson("web-1") }
        val auth = provider()
        auth.signInWithCookie("cookie")
        assertEquals(listOf("62", "61"), tokenRequests().map { it.url.parameters["totpVer"] })
        assertEquals("web-1", auth.accessToken())
    }

    @Test
    fun `last downloaded secret survives a later outage of the remote source`() = runTest {
        provider().signInWithCookie("cookie")
        gist = HttpStatusCode.NotFound to ""
        requests.clear()
        val restarted = provider() // proceso nuevo: sin caché en memoria
        now += 4_000_000
        assertEquals("web-1", restarted.accessToken())
        assertEquals("62", tokenRequests().single().url.parameters["totpVer"])
    }

    @Test
    fun `anonymous token means the cookie is invalid and nothing is stored`() = runTest {
        token = { HttpStatusCode.OK to tokenJson("anon", anonymous = true) }
        val auth = provider()
        val error = assertThrows<SpotifyAuthException> { auth.signInWithCookie("caducada") }
        assertTrue(error.message!!.contains("sp_dc"))
        assertFalse(auth.isSignedIn())
        assertNull(store.values[WebCookieAuthProvider.KeyToken])
    }

    @Test
    fun `rejection with every secret tells the user to switch to the official mode`() = runTest {
        token = { HttpStatusCode.BadRequest to """{"error":"invalid totp"}""" }
        val auth = provider()
        val error = assertThrows<SpotifyAuthException> { auth.signInWithCookie("cookie") }
        assertTrue(error.message!!.startsWith("Spotify cambió su inicio de sesión web; usa el modo oficial"))
        assertEquals(2, tokenRequests().size)
        assertFalse(auth.isSignedIn())

        token = { HttpStatusCode.OK to """<html>challenge</html>""" }
        assertThrows<SpotifyAuthException> { auth.signInWithCookie("cookie") }
    }

    @Test
    fun `blank cookie is rejected locally`() = runTest {
        assertThrows<SpotifyAuthException> { provider().signInWithCookie("  ") }
        assertTrue(requests.isEmpty())
        assertEquals("abc", WebCookieAuthProvider.cookieValue("\"abc\""))
        assertEquals("abc", WebCookieAuthProvider.cookieValue("other=1; sp_dc=abc"))
    }

    @Test
    fun `expired or invalidated token is fetched again with the stored cookie`() = runTest {
        var issued = 0
        token = { HttpStatusCode.OK to tokenJson("web-${++issued}") }
        val auth = provider()
        auth.signInWithCookie("cookie")
        assertEquals("web-1", auth.accessToken())

        now += 3_590_000 // dentro del último minuto de vida
        assertEquals("web-2", auth.accessToken())
        assertEquals("web-2", auth.accessToken())

        auth.invalidateAccessToken()
        assertEquals("web-3", auth.accessToken())
        assertEquals(3, tokenRequests().size)
        assertTrue(tokenRequests().all { it.headers["Cookie"] == "sp_dc=cookie" })
        // La fuente de secretos se consulta una sola vez mientras su caché siga fresca.
        assertEquals(1, requests.count { it.url.host == "gist.githubusercontent.com" })
    }

    @Test
    fun `client token is fetched once and cached`() = runTest {
        val auth = provider()
        assertEquals("client-tok", auth.clientToken())
        assertEquals("client-tok", auth.clientToken())
        val request = requests.single()
        assertEquals("https://clienttoken.spotify.com/v1/clienttoken", request.url.toString())
        assertTrue(request.text().contains("\"client_id\":\"${WebPlayer.ClientId}\""))
        now += 1_201_000
        auth.clientToken()
        assertEquals(2, requests.size)
    }

    @Test
    fun `client sends the client token alongside the web token`() = runTest {
        val auth = provider()
        auth.signInWithCookie("cookie")
        val pathfinder = ArrayList<HttpRequestData>()
        val client = SpotifyClient(mockHttp(pathfinder) { json("""{"data":{"me":{"library":{"tracks":{"totalCount":0,"items":[]}}}}}""") }) { auth }
        client.savedTracks()
        assertEquals("web-1", pathfinder.single().bearer)
        assertEquals("client-tok", pathfinder.single().headers["client-token"])
    }

    @Test
    fun `sign out forgets cookie and token`() = runTest {
        val auth = provider()
        auth.signInWithCookie("cookie")
        auth.signOut()
        assertFalse(auth.isSignedIn())
        assertNull(store.values[WebCookieAuthProvider.KeyCookie])
        assertNull(store.values[WebCookieAuthProvider.KeyToken])
        assertThrows<SpotifyAuthException> { auth.accessToken() }
    }
}
