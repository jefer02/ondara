package app.ondara.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Única configuración de red de Ondara: Ktor (motor OkHttp) + kotlinx.serialization. */
object HttpClientFactory {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** OkHttp compartido por Ktor, Coil, Media3 y NewPipe (un solo pool de conexiones). */
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun create(okHttp: OkHttpClient): HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        configure()
    }

    /** Para tests: mismo pipeline sobre un motor falso (`MockEngine`). */
    fun create(engine: HttpClientEngine): HttpClient = HttpClient(engine) { configure() }

    private fun io.ktor.client.HttpClientConfig<*>.configure() {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(ContentEncoding) { gzip(); deflate() }
        install(HttpTimeout) {
            requestTimeoutMillis = 25_000
            connectTimeoutMillis = 15_000
        }
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 1)
            exponentialDelay()
        }
    }
}
