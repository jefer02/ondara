// Adaptado de Echo Music (https://github.com/EchoMusicApp/Echo-Music), GPL-3.0.
package app.ondara.playback

import android.content.Context
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.ondara.core.data.SettingsCache
import app.ondara.core.domain.CanvasController
import app.ondara.core.domain.PlayerController
import app.ondara.core.model.Canvas
import app.ondara.core.model.CanvasSource
import app.ondara.core.model.CanvasState
import app.ondara.core.model.CanvasSurfaces
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Dos ranuras mudas, independientes de la posición y del estado de reproducción del audio. Confinadas a Main. */
@OptIn(UnstableApi::class)
@Singleton
class CanvasControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audio: PlayerController,
    private val repository: CanvasRepositoryImpl,
    private val mediaCache: CanvasMediaCache,
    private val settings: SettingsCache,
) : CanvasController {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<CanvasState>(CanvasState.Idle)
    override val state = mutableState.asStateFlow()
    private val mutableSurfaces = MutableStateFlow(CanvasSurfaces())
    override val surfaces = mutableSurfaces.asStateFlow()
    private val targets = arrayOfNulls<Surface>(2)
    private val players = arrayOfNulls<ExoPlayer>(2)
    private val canvases = arrayOfNulls<Canvas>(2)
    private val dimensions = Array(2) { 0 to 0 }
    private var session: CoroutineScope? = null
    private var load: Job? = null
    private var prefetch: Job? = null
    private var prefetched: String? = null
    private var songId: String? = null
    private var policy: Pair<Boolean, Boolean>? = null
    private var pending: Int? = null
    private var transition: Job? = null
    private val environment = VideoEnvironment(context) { main.launch { reconcile() } }

    override fun start() { main.launch {
        if (session != null) return@launch
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        session = scope
        repeat(2) { slot ->
            players[slot] = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(mediaCache.factory)).build().also {
                it.volume = 0f
                it.repeatMode = Player.REPEAT_MODE_ONE
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setForceHighestSupportedBitrate(true).build()
                it.setVideoSurface(targets[slot])
                it.addListener(listener(slot))
            }
        }
        environment.start()
        scope.launch { audio.state.collect { reconcile() } }
        scope.launch { settings.state.collect { reconcile() } }
        scope.launch { while (isActive) { delay(35); loop() } }
    } }

    override fun stop() { main.launch {
        if (session == null) return@launch
        session?.cancel()
        session = null
        environment.stop()
        repeat(2) { players[it]?.release(); players[it] = null; canvases[it] = null }
        load = null
        prefetch = null
        transition = null
        prefetched = null
        songId = null
        policy = null
        pending = null
        mutableState.value = CanvasState.Idle
        mutableSurfaces.value = CanvasSurfaces()
    } }

    override fun setSurface(slot: Int, surface: Any?) { main.launch {
        require(slot in 0..1)
        targets[slot] = surface as? Surface
        if (surface == null) mutableSurfaces.update { it.copy(firstFrame = it.firstFrame - slot) }
        players[slot]?.setVideoSurface(targets[slot])
    } }

    private fun listener(slot: Int) = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            dimensions[slot] = videoSize.width to videoSize.height
            if (slot == surfaces.value.active) {
                mutableSurfaces.update { it.copy(width = videoSize.width, height = videoSize.height) }
            }
        }
        override fun onRenderedFirstFrame() {
            if (canvases[slot] == null || targets[slot] == null) return
            mutableSurfaces.update { it.copy(firstFrame = it.firstFrame + slot) }
            if (pending == slot) activate(slot)
        }
        override fun onPlayerError(error: PlaybackException) {
            if (pending != slot && surfaces.value.active != slot) return
            // Una URL caducada no debe quedarse en caché: la próxima vez se resuelve de nuevo.
            canvases[slot]?.songId?.let { id -> main.launch { repository.invalidate(id) } }
            unavailable()
        }
    }

    /** Reconcilia identidad, políticas y visibilidad en O(1); nunca consulta la posición del audio. */
    @Suppress("CyclomaticComplexMethod") // una sola máquina de estados: identidad, política, visibilidad y precarga
    private fun reconcile() {
        val scope = session ?: return
        val song = audio.state.value.current
        val newPolicy = settings.value.videoBackground to environment.allowed(true, settings.value.videoWifiOnly)
        val changed = songId != song?.id || policy != newPolicy
        if (changed) {
            songId = song?.id
            policy = newPolicy
            load?.cancel()
            transition?.cancel()
            transition = null
            pending = null
            prefetch?.cancel()
            prefetched = null
            if (song == null || !settings.value.videoBackground) { unavailable(); return }
            load = scope.launch {
                repository.observeCanvas(song).collect { result ->
                    when (result) {
                        is CanvasState.Ready -> prepare(1 - surfaces.value.active, result.canvas)
                        is CanvasState.None -> unavailable()
                        else -> if (state.value !is CanvasState.Ready) mutableState.value = result
                    }
                }
            }
        }
        if (!environment.visible) {
            players.forEach { it?.pause() }
            prefetch?.cancel()
            prefetched = null
            return
        }
        players.forEachIndexed { slot, player ->
            player?.playWhenReady = canvases[slot] != null && (slot == surfaces.value.active || slot == pending || transition != null)
        }
        val next = audio.state.value.let { it.queue.getOrNull(it.currentIndex + 1) }
        if (settings.value.videoBackground && next != null && prefetched != next.id) {
            prefetch?.cancel()
            prefetched = next.id
            prefetch = scope.launch { repository.prefetch(next) }
        }
    }

    private fun prepare(slot: Int, canvas: Canvas) {
        if (!repository.permitted(canvas)) { unavailable(); return }
        pending = slot
        canvases[slot] = canvas
        dimensions[slot] = canvas.width to canvas.height
        mutableSurfaces.update { it.copy(firstFrame = it.firstFrame - slot) }
        players[slot]?.apply {
            setMediaItem(canvas.mediaItem())
            prepare()
            playWhenReady = environment.visible
        }
    }

    private fun activate(slot: Int) {
        pending = null
        val canvas = canvases[slot] ?: return
        mutableState.value = CanvasState.Ready(canvas)
        mutableSurfaces.update { it.copy(active = slot, width = dimensions[slot].first, height = dimensions[slot].second) }
        transition?.cancel()
        transition = session?.launch {
            delay(CROSSFADE_MS)
            val spare = 1 - slot
            players[spare]?.pause()
            // Preparar el inicio con antelación deja el decodificador listo para el siguiente bucle.
            if (canvas.source == CanvasSource.YOUTUBE_CLIP) {
                canvases[spare] = canvas
                mutableSurfaces.update { it.copy(firstFrame = it.firstFrame - spare) }
                players[spare]?.setMediaItem(canvas.mediaItem())
                players[spare]?.prepare()
            }
            transition = null
        }
    }

    /** Cruza al inicio preparado 350 ms antes del final; O(1) por tick, sin deriva respecto al audio. */
    @Suppress("ReturnCount") // cada guarda corta el tick sin trabajo
    private fun loop() {
        if (!environment.visible || pending != null || transition != null || load?.isActive == true) return
        if (state.value !is CanvasState.Ready) return
        val slot = surfaces.value.active
        val canvas = canvases[slot] ?: return
        val end = canvas.clipEndMs ?: return
        val player = players[slot] ?: return
        if (player.currentPosition < end - canvas.clipStartMs - CROSSFADE_MS) return
        val spare = 1 - slot
        pending = spare
        players[spare]?.play()
        if (spare in surfaces.value.firstFrame) activate(spare)
    }

    private fun unavailable() {
        transition?.cancel()
        transition = null
        pending = null
        repeat(2) { players[it]?.stop(); players[it]?.clearMediaItems(); canvases[it] = null }
        mutableSurfaces.value = CanvasSurfaces()
        mutableState.value = CanvasState.None(songId)
    }

    private companion object { const val CROSSFADE_MS = 350L }
}
