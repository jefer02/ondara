package app.ondara.source.spotify

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.security.SecureRandom

/** Persistencia de secretos (la implementa la app con Android Keystore). Valores = JSON/cadenas opacas. */
interface SpotifyTokenStore {
    suspend fun load(key: String): String?

    /** Guarda [value] bajo [key]; `null` borra la entrada. */
    suspend fun save(key: String, value: String?)
}

/** Cómo se obtiene el token: OAuth oficial (PKCE) o la sesión del reproductor web (frágil). */
enum class SpotifyAuthMode { OFFICIAL, EXPERIMENTAL }

/** Fallo de autenticación con Spotify. El mensaje está en español y se puede mostrar al usuario. */
class SpotifyAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Fallo de la API de Spotify ajeno a la autenticación. [status] es el código HTTP (0 si no hubo respuesta). */
class SpotifyApiException(val status: Int, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Patrón Strategy: cada modo de inicio de sesión sabe entregar un token de acceso vigente. */
interface SpotifyAuthProvider {
    val mode: SpotifyAuthMode

    suspend fun isSignedIn(): Boolean

    /** Token de acceso vigente (lo renueva si caducó). @throws SpotifyAuthException */
    suspend fun accessToken(): String

    suspend fun signOut()

    /**
     * Marca el token actual como inservible para que la siguiente llamada a [accessToken] pida uno nuevo.
     * [SpotifyClient] lo invoca cuando Spotify responde 401 con un token que en teoría seguía vigente.
     */
    suspend fun invalidateAccessToken() = Unit
}

/** Margen con el que un token se considera caducado antes de su hora real, para no usarlo al límite. */
internal const val ExpiryMarginMs = 60_000L

internal const val MsgNoConnection = "No se pudo conectar con Spotify. Revisa tu conexión e inténtalo de nuevo."

internal val SpotifyJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

/** Cuerpo de la respuesta como JSON, o null si viene vacío o no es JSON (páginas de error HTML, etc.). */
internal suspend fun HttpResponse.jsonOrNull(): JsonElement? =
    runCatching { SpotifyJson.parseToJsonElement(bodyAsText()) }.getOrNull()

/** Cadena aleatoria criptográfica de [length] caracteres tomados de [alphabet]. O(length). */
internal fun SecureRandom.randomString(length: Int, alphabet: String): String =
    buildString(length) { repeat(length) { append(alphabet[nextInt(alphabet.length)]) } }
