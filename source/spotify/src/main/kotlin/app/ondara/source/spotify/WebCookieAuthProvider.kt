/*
 * Flujo de token del reproductor web de Spotify (cookie `sp_dc` + TOTP). Adaptado de:
 *
 *  - Echo Music (GPL-3.0), `spotify/SpotifyAuth.kt`: secreto TOTP publicado en un Gist comunitario,
 *    hora del servidor y parámetros de `/api/token`.
 *    https://github.com/EchoMusicApp/Echo-Music/blob/main/app/src/main/kotlin/com/music/echo/spotify/SpotifyAuth.kt
 *  - Metrolist (GPL-3.0), `meow/nyx/spotlib/Auth.kt` y `Totp.kt`: secreto embebido y `client-token`.
 *    https://github.com/MetrolistGroup/Metrolist/tree/main/shared/src/jvmSharedMain/kotlin/meow/nyx/spotlib
 *
 * Ambos se basan en el trabajo de sonic-liberation (https://github.com/sonic-liberation/spotube-plugin-spotify).
 */
package app.ondara.source.spotify

import app.ondara.core.network.array
import app.ondara.core.network.bool
import app.ondara.core.network.get
import app.ondara.core.network.int
import app.ondara.core.network.long
import app.ondara.core.network.path
import app.ondara.core.network.string
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Modo experimental: cookie de sesión `sp_dc` del reproductor web → token de acceso del web player.
 *
 * No necesita Client ID, pero depende de detalles internos de `open.spotify.com` que Spotify cambia sin
 * avisar: desde 2025 `/api/token` exige un TOTP derivado de un secreto que rota cada pocas semanas. Por eso
 * el secreto se refresca desde [WebPlayerSecrets.RemoteUrl] y solo si eso falla se usa el embebido.
 *
 * El token resultante **no** sirve contra `api.spotify.com/v1`; [SpotifyClient] lo usa contra la API
 * interna del reproductor web.
 *
 * @param clock reloj en milisegundos; solo se sustituye en tests.
 */
