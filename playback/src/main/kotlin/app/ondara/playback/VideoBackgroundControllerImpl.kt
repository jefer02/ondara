package app.ondara.playback

import android.content.Context
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import app.ondara.core.data.SettingsCache
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.VideoBackgroundController
import app.ondara.core.model.Song
import app.ondara.core.model.VideoBackgroundState
import app.ondara.core.model.VideoStatus
import app.ondara.source.resolver.ResolvedVideo
import app.ondara.source.resolver.StreamResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Vídeo independiente de la MediaSession; todas las operaciones del reproductor se confinan a Main. */
@OptIn(UnstableApi::class)
@Singleton
class VideoBackgroundControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audio: PlayerController,
    private val resolver: StreamResolver,
    private val settings: SettingsCache,
    private val http: OkHttpClient,
) : VideoBackgroundController {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(VideoBackgroundState())
    override val state = mutableState.asStateFlow()
    private val sources = VideoSourceChain(listOf(OfficialVideoSource(resolver), SearchVideoSource(resolver), ArtworkVideoSource()))
    private var session: CoroutineScope? = null
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var load: Job? = null
    private var prefetch: Job? = null
    private var prefetchedId: String? = null
    private var selected: ResolvedVideo? = null
    private var resumeAudio = false
    private var retried = false
    private var suspendedPosition: Long? = null
    private var wasVisible = false
    private var lastAudioPosition: Long? = null
    private val environment = VideoEnvironment(context) { main.launch { reconcile() } }
    private val mediaSources by lazy { DefaultMediaSourceFactory(OkHttpDataSource.Factory(http)) }

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            mutableState.update { it.copy(width = videoSize.width, height = videoSize.height) }
        }

        override fun onRenderedFirstFrame() {
            if (selected != null) mutableState.update { it.copy(status = VideoStatus.READY) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val song = audio.state.value.current ?: return
            val failed = selected ?: return
            leaveVideoMode()
            player?.stop()
            selected = null
            if (retried) {
                unavailable(song.id)
            } else {
                retried = true
                load?.cancel()
                load = session?.launch {
                    withContext(Dispatchers.IO) {
                        resolver.invalidateVideo(failed.videoId)
                        sources.invalidate(song.id)
                    }
                    resolve(song)
                }
            }
        }
    }

    override fun start() { main.launch { startOnMain() } }

    private fun startOnMain() {
        if (session != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        session = scope
        player = ExoPlayer.Builder(context).setMediaSourceFactory(mediaSources)
            .setAudioAttributes(AudioAttributes.DEFAULT, false).setHandleAudioBecomingNoisy(false).build().also {
            it.volume = 0f
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            it.setVideoSurface(surface)
            it.addListener(listener)
        }
        environment.start()
        scope.launch { audio.state.collect { reconcile() } }
        scope.launch { settings.state.collect { reconcile() } }
        scope.launch {
            while (isActive) {
                delay(500)
                synchronize()
            }
        }
    }

    override fun stop() {
        main.launch {
            if (session == null) return@launch
            leaveVideoMode()
            session?.cancel()
            session = null
            load = null
            prefetch = null
            prefetchedId = null
            environment.stop()
            player?.removeListener(listener)
            player?.release()
            player = null
            selected = null
            suspendedPosition = null
            wasVisible = false
            mutableState.value = VideoBackgroundState()
        }
    }

    override fun setSurface(surface: Any?) {
        main.launch {
            this@VideoBackgroundControllerImpl.surface = surface as? Surface
            player?.setVideoSurface(this@VideoBackgroundControllerImpl.surface)
        }
    }

    private fun reconcile() {
        val scope = session ?: return
        val song = audio.state.value.current
        val permitted = environment.allowed(settings.value.videoBackground, settings.value.videoWifiOnly)
        if (song == null || !permitted) {
            load?.cancel()
            load = null
            prefetch?.cancel()
            prefetchedId = null
            unavailable(song?.id)
            return
        }
        val changed = state.value.songId != song.id
        if (changed) {
            leaveVideoMode()
            load?.cancel()
            selected = null
            retried = false
            suspendedPosition = null
            lastAudioPosition = null
            player?.stop()
            mutableState.value = VideoBackgroundState(status = VideoStatus.LOADING, songId = song.id)
        }
        if (!environment.visible) {
            suspendVideo()
            load?.cancel()
            load = null
            prefetch?.cancel()
            prefetchedId = null
            return
        }
        if (changed || selected == null && load == null) {
            load = scope.launch { resolve(song) }
        }
        resumeVideo()
        synchronize()
        prefetchNext()
    }

    private fun suspendVideo() {
        if (wasVisible && selected != null) {
            suspendedPosition = player?.currentPosition
            player?.stop()
        }
        wasVisible = false
    }

    private fun resumeVideo() {
        if (!wasVisible) {
            val video = selected
            if (video != null && suspendedPosition != null) {
                val position = if (state.value.videoMode) suspendedPosition ?: 0 else audio.positionMs()
                mutableState.update { it.copy(status = VideoStatus.LOADING) }
                prepare(video, position, state.value.videoMode)
                suspendedPosition = null
            }
            if (state.value.videoMode) player?.playWhenReady = resumeAudio else player?.seekTo(audio.positionMs())
        }
        wasVisible = true
    }

    private suspend fun resolve(song: Song) {
        mutableState.update { it.copy(status = VideoStatus.LOADING, songId = song.id) }
        val video = withContext(Dispatchers.IO) { sources.resolve(song) }
        if (video == null) {
            unavailable(song.id)
        } else {
            selected = video
            prepare(video, audio.positionMs(), mode = false)
            synchronize()
        }
    }

    private fun prepare(video: ResolvedVideo, position: Long, mode: Boolean) {
        val p = player ?: return
        val stream = if (mode) video.muxed ?: video.videoOnly else video.videoOnly ?: video.muxed
        if (stream == null) return
        val source = mediaSources.createMediaSource(MediaItem.fromUri(stream.url))
        val audioUrl = video.audioUrl
        val media = if (mode && !stream.hasAudio && audioUrl != null) {
            MergingMediaSource(source, mediaSources.createMediaSource(MediaItem.fromUri(audioUrl)))
        } else source
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !mode).build()
        p.volume = if (mode) 1f else 0f
        p.setMediaSource(media, position.coerceAtLeast(0))
        p.prepare()
    }

    private fun synchronize() {
        val p = player ?: return
        if (selected == null) return
        if (!environment.visible) { p.pause(); return }
        if (state.value.videoMode) return
        val playing = audio.state.value.isPlaying
        val position = audio.positionMs()
        // Un salto explícito en pausa también cambia el fotograma, sin corrección continua.
        if (!playing && lastAudioPosition != null && lastAudioPosition != position) {
            p.seekTo(if (p.duration > 0) position.coerceIn(0, p.duration - 1) else position)
        }
        lastAudioPosition = position
        VideoSync.correction(position, p.currentPosition, p.duration, playing)?.let(p::seekTo)
        p.playWhenReady = playing
    }

    override fun setVideoMode(enabled: Boolean) {
        main.launch {
            val video = selected ?: return@launch
            if (enabled == state.value.videoMode) return@launch
            if (enabled) {
                if (video.muxed == null && video.audioUrl == null) return@launch
                resumeAudio = audio.state.value.isPlaying
                mutableState.update { it.copy(videoMode = true) }
                val position = audio.positionMs()
                if (resumeAudio) audio.togglePlayPause()
                prepare(video, position, mode = true)
                player?.playWhenReady = resumeAudio && environment.visible
            } else {
                val position = player?.currentPosition ?: audio.positionMs()
                leaveVideoMode()
                prepare(video, position, mode = false)
                synchronize()
            }
        }
    }

    private fun leaveVideoMode() {
        if (!state.value.videoMode) return
        val position = player?.currentPosition ?: audio.positionMs()
        mutableState.update { it.copy(videoMode = false) }
        player?.volume = 0f
        if (audio.state.value.current?.id == state.value.songId) {
            audio.seekTo(position.coerceIn(0, audio.state.value.durationMs.coerceAtLeast(0)))
            if (resumeAudio && !audio.state.value.isPlaying) audio.togglePlayPause()
        }
        resumeAudio = false
    }

    private fun unavailable(songId: String?) {
        leaveVideoMode()
        player?.stop()
        player?.clearMediaItems()
        selected = null
        suspendedPosition = null
        mutableState.value = VideoBackgroundState(status = VideoStatus.UNAVAILABLE, songId = songId)
    }

    /** Pre-resuelve únicamente URLs de i+1; O(k) fuentes, sin preparar ni descargar medios. */
    private fun prefetchNext() {
        if (!environment.wifi) {
            prefetch?.cancel()
            prefetchedId = null
            return
        }
        val audioState = audio.state.value
        val next = audioState.queue.getOrNull(audioState.currentIndex + 1) ?: return
        if (prefetchedId == next.id) return
        prefetch?.cancel()
        prefetchedId = next.id
        prefetch = session?.launch { withContext(Dispatchers.IO) { sources.resolve(next) } }
    }
}
