package app.ondara.source.spotify

import app.ondara.core.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString

/** Store en memoria; [values] queda a la vista para comprobar qué se persistió. */
class MemoryStore : SpotifyTokenStore {
    val values = HashMap<String, String>()

    override suspend fun load(key: String): String? = values[key]

    override suspend fun save(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** Provider de mentira: entrega [tokens] en orden, avanzando cada vez que el cliente invalida el actual. */
class FakeAuth(override val mode: SpotifyAuthMode, private vararg val tokens: String) : SpotifyAuthProvider {
    var invalidations = 0

    override suspend fun isSignedIn() = true

    override suspend fun accessToken(): String = tokens[minOf(invalidations, tokens.lastIndex)]

    override suspend fun invalidateAccessToken() {
        invalidations++
    }

    override suspend fun signOut() = Unit
}

/** Mismo pipeline que producción (`expectSuccess = true` incluido) sobre un motor falso que registra las peticiones. */
fun mockHttp(requests: MutableList<HttpRequestData> = ArrayList(), handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
    HttpClientFactory.create(
        MockEngine { request ->
            requests += request
            handler(request)
        },
    )

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg headers: Pair<String, String>): HttpResponseData =
    respond(
        body,
        status,
        headersOf(*(headers.map { it.first to listOf(it.second) } + (HttpHeaders.ContentType to listOf("application/json"))).toTypedArray()),
    )

suspend fun HttpRequestData.text(): String = String(body.toByteArray())

suspend fun HttpRequestData.form(): Parameters = parseQueryString(text())

val HttpRequestData.bearer: String? get() = headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
