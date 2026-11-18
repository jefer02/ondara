package app.ondara.source.spotify

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.util.Base64

class PkceAuthProviderTest {
    private val store = MemoryStore()
    private val requests = ArrayList<HttpRequestData>()
    private var now = 1_000_000L
    private var clientId = "client-123"

    private fun provider(tokenResponses: List<Pair<HttpStatusCode, String>> = emptyList()): PkceAuthProvider {
        val queue = ArrayDeque(tokenResponses)
        val http = mockHttp(requests) { json(queue.first().second, queue.removeFirst().first) }
        return PkceAuthProvider(http, store, { clientId }, clock = { now })
    }

    private fun tokenJson(access: String, refresh: String? = "refresh-1", expiresIn: Int = 3600) =
        """{"access_token":"$access","token_type":"Bearer","expires_in":$expiresIn""" + (refresh?.let { ""","refresh_token":"$it"}""" } ?: "}")

    private fun pending(field: String): String = Regex("\"$field\":\"([^\"]+)\"").find(store.values.getValue(PkceAuthProvider.KeyPending))!!.groupValues[1]

    @Test
    fun `challenge follows the RFC 7636 appendix B vector`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", PkceAuthProvider.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun `authorization url carries a verifiable S256 challenge, state and scopes`() = runTest {
        val url = Url(provider().beginAuthorization())
        assertEquals("accounts.spotify.com", url.host)
        assertEquals("/authorize", url.encodedPath)
        val q = url.parameters
        assertEquals("client-123", q["client_id"])
        assertEquals("code", q["response_type"])
        assertEquals("ondara://spotify-callback", q["redirect_uri"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("playlist-read-private playlist-read-collaborative user-library-read user-read-private", q["scope"])

        val verifier = pending("verifier")
        assertEquals(64, verifier.length)
        assertTrue(verifier.matches(Regex("[A-Za-z0-9._~-]+")))
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        assertEquals(expected, q["code_challenge"])
        assertFalse(q["code_challenge"]!!.contains('='))
        assertEquals(pending("state"), q["state"])

        // Cada intento genera secretos nuevos.
        provider().beginAuthorization()
        assertNotEquals(verifier, pending("verifier"))
    }

    @Test
    fun `callback exchanges the code with the stored verifier and persists the tokens`() = runTest {
        val auth = provider(listOf(HttpStatusCode.OK to tokenJson("access-1")))
        assertFalse(auth.isSignedIn())
        val state = Url(auth.beginAuthorization()).parameters["state"]
        val verifier = pending("verifier")

        auth.completeAuthorization("ondara://spotify-callback?code=the-code&state=$state")

        val request = requests.single()
        assertEquals("https://accounts.spotify.com/api/token", request.url.toString())
        val form = request.form()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-code", form["code"])
        assertEquals("ondara://spotify-callback", form["redirect_uri"])
        assertEquals("client-123", form["client_id"])
        assertEquals(verifier, form["code_verifier"])
        assertFalse(form.contains("client_secret"))

        assertTrue(auth.isSignedIn())
        assertEquals("access-1", auth.accessToken())
        assertEquals(1, requests.size)
        assertFalse(store.values.containsKey(PkceAuthProvider.KeyPending))
        assertTrue(store.values.getValue(PkceAuthProvider.KeyTokens).contains("refresh-1"))
    }

    @Test
    fun `invalid state is rejected without calling spotify`() = runTest {
        val auth = provider()
        auth.beginAuthorization()
        val error = assertThrows<SpotifyAuthException> { auth.completeAuthorization("ondara://spotify-callback?code=c&state=forged") }
        assertTrue(error.message!!.contains("state"))
        assertThrows<SpotifyAuthException> { auth.completeAuthorization("ondara://spotify-callback?code=c") }
        assertThrows<SpotifyAuthException> { auth.completeAuthorization("https://evil.example/?code=c&state=${pending("state")}") }
        assertTrue(requests.isEmpty())
        assertFalse(auth.isSignedIn())
    }

    @Test
    fun `callback without a pending authorization is rejected`() = runTest {
        assertThrows<SpotifyAuthException> { provider().completeAuthorization("ondara://spotify-callback?code=c&state=s") }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `access denied surfaces a clear error`() = runTest {
        val auth = provider()
        val state = Url(auth.beginAuthorization()).parameters["state"]
        val error = assertThrows<SpotifyAuthException> { auth.completeAuthorization("ondara://spotify-callback?error=access_denied&state=$state") }
        assertTrue(error.message!!.contains("Cancelaste"))
        assertTrue(requests.isEmpty())
        assertFalse(auth.isSignedIn())
    }

    @Test
    fun `rejected code exchange reports spotify's reason`() = runTest {
        val auth = provider(listOf(HttpStatusCode.BadRequest to """{"error":"invalid_grant","error_description":"Invalid authorization code"}"""))
        val state = Url(auth.beginAuthorization()).parameters["state"]
        val error = assertThrows<SpotifyAuthException> { auth.completeAuthorization("ondara://spotify-callback?code=c&state=$state") }
        assertTrue(error.message!!.contains("Invalid authorization code"))
        assertFalse(auth.isSignedIn())
    }

    @Test
    fun `expired token is refreshed with the refresh token and client id`() = runTest {
        val auth = provider(
            listOf(
                HttpStatusCode.OK to tokenJson("access-1", "refresh-1", expiresIn = 3600),
                HttpStatusCode.OK to tokenJson("access-2", refresh = null),
                HttpStatusCode.OK to tokenJson("access-3", "refresh-2"),
            ),
        )
        val state = Url(auth.beginAuthorization()).parameters["state"]
        auth.completeAuthorization("ondara://spotify-callback?code=c&state=$state")

        now += 3_500_000 // dentro del margen de seguridad todavía no caduca…
        assertEquals("access-1", auth.accessToken())
        now += 50_000 // …a menos de un minuto del final ya se renueva
        assertEquals("access-2", auth.accessToken())
        assertEquals("access-2", auth.accessToken())
        assertEquals(2, requests.size)
        val form = requests[1].form()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("refresh-1", form["refresh_token"])
        assertEquals("client-123", form["client_id"])

        // Spotify no devolvió refresh token nuevo: se conserva el anterior. Invalidar fuerza otra renovación.
        auth.invalidateAccessToken()
        assertEquals("access-3", auth.accessToken())
        assertEquals("refresh-1", requests[2].form()["refresh_token"])
        assertTrue(store.values.getValue(PkceAuthProvider.KeyTokens).contains("refresh-2"))
    }

    @Test
    fun `revoked refresh token ends the session`() = runTest {
        val auth = provider(
            listOf(
                HttpStatusCode.OK to tokenJson("access-1", expiresIn = 1),
                HttpStatusCode.BadRequest to """{"error":"invalid_grant","error_description":"Refresh token revoked"}""",
            ),
        )
        val state = Url(auth.beginAuthorization()).parameters["state"]
        auth.completeAuthorization("ondara://spotify-callback?code=c&state=$state")
        val error = assertThrows<SpotifyAuthException> { auth.accessToken() }
        assertTrue(error.message!!.contains("caducó"))
        assertFalse(auth.isSignedIn())
    }

    @Test
    fun `temporary refresh failure keeps the session`() = runTest {
        val auth = provider(
            listOf(
                HttpStatusCode.OK to tokenJson("access-1", expiresIn = 1),
                HttpStatusCode.TooManyRequests to "",
                HttpStatusCode.OK to tokenJson("access-2"),
            ),
        )
        val state = Url(auth.beginAuthorization()).parameters["state"]
        auth.completeAuthorization("ondara://spotify-callback?code=c&state=$state")
        assertThrows<SpotifyAuthException> { auth.accessToken() }
        assertTrue(auth.isSignedIn())
        assertEquals("access-2", auth.accessToken())
    }

    @Test
    fun `missing client id fails with a clear message`() = runTest {
        clientId = "  "
        val error = assertThrows<SpotifyAuthException> { provider().beginAuthorization() }
        assertTrue(error.message!!.contains("Client ID"))
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `sign out wipes everything and access token then fails`() = runTest {
        val auth = provider(listOf(HttpStatusCode.OK to tokenJson("access-1")))
        val state = Url(auth.beginAuthorization()).parameters["state"]
        auth.completeAuthorization("ondara://spotify-callback?code=c&state=$state")
        auth.beginAuthorization()
        auth.signOut()
        assertTrue(store.values.isEmpty())
        assertFalse(auth.isSignedIn())
        assertThrows<SpotifyAuthException> { auth.accessToken() }
        assertEquals(SpotifyAuthMode.OFFICIAL, auth.mode)
    }
}
