package app.ondara.source.spotify

import app.ondara.core.network.get
import app.ondara.core.network.string
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/** Ejecuta una petición autenticada: recibe cómo construirla a partir del token y devuelve la respuesta final. */
internal typealias AuthorizedSend = suspend (request: suspend (token: String) -> HttpResponse) -> HttpResponse

/**
 * Lectura de la biblioteca de Spotify del usuario.
 *
 * Con un provider [SpotifyAuthMode.OFFICIAL] habla con la API Web (`api.spotify.com/v1`). Con uno
 * [SpotifyAuthMode.EXPERIMENTAL] usa la API interna del reproductor web ([PathfinderLibrary]), porque
 * Spotify ya no acepta el token del web player en la API Web. La API pública es la misma en ambos casos.
 *
 * Todas las llamadas renuevan el token una vez ante un 401 y respetan `Retry-After` ante un 429.
 *
 * @param auth se consulta en cada llamada, así el usuario puede cambiar de modo sin recrear el cliente.
 * @throws SpotifyAuthException si no hay sesión o Spotify la rechaza.
 * @throws SpotifyApiException ante cualquier otro fallo de red o de la API.
 */
class SpotifyClient(private val http: HttpClient, private val auth: () -> SpotifyAuthProvider) {
    private val pathfinder = PathfinderLibrary(http, ::send) { (auth() as? WebCookieAuthProvider)?.clientToken() }

    private val experimental: Boolean get() = auth().mode == SpotifyAuthMode.EXPERIMENTAL

    suspend fun me(): SpotifyUser =
        if (experimental) pathfinder.me() else SpotifyParsers.user(getJson("$Api/me"))

    /** Playlists propias y seguidas, con la paginación completa. */
    suspend fun playlists(): List<SpotifyPlaylist> =
        if (experimental) pathfinder.playlists() else paged("$Api/me/playlists$FirstPage", SpotifyParsers::playlist)

    /**
     * Canciones de la playlist, con la paginación completa; omite archivos locales, episodios y huecos nulos.
     *
     * En modo oficial usa `GET /playlists/{id}/items` (sustituye a `/tracks` desde febrero de 2026); con
     * una app en modo desarrollo Spotify solo entrega las playlists propias o colaborativas y responde 403
     * en las demás.
     */
    suspend fun playlistTracks(playlistId: String): List<SpotifyTrack> =
        if (experimental) {
            pathfinder.playlistTracks(playlistId)
        } else {
            paged("$Api/playlists/${playlistId.encodeURLPathPart()}/items$FirstPage", SpotifyParsers::playlistEntry)
        }

    /** "Canciones que te gustan". */
    suspend fun savedTracks(): List<SpotifyTrack> =
        if (experimental) pathfinder.savedTracks() else paged("$Api/me/tracks$FirstPage") { SpotifyParsers.track(it["track"]) }

    suspend fun savedAlbums(): List<SpotifyAlbum> =
        if (experimental) pathfinder.savedAlbums() else paged("$Api/me/albums$FirstPage") { SpotifyParsers.album(it["album"]) }

    /**
     * Recorre una colección paginada siguiendo `next`. O(n) elementos en ⌈n/50⌉ peticiones secuenciales
     * (en paralelo Spotify responde 429 enseguida).
     */
    private suspend fun <T : Any> paged(firstUrl: String, parse: (JsonElement) -> T?): List<T> {
        val out = ArrayList<T>()
        var url: String? = firstUrl
        var pages = 0
        while (url != null && pages++ < MaxPages) {
            val page = getJson(url)
            val items = page["items"] as? JsonArray ?: throw SpotifyApiException(200, "Spotify devolvió una página de biblioteca inválida.")
            items.mapNotNullTo(out, parse)
            // Una continuación externa indica una respuesta incompleta y nunca recibe el token.
            url = page["next"].string
            if (url != null && !url.startsWith("$Api/")) {
                throw SpotifyApiException(200, "Spotify devolvió una continuación de biblioteca inválida.")
            }
        }
        if (url != null) throw SpotifyApiException(200, "Spotify devolvió demasiadas páginas para completar la importación.")
        return out
    }

    private suspend fun getJson(url: String): JsonElement {
        val response = send { token ->
            http.get(url) {
                expectSuccess = false
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, "application/json")
            }
        }
        return response.takeIf { it.status.isSuccess() }?.jsonOrNull() ?: throw failure(response.status.value)
    }

    /**
     * Envía [request] con el token vigente. Ante un 401 invalida el token, pide otro y reintenta una sola
     * vez; ante un 429 espera lo que diga `Retry-After` (hasta [MaxRetryAfterSec], [MaxRateLimitRetries] veces).
     * Cualquier otra respuesta se devuelve tal cual para que la interprete el llamador.
     */
    private suspend fun send(request: suspend (token: String) -> HttpResponse): HttpResponse {
        val provider = auth()
        var token = provider.accessToken()
        var refreshed = false
        var throttled = 0
        while (true) {
            val response = try {
                request(token)
            } catch (e: IOException) {
                throw SpotifyApiException(0, MsgNoConnection, e)
            }
            when {
                response.status == HttpStatusCode.Unauthorized && !refreshed -> {
                    refreshed = true
                    provider.invalidateAccessToken()
                    token = provider.accessToken()
                }
                response.status == HttpStatusCode.TooManyRequests -> {
                    val waitSec = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.coerceAtLeast(0) ?: DefaultRetryAfterSec
                    if (throttled++ >= MaxRateLimitRetries || waitSec > MaxRetryAfterSec) {
                        throw SpotifyApiException(429, "Spotify está limitando las peticiones. Inténtalo de nuevo más tarde.")
                    }
                    delay(waitSec * 1000)
                }
                else -> return response
            }
        }
    }

    private fun failure(status: Int): Exception = when (status) {
        401 -> SpotifyAuthException("Spotify rechazó la sesión. Vuelve a iniciar sesión.")
        403 -> SpotifyApiException(
            status,
            "Spotify denegó el acceso. Con una app en modo desarrollo solo se pueden leer las playlists propias o " +
                "colaborativas, y tu cuenta debe estar añadida a la app en developer.spotify.com.",
        )
        404 -> SpotifyApiException(status, "Spotify no encontró lo que se pidió.")
        else -> SpotifyApiException(status, "Spotify devolvió una respuesta inesperada (HTTP $status).")
    }

    private companion object {
        const val Api = "https://api.spotify.com/v1"
        const val FirstPage = "?limit=50&offset=0"

        /** Tope de seguridad contra un `next` que nunca termina: 400 páginas = 20 000 elementos. */
        const val MaxPages = 400
        const val MaxRateLimitRetries = 3
        const val DefaultRetryAfterSec = 2L

        /** Esperas mayores (Spotify llega a pedir horas) se convierten en error en vez de bloquear la importación. */
        const val MaxRetryAfterSec = 60L
    }
}
