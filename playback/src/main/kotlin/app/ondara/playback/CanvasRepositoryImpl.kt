// Adaptado de Echo Music (https://github.com/EchoMusicApp/Echo-Music), GPL-3.0.
package app.ondara.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.ondara.core.data.SettingsCache
import app.ondara.core.domain.CanvasRepository
import app.ondara.core.domain.LyricsRepository
import app.ondara.core.model.Canvas
import app.ondara.core.model.CanvasSource
import app.ondara.core.model.CanvasState
import app.ondara.core.model.Song
import app.ondara.source.canvas.AppleMusicCanvasProvider
import app.ondara.source.canvas.CanvasChain
import app.ondara.source.canvas.TidalCanvasProvider
import app.ondara.source.canvas.canvasAttempt
import app.ondara.source.resolver.StreamResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Caché exclusiva del canvas, separada de las descargas permanentes del usuario. */
@OptIn(UnstableApi::class)
@Singleton
class CanvasMediaCache @Inject constructor(@ApplicationContext context: Context, http: OkHttpClient) {
    private val cache = SimpleCache(
        File(context.cacheDir, "canvas-media"), LeastRecentlyUsedCacheEvictor(200L * 1024 * 1024), StandaloneDatabaseProvider(context),
    )
    val factory: CacheDataSource.Factory = CacheDataSource.Factory().setCache(cache)
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(http)).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}

/** Política común para resolver y precargar: el Wi-Fi restringe únicamente YouTube. */
@OptIn(UnstableApi::class)
@Singleton
class CanvasRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    http: HttpClient,
    private val resolver: StreamResolver,
    lyrics: LyricsRepository,
    private val settings: SettingsCache,
    private val mediaCache: CanvasMediaCache,
) : CanvasRepository {
    private val environment = VideoEnvironment(context) {}
    private val chain = CanvasChain(
        listOf(AppleMusicCanvasProvider(http), TidalCanvasProvider(http), YouTubeCanvasProvider(resolver, lyrics, ::youtubeAllowed)),
        File(context.filesDir, "canvas-urls.json"),
    )

    private fun youtubeAllowed() = environment.allowed(settings.value.videoBackground, settings.value.videoWifiOnly)

    fun permitted(canvas: Canvas): Boolean = settings.value.videoBackground &&
        (canvas.source !in setOf(CanvasSource.YOUTUBE_CLIP, CanvasSource.YOUTUBE_VIDEO) || youtubeAllowed())

    internal suspend fun fullVideo(song: Song): FullVideo? = withContext(Dispatchers.IO) {
        if (youtubeAllowed()) resolveFullVideo(resolver, song)?.takeIf { youtubeAllowed() } else null
    }

    internal fun invalidateFullVideo(videoId: String) = resolver.invalidateVideo(videoId)

    private suspend fun resolve(song: Song): Canvas? {
        if (!settings.value.videoBackground) return null
        return chain.resolve(song, youtubeAllowed())?.canvas?.takeIf(::permitted)
    }

    /** La próxima resolución de [songId] vuelve a la red. */
    suspend fun invalidate(songId: String) = chain.invalidate(songId)

    override fun observeCanvas(song: Song) = flow<CanvasState> {
        if (!settings.value.videoBackground) {
            emit(CanvasState.Loading(song.id))
            emit(CanvasState.None(song.id))
        } else {
            chain.observeCanvas(song, youtubeAllowed()).collect { result ->
                val permitted = result !is CanvasState.Ready || permitted(result.canvas)
                emit(if (permitted) result else CanvasState.None(song.id))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Resuelve O(p) proveedores y prepara el recorte sin reproducir: Media3 busca sus primeros paquetes,
     * incluyendo índices MP4 o segmentos HLS, en la misma caché. Memoria de búfer acotada por Media3.
     */
    override suspend fun prefetch(song: Song) {
        val canvas = withContext(Dispatchers.IO) { resolve(song) } ?: return
        canvasAttempt {
            withContext(Dispatchers.Main.immediate) {
                if (!permitted(canvas) || !environment.visible) return@withContext
                val player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(mediaCache.factory)).build()
                try {
                    player.volume = 0f
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                    awaitPrefetch(player, canvas)
                } finally {
                    player.release()
                }
            }
        }
    }

    private suspend fun awaitPrefetch(player: ExoPlayer, canvas: Canvas) {
        withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY && continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                })
                player.setMediaItem(canvas.mediaItem())
                player.prepare()
            }
        }
    }
}

/** Configura el recorte en tiempo del medio original. O(1). */
internal fun Canvas.mediaItem(): MediaItem = MediaItem.Builder().setUri(url).setClippingConfiguration(
    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clipStartMs).apply {
        clipEndMs?.let { setEndPositionMs(it) }
    }.build(),
).build()
