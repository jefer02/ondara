// Adaptado de Echo Music (https://github.com/EchoMusicApp/Echo-Music), GPL-3.0.
package app.ondara.source.canvas

import app.ondara.core.network.HttpClientFactory
import app.ondara.core.network.get
import app.ondara.core.network.long
import app.ondara.core.network.string
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

/** Obtiene el token público del catálogo web, sin incrustar credenciales caducables. */
class AppleMusicTokenProvider(private val http: HttpClient) {
    private val mutex = Mutex()
    private var cached: Pair<String, Long>? = null

    /** O(b + t) caracteres del bundle y tokens; caché O(1) hasta cinco minutos antes de expirar. */
    suspend fun token(): String? = mutex.withLock {
        val now = System.currentTimeMillis()
        cached?.takeIf { it.second > now + 300_000 }?.let { return@withLock it.first }
        for (host in listOf("https://music.apple.com", "https://beta.music.apple.com")) {
            val result = canvasAttempt {
                val html = http.get("$host/us/browse").bodyAsText()
                val bundle = Regex("""/assets/index[^"'\s]*\.js""").find(html)?.value ?: return@canvasAttempt null
                val js = http.get(host + bundle).bodyAsText()
                Regex("""eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""").findAll(js).mapNotNull { match ->
                    runCatching {
                        val payload = HttpClientFactory.json.parseToJsonElement(String(Base64.getUrlDecoder().decode(match.value.split('.')[1])))
                        val expiry = (payload["exp"].long ?: 0) * 1000
                        if (payload["iss"].string == "AMPWebPlay" && expiry > now + 300_000) match.value to expiry else null
                    }.getOrNull()
                }.maxByOrNull { it.second }
            }
            if (result != null) { cached = result; return@withLock result.first }
        }
        null
    }

    suspend fun clear() = mutex.withLock { cached = null }
}
