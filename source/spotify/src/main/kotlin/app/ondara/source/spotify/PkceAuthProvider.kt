package app.ondara.source.spotify

import app.ondara.core.network.get
import app.ondara.core.network.long
import app.ondara.core.network.string
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.http.parseQueryString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * OAuth 2.0 Authorization Code + PKCE (S256) contra `accounts.spotify.com`. Sin client secret: es el flujo
 * que Spotify recomienda para apps móviles.
 *
 * Uso: [beginAuthorization] devuelve la URL que hay que abrir en el navegador; cuando Spotify redirige a
 * [redirectUri], la app pasa esa URI completa a [completeAuthorization]. A partir de ahí [accessToken]
 * entrega un token vigente y lo renueva solo con el `refresh_token`.
 *
 * @param clientId se lee en cada operación para que el usuario pueda cambiarlo en Ajustes sin recrear el objeto.
 * @param clock reloj en milisegundos; solo se sustituye en tests.
 */
class PkceAuthProvider(
    private val http: HttpClient,
    private val store: SpotifyTokenStore,
    private val clientId: () -> String,
    private val redirectUri: String = "ondara://spotify-callback",
    private val clock: () -> Long = System::currentTimeMillis,
) : SpotifyAuthProvider {
    private val mutex = Mutex()
    private val random = SecureRandom()

    override val mode: SpotifyAuthMode get() = SpotifyAuthMode.OFFICIAL

    override suspend fun isSignedIn(): Boolean = loadTokens() != null

    /**
     * Genera verifier/challenge/state, los guarda en el store y devuelve la URL de
     * `accounts.spotify.com/authorize` con los scopes de lectura de biblioteca.
     *
     * @throws SpotifyAuthException si no hay Client ID configurado.
     */
    suspend fun beginAuthorization(): String {
        val id = requireClientId()
        val verifier = random.randomString(VerifierLength, VerifierAlphabet)
        val state = random.randomString(StateLength, StateAlphabet)
        store.save(KeyPending, SpotifyJson.encodeToString(PendingAuthorization.serializer(), PendingAuthorization(verifier, state)))
        return URLBuilder(AuthorizeUrl).apply {
            parameters.append("client_id", id)
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", redirectUri)
            parameters.append("code_challenge_method", "S256")
            parameters.append("code_challenge", challengeFor(verifier))
            parameters.append("state", state)
            parameters.append("scope", Scopes)
        }.buildString()
    }

    /**
     * Recibe la URI completa del redirect (`ondara://spotify-callback?code=..&state=..` o `?error=..`),
     * valida `state` y canjea el `code` en `/api/token`.
     *
     * @throws SpotifyAuthException si la URI no corresponde a la autorización en curso, el usuario la
     * denegó o Spotify rechaza el canje.
     */
    suspend fun completeAuthorization(callbackUri: String) {
        val id = requireClientId()
        val (pending, code) = validateCallback(callbackUri)
        val (ok, body) = requestToken(
            parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("client_id", id)
                append("code_verifier", pending.verifier)
            },
        )
        val tokens = body.takeIf { ok }?.toTokens(previousRefreshToken = null)
            ?: throw SpotifyAuthException("Spotify rechazó el código de autorización${body.errorSuffix()}. Vuelve a iniciar sesión.")
        mutex.withLock { saveTokens(tokens) }
    }

    private suspend fun validateCallback(callbackUri: String): Pair<PendingAuthorization, String> {
        if (!callbackUri.substringBefore('?').trimEnd('/').equals(redirectUri.trimEnd('/'), ignoreCase = true)) {
            throw SpotifyAuthException("La respuesta no viene del inicio de sesión de Spotify.")
        }
        val params = parseQueryString(callbackUri.substringAfter('?', "").substringBefore('#'))
        val pending = store.load(KeyPending)
            ?.let { runCatching { SpotifyJson.decodeFromString(PendingAuthorization.serializer(), it) }.getOrNull() }
            ?: throw SpotifyAuthException("No hay ningún inicio de sesión de Spotify en curso. Vuelve a empezar.")
        if (!sameText(pending.state, params["state"].orEmpty())) {
            throw SpotifyAuthException("La respuesta de Spotify no coincide con la solicitud (state inválido). Vuelve a intentarlo.")
        }
        store.save(KeyPending, null)
        rejectAuthorizationError(params["error"])
        val code = params["code"]?.takeIf { it.isNotBlank() }
            ?: throw SpotifyAuthException("Spotify no devolvió el código de autorización.")
        return pending to code
    }

    private fun rejectAuthorizationError(error: String?) {
        if (error != null) throw SpotifyAuthException(
            if (error == "access_denied") "Cancelaste el acceso a tu cuenta de Spotify." else "Spotify rechazó el inicio de sesión ($error).",
        )
    }

    override suspend fun accessToken(): String = mutex.withLock {
        val tokens = loadTokens() ?: throw SpotifyAuthException("No has iniciado sesión en Spotify.")
        if (tokens.expiresAt - ExpiryMarginMs > clock()) tokens.accessToken else refresh(tokens).accessToken
    }

    override suspend fun invalidateAccessToken() = mutex.withLock {
        loadTokens()?.let { saveTokens(it.copy(expiresAt = 0)) }
        Unit
    }

    override suspend fun signOut() = mutex.withLock {
        store.save(KeyTokens, null)
        store.save(KeyPending, null)
    }

    private suspend fun refresh(current: StoredTokens): StoredTokens {
        val refreshToken = current.refreshToken ?: run {
            store.save(KeyTokens, null)
            throw SpotifyAuthException(MsgSessionExpired)
        }
        val (ok, body) = requestToken(
            parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", requireClientId())
            },
        )
        val error = body["error"].string
        if (!ok && (error == "invalid_grant" || error == "invalid_client")) {
            // El refresh token fue revocado o pertenece a otro Client ID: la sesión ya no es recuperable.
            store.save(KeyTokens, null)
            throw SpotifyAuthException(MsgSessionExpired)
        }
        val tokens = body.takeIf { ok }?.toTokens(previousRefreshToken = refreshToken)
            ?: throw SpotifyAuthException("Spotify no pudo renovar la sesión${body.errorSuffix()}. Inténtalo de nuevo.")
        saveTokens(tokens)
        return tokens
    }

    /** POST a `/api/token`. Devuelve si fue 2xx y el cuerpo JSON (también en errores, que traen `error`). */
    private suspend fun requestToken(form: Parameters): Pair<Boolean, JsonElement?> = try {
        val response = http.submitForm(TokenUrl, form) { expectSuccess = false }
        response.status.isSuccess() to response.jsonOrNull()
    } catch (e: IOException) {
        throw SpotifyAuthException(MsgNoConnection, e)
    }

    private fun JsonElement.toTokens(previousRefreshToken: String?): StoredTokens? {
        val access = this["access_token"].string?.takeIf { it.isNotBlank() } ?: return null
        return StoredTokens(
            accessToken = access,
            // Spotify puede rotar el refresh token o no devolverlo; en ese caso sigue valiendo el anterior.
            refreshToken = this["refresh_token"].string?.takeIf { it.isNotBlank() } ?: previousRefreshToken,
            expiresAt = clock() + (this["expires_in"].long ?: DefaultExpiresInSec) * 1000,
        )
    }

    private fun JsonElement?.errorSuffix(): String =
        (this["error_description"].string ?: this["error"].string)?.let { " ($it)" }.orEmpty()

    private fun requireClientId(): String = clientId().trim().ifEmpty {
        throw SpotifyAuthException(
            "Falta el Client ID de Spotify. Crea una app en developer.spotify.com y pega su Client ID en Ajustes.",
        )
    }

    private suspend fun loadTokens(): StoredTokens? =
        store.load(KeyTokens)?.let { runCatching { SpotifyJson.decodeFromString(StoredTokens.serializer(), it) }.getOrNull() }

    private suspend fun saveTokens(tokens: StoredTokens) =
        store.save(KeyTokens, SpotifyJson.encodeToString(StoredTokens.serializer(), tokens))

    @Serializable
    private data class PendingAuthorization(val verifier: String, val state: String)

    @Serializable
    private data class StoredTokens(val accessToken: String, val refreshToken: String?, val expiresAt: Long)

    companion object {
        /** Scopes de solo lectura que necesita la importación. */
        const val Scopes = "playlist-read-private playlist-read-collaborative user-library-read user-read-private"

        internal const val KeyTokens = "spotify.pkce.tokens"
        internal const val KeyPending = "spotify.pkce.pending"
        private const val AuthorizeUrl = "https://accounts.spotify.com/authorize"
        private const val TokenUrl = "https://accounts.spotify.com/api/token"
        private const val VerifierLength = 64
        private const val StateLength = 24
        private const val DefaultExpiresInSec = 3600L
        private const val StateAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /** Caracteres no reservados que RFC 7636 §4.1 admite en el `code_verifier`. */
        private const val VerifierAlphabet = "$StateAlphabet-._~"
        private const val MsgSessionExpired = "Tu sesión de Spotify caducó. Vuelve a iniciar sesión."

        /** `code_challenge` S256 de RFC 7636: base64url sin relleno del SHA-256 del verifier. O(n). */
        internal fun challengeFor(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** Comparación en tiempo constante, para no filtrar el `state` por temporización. O(n). */
        private fun sameText(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
