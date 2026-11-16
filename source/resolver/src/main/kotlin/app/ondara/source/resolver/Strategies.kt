package app.ondara.source.resolver

import app.ondara.core.model.AudioQuality
import app.ondara.core.model.ResolvedStream
import app.ondara.core.network.array
import app.ondara.core.network.double
import app.ondara.core.network.get
import app.ondara.core.network.int
import app.ondara.core.network.long
import app.ondara.core.network.path
import app.ondara.core.network.string
import app.ondara.source.innertube.InnerTubeClient
import app.ondara.source.innertube.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

private inline fun <T> resultOf(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/**
 * Motor principal: NewPipeExtractor (descifra `signatureCipher` y el parámetro `n` ejecutando
 * el JS del reproductor con Rhino). Es bloqueante, por eso corre en `Dispatchers.IO`.
 */
class NewPipeStrategy(private val okHttp: OkHttpClient) : StreamStrategy {
    override val name = "newpipe"

    override suspend fun resolve(videoId: String, quality: AudioQuality): Result<ResolvedStream> = resultOf {
        runInterruptible(Dispatchers.IO) {
            ensureInit()
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()
            val candidates = extractor.audioStreams.filter { it.isUrl }.map { s ->
                AudioCandidate(
                    url = s.content,
                    mimeType = s.format?.mimeType ?: "audio/mp4",
                    bitrate = (if (s.averageBitrate > 0) s.averageBitrate else s.bitrate.coerceAtLeast(0)).let { if (it < 4000) it * 1000 else it },
                    contentLength = s.itagItem?.contentLength?.takeIf { it > 0 },
                    loudnessDb = null,
                )
            }
            val best = candidates.pick(quality) ?: error("NewPipe no devolvió pistas de audio")
            best.toResolved(videoId, name)
        }
    }

    private fun ensureInit() {
        if (initialized) return
        synchronized(NewPipeStrategy::class.java) {
            if (!initialized) {
                NewPipe.init(OkHttpDownloader(okHttp))
                initialized = true
            }
        }
    }

    private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val builder = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body)
                .header("User-Agent", YouTubeClient.WEB_REMIX.userAgent)
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            client.newCall(builder.build()).execute().use { response ->
                if (response.code == 429) throw ReCaptchaException("reCaptcha", request.url())
                return Response(
                    response.code, response.message, response.headers.toMultimap(),
                    response.body.string(), response.request.url.toString(),
                )
            }
        }
    }

    private companion object {
        @Volatile var initialized = false
    }
}

/**
 * Respaldo: endpoint `player` de InnerTube rotando identidades de cliente (ANDROID → IOS → ANDROID_VR).
 * Estos clientes devuelven URLs directas (sin cifrado de firma) y además la loudness de la pista.
 * O(c) peticiones en el peor caso con c clientes.
 */
class InnerTubeClientStrategy(
    private val client: InnerTubeClient,
    private val rotation: List<YouTubeClient> = YouTubeClient.PLAYER_ROTATION,
) : StreamStrategy {
    override val name = "innertube"

    override suspend fun resolve(videoId: String, quality: AudioQuality): Result<ResolvedStream> {
        var last: Throwable = IllegalStateException("sin clientes")
        for (identity in rotation) {
            val attempt = resultOf { parse(videoId, client.player(videoId, identity), quality, identity.name) }
            attempt.onSuccess { return attempt }.onFailure { last = it }
        }
        return Result.failure(last)
    }

    internal fun parse(videoId: String, json: JsonObject, quality: AudioQuality, clientName: String): ResolvedStream {
        val status = json.path("playabilityStatus", "status").string
        check(status == "OK") { "$clientName: ${json.path("playabilityStatus", "reason").string ?: status}" }
        check(json.path("videoDetails", "videoId").string.let { it == null || it == videoId }) { "$clientName devolvió otro vídeo" }
        val loudness = json.path("playerConfig", "audioConfig", "loudnessDb").double?.toFloat()
        val candidates = json.path("streamingData", "adaptiveFormats").array.mapNotNull { f ->
            val mime = f["mimeType"].string ?: return@mapNotNull null
            if (!mime.startsWith("audio/")) return@mapNotNull null
            AudioCandidate(
                url = f["url"].string ?: return@mapNotNull null,
                mimeType = mime.substringBefore(';') + if ("opus" in mime) "; codecs=opus" else "",
                bitrate = f["averageBitrate"].int ?: f["bitrate"].int ?: 0,
                contentLength = f["contentLength"].long,
                loudnessDb = f["loudnessDb"].double?.toFloat() ?: loudness,
            )
        }
        val best = candidates.pick(quality) ?: error("$clientName: sin formatos de audio con URL directa")
        return best.toResolved(videoId, name)
    }
}

/**
 * Opcional: servidor externo configurado por el usuario (p. ej. audio a mayor bitrate).
 *
 * Contrato: `GET {endpoint}/stream?videoId=…&quality=LOW|HIGH` →
 * `{"url": "...", "mimeType": "audio/flac", "bitrate": 900000, "expiresAt": 1730000000000, "loudnessDb": -1.2}`.
 * Sin endpoint configurado falla de inmediato y la cadena sigue con YouTube.
 */
class ExtensionStrategy(private val http: HttpClient, private val endpoint: () -> String) : StreamStrategy {
    override val name = "extension"

    override suspend fun resolve(videoId: String, quality: AudioQuality): Result<ResolvedStream> = resultOf {
        val base = endpoint().trim().trimEnd('/')
        check(base.startsWith("http")) { "extensión no configurada" }
        val json: JsonObject = http.get("$base/stream") {
            parameter("videoId", videoId)
            parameter("quality", quality.name)
        }.body()
        val url = json["url"].string ?: error("la extensión no devolvió url")
        ResolvedStream(
            videoId = videoId,
            url = url,
            mimeType = json["mimeType"].string ?: "audio/mpeg",
            bitrate = json["bitrate"].int ?: 0,
            expiresAtMs = json["expiresAt"].long ?: StreamUrls.expiresAtMs(url),
            loudnessDb = json["loudnessDb"].double?.toFloat(),
            contentLength = json["contentLength"].long,
            source = name,
        )
    }
}

internal fun AudioCandidate.toResolved(videoId: String, source: String) = ResolvedStream(
    videoId = videoId,
    url = url,
    mimeType = mimeType,
    bitrate = bitrate,
    expiresAtMs = StreamUrls.expiresAtMs(url),
    loudnessDb = loudnessDb,
    contentLength = contentLength,
    source = source,
)