class WebCookieAuthProvider(
    private val http: HttpClient,
    private val store: SpotifyTokenStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : SpotifyAuthProvider {
    private val mutex = Mutex()
    private val deviceId = UUID.randomUUID().toString()
    private var remoteSecret: TotpSecret? = null
    private var remoteSecretAt = 0L
    private var clientToken: String? = null
    private var clientTokenUntil = 0L

    override val mode: SpotifyAuthMode get() = SpotifyAuthMode.EXPERIMENTAL

    override suspend fun isSignedIn(): Boolean = !store.load(KeyCookie).isNullOrBlank()

    /**
     * Valida la cookie obteniendo un token y, si funciona, la guarda. Acepta el valor suelto o pegado como
     * `sp_dc=valor; otra=…`.
     *
     * @throws SpotifyAuthException si la cookie no sirve o Spotify cambió el flujo.
     */
    suspend fun signInWithCookie(spDc: String) = mutex.withLock {
        val cookie = cookieValue(spDc)
        if (cookie.isEmpty()) throw SpotifyAuthException("La cookie sp_dc está vacía.")
        val token = fetchToken(cookie)
        store.save(KeyCookie, cookie)
        saveToken(token)
    }

    override suspend fun accessToken(): String = mutex.withLock {
        val cached = store.load(KeyToken)?.let { runCatching { SpotifyJson.decodeFromString(WebToken.serializer(), it) }.getOrNull() }
        if (cached != null && cached.expiresAt - ExpiryMarginMs > clock()) return@withLock cached.accessToken
        val cookie = store.load(KeyCookie)?.takeIf { it.isNotBlank() }
            ?: throw SpotifyAuthException("No has iniciado sesión en Spotify.")
        fetchToken(cookie).also { saveToken(it) }.accessToken
    }

    override suspend fun invalidateAccessToken() = mutex.withLock { store.save(KeyToken, null) }

    override suspend fun signOut() = mutex.withLock {
        store.save(KeyCookie, null)
        store.save(KeyToken, null)
        clientToken = null
        clientTokenUntil = 0
    }

    /**
     * `client-token` que el reproductor web adjunta a sus peticiones GraphQL. Es opcional: si no se puede
     * obtener devuelve null y la petición se envía sin él.
     */
    internal suspend fun clientToken(): String? = mutex.withLock {
        // También se recuerda el fallo (null) durante el TTL para no reintentar en cada petición.
        if (clientTokenUntil > clock()) return@withLock clientToken
        val body = try {
            http.post(WebPlayer.ClientTokenUrl) {
                webPlayerHeaders()
                setBody(TextContent(clientTokenPayload(), ContentType.Application.Json))
            }.takeIf { it.status.isSuccess() }?.jsonOrNull()
        } catch (_: IOException) {
            null
        }
        val granted = body["granted_token"]
        clientToken = granted["token"].string?.takeIf { it.isNotBlank() }
        clientTokenUntil = clock() + (granted["refresh_after_seconds"].long ?: ClientTokenTtlSec) * 1000
        clientToken
    }

    /**
     * Pide el token probando cada secreto conocido, del más nuevo al más viejo. O(s) peticiones con
     * s ≤ 3 secretos (remoto, último remoto guardado y embebido).
     */
    private suspend fun fetchToken(cookie: String): WebToken {
        val serverTimeSec = serverTimeSec()
        var lastStatus = 0
        for (secret in secrets()) {
            val response = webGet(WebPlayer.TokenUrl) {
                val code = SpotifyTotp.code(secret.secret, serverTimeSec)
                parameter("reason", "init")
                parameter("productType", "web-player")
                parameter("totp", code)
                parameter("totpServer", code)
                parameter("totpVer", secret.version)
                header(HttpHeaders.Cookie, "sp_dc=$cookie")
            }
            lastStatus = response.status.value
            if (!response.status.isSuccess()) continue
            val body = response.jsonOrNull()
            val token = body["accessToken"].string?.takeIf { it.isNotBlank() } ?: throw SpotifyAuthException(MsgFlowChanged)
            if (body["isAnonymous"].bool == true) {
                throw SpotifyAuthException("La cookie sp_dc no es válida o caducó. Vuelve a copiarla desde open.spotify.com.")
            }
            return WebToken(token, body["accessTokenExpirationTimestampMs"].long ?: (clock() + DefaultTokenTtlMs))
        }
        throw SpotifyAuthException("$MsgFlowChanged (HTTP $lastStatus)")
    }

    /**
     * Secretos candidatos ordenados por versión descendente: el publicado en la fuente comunitaria (se
     * consulta como mucho cada [SecretsTtlMs]), el último que se descargó con éxito y el embebido.
     */
    private suspend fun secrets(): List<TotpSecret> {
        if (remoteSecret == null || clock() - remoteSecretAt > SecretsTtlMs) {
            val fetched = runCatching { fetchRemoteSecret() }.getOrNull()
            if (fetched != null) {
                store.save(KeySecret, SpotifyJson.encodeToString(TotpSecret.serializer(), fetched))
                remoteSecret = fetched
                remoteSecretAt = clock()
            }
        }
        val stored = store.load(KeySecret)?.let { runCatching { SpotifyJson.decodeFromString(TotpSecret.serializer(), it) }.getOrNull() }
        return listOfNotNull(remoteSecret, stored, WebPlayerSecrets.Embedded).distinct().sortedByDescending { it.version }
    }

    private suspend fun fetchRemoteSecret(): TotpSecret? {
        val response = http.get(WebPlayerSecrets.RemoteUrl) { expectSuccess = false }
        if (!response.status.isSuccess()) return null
        return response.jsonOrNull().array
            .mapNotNull { entry -> entry["s"].string?.takeIf { it.isNotBlank() }?.let { s -> entry["v"].int?.let { v -> TotpSecret(s, v) } } }
            .maxByOrNull { it.version }
    }

    /** Hora de Spotify en segundos; si el endpoint no responde se usa el reloj local. */
    private suspend fun serverTimeSec(): Long = try {
        webGet(WebPlayer.ServerTimeUrl).takeIf { it.status.isSuccess() }?.jsonOrNull().path("serverTime").long
    } catch (_: SpotifyAuthException) {
        null
    } ?: (clock() / 1000)

    private suspend fun webGet(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse = try {
        http.get(url) {
            webPlayerHeaders()
            block()
        }
    } catch (e: IOException) {
        throw SpotifyAuthException(MsgNoConnection, e)
    }

    private suspend fun saveToken(token: WebToken) = store.save(KeyToken, SpotifyJson.encodeToString(WebToken.serializer(), token))

    private fun clientTokenPayload(): String = buildJsonObject {
        putJsonObject("client_data") {
            put("client_version", WebPlayer.AppVersion)
            put("client_id", WebPlayer.ClientId)
            putJsonObject("js_sdk_data") {
                put("device_brand", "unknown")
                put("device_model", "unknown")
                put("os", "android")
                put("os_version", "unknown")
                put("device_id", deviceId)
                put("device_type", "computer")
            }
        }
    }.toString()

    @Serializable
    private data class WebToken(val accessToken: String, val expiresAt: Long)

    companion object {
        internal const val KeyCookie = "spotify.web.sp_dc"
        internal const val KeyToken = "spotify.web.token"
        internal const val KeySecret = "spotify.web.totp"
        internal const val MsgFlowChanged = "Spotify cambió su inicio de sesión web; usa el modo oficial"
        private const val SecretsTtlMs = 6 * 60 * 60 * 1000L
        private const val DefaultTokenTtlMs = 30 * 60 * 1000L
        private const val ClientTokenTtlSec = 30 * 60L

        /** Extrae el valor de `sp_dc` tanto si llega suelto como dentro de una cabecera `Cookie` completa. */
        internal fun cookieValue(raw: String): String {
            val text = raw.trim().removeSurrounding("\"")
            val fromHeader = text.split(';').map { it.trim() }.firstOrNull { it.startsWith("sp_dc=") }?.removePrefix("sp_dc=")
            return (fromHeader ?: text).trim()
        }
    }
}

/** Identidad del reproductor web con la que se hacen las peticiones del modo experimental. */
internal object WebPlayer {
    const val TokenUrl = "https://open.spotify.com/api/token"
    const val ServerTimeUrl = "https://open.spotify.com/api/server-time"
    const val ClientTokenUrl = "https://clienttoken.spotify.com/v1/clienttoken"
    const val Origin = "https://open.spotify.com"
    const val UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    /** Versión del cliente web que se declara en `spotify-app-version` y al pedir el `client-token`. */
    const val AppVersion = "1.2.96.390.gd46a85db"

    /** Client ID público del propio reproductor web (no es un secreto; va en su JavaScript). */
    const val ClientId = "d8a5ed958d274c2e8ee717e6a4b0971d"
}

internal fun HttpRequestBuilder.webPlayerHeaders() {
    expectSuccess = false
    header(HttpHeaders.UserAgent, WebPlayer.UserAgent)
    header(HttpHeaders.Accept, "application/json")
    header(HttpHeaders.Origin, WebPlayer.Origin)
    header(HttpHeaders.Referrer, "${WebPlayer.Origin}/")
    header("app-platform", "WebPlayer")
}

/** Secreto TOTP del reproductor web: [secret] en Base32 y la [version] que Spotify espera en `totpVer`. */
@Serializable
internal data class TotpSecret(val secret: String, val version: Int)

/**
 * Lo único que hay que tocar cuando Spotify rote el secreto y la fuente remota deje de mantenerse:
 * actualizar [Embedded] con el par (secreto, versión) vigente.
 */
internal object WebPlayerSecrets {
    /** Gist comunitario (sonic-liberation) con `[{"s": "<base32>", "v": <versión>}]`; es el que consulta Echo Music. */
    const val RemoteUrl = "https://gist.githubusercontent.com/sonic-liberation/22ed9c6ba463899e933427f7de1f0eef/raw/"

    /** Versión 61, vigente el 2026-11-16 (coincide con el secreto embebido de Metrolist). */
    val Embedded = TotpSecret(
        secret = "GM3TMMJTGYZTQNZVGM4DINJZHA4TGOBYGMZTCMRTGEYDSMJRHE4TEOBUG4YTCMRUGQ4DQOJUGQYTAMRRGA2TCMJSHE3TCMBY",
        version = 61,
    )
}

/** TOTP de RFC 6238 (HMAC-SHA1, 6 dígitos, paso de 30 s), que es lo que valida `/api/token`. */
internal object SpotifyTotp {
    private const val Base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val PeriodSec = 30L

    /** Código de 6 dígitos para el instante [epochSec]. O(n) sobre la longitud del secreto. */
    fun code(secretBase32: String, epochSec: Long): String {
        val counter = ByteArray(8) { i -> ((epochSec / PeriodSec) shr (8 * (7 - i))).toByte() }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(base32Decode(secretBase32), "HmacSHA1")) }
        val digest = mac.doFinal(counter)
        val offset = digest.last().toInt() and 0x0F
        val binary = ((digest[offset].toInt() and 0x7F) shl 24) or
            ((digest[offset + 1].toInt() and 0xFF) shl 16) or
            ((digest[offset + 2].toInt() and 0xFF) shl 8) or
            (digest[offset + 3].toInt() and 0xFF)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    /** Base32 de RFC 4648 sin relleno; ignora los caracteres ajenos al alfabeto. O(n). */
    fun base32Decode(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in text.uppercase()) {
            val value = Base32Alphabet.indexOf(c)
            if (value < 0) continue
            buffer = ((buffer shl 5) or value) and 0xFFFF
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out += ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.toByteArray()
    }
}
