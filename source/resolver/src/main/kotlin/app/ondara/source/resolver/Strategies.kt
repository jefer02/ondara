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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream

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
class NewPipeStrategy(private val okHttp: OkHttpClient) : StreamStrategy, VideoStreamStrategy, VideoSearchStrategy {
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

    /**
     * Pistas de vídeo de [videoId]. NewPipe no expone `musicVideoType`, así que la "art track" se decide
     * con la heurística de [VideoSelection.isArtTrack] (canal "- Topic" / "Provided to YouTube by").
     * Una extracción de red; la selección es O(f) con f formatos.
     */
    override suspend fun resolveVideo(videoId: String): Result<ResolvedVideo> = resultOf {
        runInterruptible(Dispatchers.IO) {
            ensureInit()
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()
            val audio = extractor.audioStreams.filter { it.isUrl }.map { s ->
                AudioCandidate(s.content, s.format?.mimeType ?: "audio/mp4", s.averageBitrate.coerceAtLeast(0), null, null)
            }.pick(AudioQuality.HIGH)
            VideoSelection.build(
                videoId = videoId,
                source = name,
                videoOnly = extractor.videoOnlyStreams.filter { it.isUrl }.map { it.toCandidate(hasAudio = false) },
                muxed = extractor.videoStreams.filter { it.isUrl && !it.isVideoOnly }.map { it.toCandidate(hasAudio = true) },
                audioUrl = audio?.url,
                isArtTrack = VideoSelection.isArtTrack(
                    musicVideoType = null,
                    author = runCatching { extractor.uploaderName }.getOrNull(),
                    description = runCatching { extractor.description.content }.getOrNull(),
                ),
                durationSec = runCatching { extractor.length.toInt() }.getOrDefault(0),
            )
        }
    }

    /** Búsqueda de vídeos en YouTube Music (filtro `music_videos`). Una llamada de red, O(r) con r resultados. */
    override suspend fun searchVideos(query: String): Result<List<VideoSearchHit>> = resultOf {
        runInterruptible(Dispatchers.IO) {
            ensureInit()
            val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS), "")
            extractor.fetchPage()
            extractor.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
                val id = VIDEO_ID.find(item.url.orEmpty())?.groupValues?.get(1) ?: return@mapNotNull null
                VideoSearchHit(id, item.name.orEmpty(), item.uploaderName.orEmpty(), item.duration.toInt().coerceAtLeast(0))
            }
        }
    }

    private fun VideoStream.toCandidate(hasAudio: Boolean) = VideoCandidate(
        url = content,
        mimeType = (format?.mimeType ?: "video/mp4") + (codec?.takeIf { it.isNotBlank() }?.let { "; codecs=\"$it\"" } ?: ""),
        width = width.coerceAtLeast(0),
        height = if (height > 0) height else VideoSelection.heightOf(resolution),
        bitrate = bitrate.coerceAtLeast(0),
        hasAudio = hasAudio,
    )

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
        val VIDEO_ID = Regex("""(?:[?&]v=|youtu\.be/)([\w-]{11})""")
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
) : StreamStrategy, VideoStreamStrategy {
    override val name = "innertube"

    /** Igual que [resolve] pero para las pistas de vídeo: O(c) peticiones en el peor caso con c clientes. */
    override suspend fun resolveVideo(videoId: String): Result<ResolvedVideo> {
        var last: Throwable = IllegalStateException("sin clientes")
        for (identity in rotation) {
            val attempt = resultOf { parseVideo(videoId, client.player(videoId, identity), identity.name) }
            attempt.onSuccess { return attempt }.onFailure { last = it }
        }
        return Result.failure(last)
    }

    /**
     * Extrae de la respuesta de `player` las pistas de solo vídeo (`adaptiveFormats`), las progresivas
     * (`formats`) y si es art track (`videoDetails.musicVideoType`). O(f) con f formatos.
     */
    internal fun parseVideo(videoId: String, json: JsonObject, clientName: String): ResolvedVideo {
        val status = json.path("playabilityStatus", "status").string
        check(status == "OK") { "$clientName: ${json.path("playabilityStatus", "reason").string ?: status}" }
        val details = json["videoDetails"]
        check(details["videoId"].string.let { it == null || it == videoId }) { "$clientName devolvió otro vídeo" }
        val adaptive = json.path("streamingData", "adaptiveFormats").array
        val audio = adaptive.mapNotNull { f ->
            val mime = f["mimeType"].string?.takeIf { it.startsWith("audio/") } ?: return@mapNotNull null
            AudioCandidate(f["url"].string ?: return@mapNotNull null, mime, f["averageBitrate"].int ?: f["bitrate"].int ?: 0, null, null)
        }.pick(AudioQuality.HIGH)
        return VideoSelection.build(
            videoId = videoId,
            source = name,
            videoOnly = adaptive.mapNotNull { it.toVideoCandidate(hasAudio = false) },
            muxed = json.path("streamingData", "formats").array.mapNotNull { it.toVideoCandidate(hasAudio = true) },
            audioUrl = audio?.url,
            isArtTrack = VideoSelection.isArtTrack(details["musicVideoType"].string, details["author"].string, details["shortDescription"].string),
            durationSec = details["lengthSeconds"].string?.toIntOrNull() ?: details["lengthSeconds"].int ?: 0,
        )
    }

    private fun JsonElement.toVideoCandidate(hasAudio: Boolean): VideoCandidate? {
        val mime = this["mimeType"].string?.takeIf { it.startsWith("video/") } ?: return null
        return VideoCandidate(
            url = this["url"].string ?: return null,
            mimeType = mime,
            width = this["width"].int ?: 0,
            height = this["height"].int ?: VideoSelection.heightOf(this["qualityLabel"].string),
            bitrate = this["averageBitrate"].int ?: this["bitrate"].int ?: 0,
            hasAudio = hasAudio,
        )
    }

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
