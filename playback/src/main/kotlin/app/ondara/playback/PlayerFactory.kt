package app.ondara.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.ondara.core.data.SettingsCache
import app.ondara.core.model.ArtistRef
import app.ondara.core.model.Song
import app.ondara.source.resolver.StreamResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

internal const val SONG_SCHEME = "ondara"

@OptIn(UnstableApi::class)
fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri("$SONG_SCHEME://song/$id")
    .setCustomCacheKey(id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artistLine)
            .setAlbumTitle(album?.title)
            .setArtworkUri(thumbnailUrl?.let(Uri::parse))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build(),
    )
    .build()

/** Reconstruye un [Song] mínimo desde un MediaItem ajeno (p. ej. pedido por Android Auto). */
internal fun MediaItem.toSong(): Song = Song(
    id = mediaId,
    title = mediaMetadata.title?.toString().orEmpty(),
    artists = listOfNotNull(mediaMetadata.artist?.toString()?.let { ArtistRef(null, it) }),
    thumbnailUrl = mediaMetadata.artworkUri?.toString(),
)

/** Las dos cachés en disco: descargas (permanente) y streaming (LRU de 256 MB). */
@OptIn(UnstableApi::class)
@Singleton
class PlaybackCaches @Inject constructor(@ApplicationContext context: Context) {
    private val database = StandaloneDatabaseProvider(context)
    val downloads = SimpleCache(File(context.filesDir, "downloads"), NoOpCacheEvictor(), database)
    val streaming = SimpleCache(File(context.cacheDir, "stream"), LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024), database)

    /** `true` si [songId] está completa en la caché de descargas. */
    fun isDownloaded(songId: String, length: Long): Boolean = length > 0 && downloads.isCached(songId, 0, length)
}

/**
 * Amplitud RMS del PCM que pasa por el sink (0..1), para el tema Neón.
 * No necesita permiso de micrófono: se mide el audio propio con un `TeeAudioProcessor`.
 * O(n) sobre las muestras de cada buffer, submuestreadas ×4.
 */
@OptIn(UnstableApi::class)
@Singleton
class AmplitudeMeter @Inject constructor() : TeeAudioProcessor.AudioBufferSink {
    val amplitude = MutableStateFlow(0f)
    private var encoding = C.ENCODING_PCM_16BIT

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (encoding != C.ENCODING_PCM_16BIT) return
        val samples = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        var count = 0
        var i = samples.position()
        while (i < samples.limit()) {
            val v = samples.get(i) / 32768.0
            sum += v * v
            count++
            i += 4
        }
        if (count == 0) return
        val rms = sqrt(sum / count).toFloat()
        // Ataque rápido, caída lenta: el pulso se ve musical y no parpadea.
        val previous = amplitude.value
        amplitude.value = if (rms > previous) rms.coerceAtMost(1f) else previous * 0.86f + rms * 0.14f
    }
}

@OptIn(UnstableApi::class)
@Singleton
class PlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
    private val caches: PlaybackCaches,
    private val resolver: StreamResolver,
    private val settings: SettingsCache,
    private val meter: AmplitudeMeter,
) {
    /**
     * Fuente de datos: descargas → caché de streaming → red. La URI `ondara://song/<id>` se
     * resuelve justo antes de abrir la conexión; si la canción ya está en disco no se toca la red.
     * Con la URL pre-resuelta el acierto de caché es O(1) y el salto de canción es inmediato.
     */
    fun dataSourceFactory(): DataSource.Factory {
        val network = OkHttpDataSource.Factory(okHttp)
        val streaming = CacheDataSource.Factory().setCache(caches.streaming).setUpstreamDataSourceFactory(network)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val downloads = CacheDataSource.Factory().setCache(caches.downloads).setUpstreamDataSourceFactory(streaming)
            .setCacheWriteDataSinkFactory(null)
        return ResolvingDataSource.Factory(downloads) { spec -> resolve(spec) }
    }

    private fun resolve(spec: DataSpec): DataSpec {
        if (spec.uri.scheme != SONG_SCHEME) return spec
        val id = spec.key ?: spec.uri.lastPathSegment ?: return spec
        val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(caches.downloads.getContentMetadata(id))
        if (caches.isDownloaded(id, length)) return spec
        val streamLength = androidx.media3.datasource.cache.ContentMetadata.getContentLength(caches.streaming.getContentMetadata(id))
        if (streamLength > 0 && caches.streaming.isCached(id, spec.position, streamLength - spec.position)) return spec
        val stream = try {
            runBlocking { resolver.resolve(id, settings.value.streamQuality) }
        } catch (e: Exception) {
            // ExoPlayer solo sabe recuperarse de IOException en el hilo de carga.
            throw java.io.IOException(e.message, e)
        }
        return spec.withUri(stream.url.toUri())
    }

    fun create(handleAudioFocus: Boolean): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(meter)))
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                handleAudioFocus,
            )
            .setHandleAudioBecomingNoisy(handleAudioFocus)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }
}

/** Matemática de volumen: normalización por loudness y curvas de crossfade. */
object Gain {
    /**
     * Ganancia lineal para normalizar. YouTube da `loudnessDb` relativo a su referencia:
     * positivo = la pista suena más fuerte → se atenúa `10^(−dB/20)`. Nunca se amplifica (evita clipping).
     */
    fun normalization(loudnessDb: Float?): Float {
        if (loudnessDb == null || loudnessDb <= 0f) return 1f
        return Math.pow(10.0, -loudnessDb / 20.0).toFloat().coerceIn(0.25f, 1f)
    }

    /** Curva de igual potencia: la suma de potencias (in² + out²) es 1 durante todo el fundido. */
    fun fadeIn(t: Float): Float = kotlin.math.sin(t.coerceIn(0f, 1f) * Math.PI / 2).toFloat()

    fun fadeOut(t: Float): Float = kotlin.math.cos(t.coerceIn(0f, 1f) * Math.PI / 2).toFloat()
}
