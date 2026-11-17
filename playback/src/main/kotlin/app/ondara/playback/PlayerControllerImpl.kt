package app.ondara.playback

import android.content.ComponentName
import android.content.Context
import android.media.audiofx.Equalizer
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.ondara.core.data.SettingsCache
import app.ondara.core.domain.BuildFlowQueueUseCase
import app.ondara.core.domain.LibraryRepository
import app.ondara.core.domain.PlayerController
import app.ondara.core.domain.QueueShuffler
import app.ondara.core.model.EqualizerBand
import app.ondara.core.model.PlaybackContext
import app.ondara.core.model.PlayerState
import app.ondara.core.model.RepeatMode
import app.ondara.core.model.Song
import app.ondara.core.model.UserSettings
import app.ondara.source.resolver.StreamResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementación de [PlayerController] sobre un único ExoPlayer del proceso.
 * `PlaybackService` envuelve ese mismo reproductor en una MediaSession (notificación, pantalla de
 * bloqueo, Android Auto). Todas las operaciones sobre el reproductor ocurren en el hilo principal.
 */
@OptIn(UnstableApi::class)
@Singleton
class PlayerControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val factory: PlayerFactory,
    private val resolver: StreamResolver,
    private val settings: SettingsCache,
    private val library: LibraryRepository,
    private val buildFlow: BuildFlowQueueUseCase,
    meter: AmplitudeMeter,
) : PlayerController, Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    override val amplitude: StateFlow<Float> = meter.amplitude

    private val songs = HashMap<String, Song>()
    private val retried = HashSet<String>()
    private var originalOrder: List<String>? = null
    private var sleepJob: Job? = null
    private var flowJob: Job? = null
    private var serviceRequested = false
    private var equalizer: Equalizer? = null
    private var normalizationGain = 1f
    private var fadeGain = 1f
    private var crossfadingFrom: String? = null

    /** Reproductor principal; lo comparte la MediaSession del servicio. */
    val player: ExoPlayer by lazy {
        factory.create(handleAudioFocus = true).also { p ->
            p.addListener(this)
            scope.launch { settings.state.collect(::applySettings) }
            scope.launch { crossfadeLoop() }
        }
    }

    /** Segundo reproductor: solo suena la cola de la canción saliente durante un crossfade. */
    private val outgoing: ExoPlayer by lazy { factory.create(handleAudioFocus = false) }

    // ---- PlayerController -------------------------------------------------------------------

    override fun positionMs(): Long = if (_state.value.currentIndex >= 0) player.currentPosition.coerceAtLeast(0) else 0

    override fun positionFlow(periodMs: Long): Flow<Long> = flow {
        while (true) {
            emit(positionMs())
            delay(periodMs)
        }
    }.flowOn(Dispatchers.Main)

    override fun play(songs: List<Song>, startIndex: Int, shuffle: Boolean, context: PlaybackContext?) {
        if (songs.isEmpty()) return
        ensureService()
        val index = startIndex.coerceIn(0, songs.lastIndex)
        val ordered = if (shuffle) QueueShuffler.shuffle(songs, index) else songs
        originalOrder = if (shuffle) songs.map { it.id } else null
        register(ordered)
        retried.clear()
        player.setMediaItems(ordered.map { it.toMediaItem() }, if (shuffle) 0 else index, 0)
        player.prepare()
        player.play()
        _state.update { it.copy(shuffle = shuffle, error = null, context = context) }
    }

    override fun playNext(song: Song) {
        if (player.mediaItemCount == 0) return play(listOf(song))
        register(listOf(song))
        player.addMediaItem(player.currentMediaItemIndex + 1, song.toMediaItem())
    }

    override fun addToQueue(songs: List<Song>) {
        if (player.mediaItemCount == 0) return play(songs)
        register(songs)
        player.addMediaItems(songs.map { it.toMediaItem() })
    }

    override fun togglePlayPause() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
        if (player.isPlaying || player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) player.pause() else player.play()
    }

    override fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    override fun previous() {
        if (player.currentPosition > 4_000 || !player.hasPreviousMediaItem()) player.seekTo(0) else player.seekToPreviousMediaItem()
    }

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun seekBy(deltaMs: Long) {
        val position = positionMs()
        val duration = state.value.durationMs.coerceAtLeast(0)
        val target = if (deltaMs > 0 && position > Long.MAX_VALUE - deltaMs) Long.MAX_VALUE else position + deltaMs
        player.seekTo(target.coerceIn(0, duration))
    }

    override fun skipToIndex(index: Int) {
        if (index in 0 until player.mediaItemCount) {
            player.seekTo(index, 0)
            player.play()
        }
    }

    override fun moveQueueItem(from: Int, to: Int) {
        if (from in 0 until player.mediaItemCount && to in 0 until player.mediaItemCount) player.moveMediaItem(from, to)
    }

    override fun removeQueueItem(index: Int) {
        if (index in 0 until player.mediaItemCount) player.removeMediaItem(index)
    }

    /**
     * Baraja físicamente la cola (Fisher–Yates, O(n)) dejando la canción actual en la posición 0
     * sin interrumpirla; al desactivar se restaura el orden original.
     */
    override fun toggleShuffle() {
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        if (count < 2 || current < 0) return _state.update { it.copy(shuffle = !it.shuffle) }
        val items = List(count) { player.getMediaItemAt(it) }
        val saved = originalOrder
        if (saved == null) {
            originalOrder = items.map { it.mediaId }
            val order = QueueShuffler.shuffledOrder(count, current)
            player.moveMediaItem(current, 0)
            player.replaceMediaItems(1, count, order.drop(1).map { items[it] })
            _state.update { it.copy(shuffle = true) }
        } else {
            originalOrder = null
            val byId = items.associateBy { it.mediaId }
            val currentId = items[current].mediaId
            // Lo añadido tras barajar no estaba en el orden original: va al final.
            val restored = (saved + items.map { it.mediaId }).distinct().mapNotNull(byId::get)
            val target = restored.indexOfFirst { it.mediaId == currentId }.coerceAtLeast(0)
            player.moveMediaItem(current, 0)
            player.replaceMediaItems(1, count, restored.filter { it.mediaId != currentId })
            player.moveMediaItem(0, target)
            _state.update { it.copy(shuffle = false) }
        }
    }

    override fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun startRadio(seed: Song) {
        play(listOf(seed), context = PlaybackContext("Radio", "Radio de ${seed.title}"))
        extendWithFlow(force = true)
    }

    override fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _state.update { it.copy(sleepTimerEndsAtMs = null) }
            return
        }
        val end = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepTimerEndsAtMs = end) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            player.pause()
            _state.update { it.copy(sleepTimerEndsAtMs = null) }
        }
    }

    override fun equalizerBands(): List<EqualizerBand> {
        val eq = ensureEqualizer() ?: return emptyList()
        return runCatching {
            val range = eq.bandLevelRange
            List(eq.numberOfBands.toInt()) { i ->
                EqualizerBand(i, eq.getCenterFreq(i.toShort()) / 1000, range[0].toInt(), range[1].toInt())
            }
        }.getOrDefault(emptyList())
    }

    /** La usan el servicio (Android Auto) y el widget para resolver ids a canciones conocidas. */
    fun register(list: List<Song>) = list.forEach { songs[it.id] = it }

    fun knownSong(id: String): Song? = songs[id]

    // ---- Player.Listener --------------------------------------------------------------------

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED,
            )
        ) {
            publish(queueChanged = events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION))
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId ?: return
        _state.update { it.copy(error = null) }
        val song = songs[id] ?: mediaItem.toSong().also { songs[id] = it }
        scope.launch { runCatching { library.recordPlay(song) } }
        updateNormalization(id)
        prefetchNext()
        extendWithFlow(force = false)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // Con la canción ya sonando su entrada de caché existe: es el momento de leer la loudness.
        if (playbackState == Player.STATE_READY) {
            player.currentMediaItem?.mediaId?.let { id ->
                retried.remove(id)
                updateNormalization(id)
            }
            prefetchNext()
        }
    }

    /**
     * HTTP 403/410 (URL caducada o rechazada): se invalida la caché y se reintenta **una sola vez**
     * con la siguiente estrategia, conservando la posición. Si vuelve a fallar se salta la canción.
     */
    override fun onPlayerError(error: PlaybackException) {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        val http = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val expired = http?.responseCode in RECOVERABLE_HTTP || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        if (expired && retried.add(id)) {
            val position = player.currentPosition
            val index = player.currentMediaItemIndex
            val quality = settings.value.streamQuality
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { resolver.resolveAfterFailure(id, quality, resolver.cached(id, quality)) }
                    player.seekTo(index, position)
                    player.prepare()
                    player.play()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(e.message)
                }
            }
        } else {
            fail(error.cause?.message ?: error.message)
        }
    }

    private fun fail(message: String?) {
        _state.update { it.copy(error = message ?: "No se pudo reproducir", isBuffering = false) }
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    // ---- Internos ---------------------------------------------------------------------------

    private fun publish(queueChanged: Boolean) {
        _state.update { old ->
            val queue = if (queueChanged || old.queue.size != player.mediaItemCount) {
                List(player.mediaItemCount) { i -> player.getMediaItemAt(i).let { songs[it.mediaId] ?: it.toSong() } }
            } else {
                old.queue
            }
            old.copy(
                queue = queue,
                currentIndex = if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady,
                durationMs = player.duration.takeIf { it > 0 } ?: (queue.getOrNull(player.currentMediaItemIndex)?.durationSec ?: 0) * 1000L,
                repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    else -> RepeatMode.OFF
                },
            )
        }
        OndaraWidget.update(context, _state.value)
    }

    /** Pre-resuelve la canción i+1 al empezar la i: el salto pasa de O(red) a un acierto de caché O(1). */
    private fun prefetchNext() {
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex < 0) return
        val id = player.getMediaItemAt(nextIndex).mediaId
        val quality = settings.value.streamQuality
        scope.launch(Dispatchers.IO) { resolver.prefetch(id, quality) }
    }

    private fun extendWithFlow(force: Boolean) {
        if (!force && (!settings.value.ondaraFlow || player.repeatMode != Player.REPEAT_MODE_OFF)) return
        if (player.mediaItemCount - player.currentMediaItemIndex > 2 || flowJob?.isActive == true) return
        val seed = player.currentMediaItem?.let { songs[it.mediaId] } ?: return
        val existing = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
        flowJob = scope.launch {
            val more = withContext(Dispatchers.IO) { runCatching { buildFlow(seed, existing) }.getOrDefault(emptyList()) }
            if (more.isNotEmpty()) addToQueue(more)
        }
    }

    private fun updateNormalization(id: String) {
        val s = settings.value
        normalizationGain = if (s.normalizeVolume) Gain.normalization(resolver.cached(id, s.streamQuality)?.loudnessDb) else 1f
        applyVolume()
    }

    private fun applyVolume() {
        player.volume = normalizationGain * fadeGain
    }

    private fun applySettings(s: UserSettings) {
        player.skipSilenceEnabled = s.skipSilence
        player.currentMediaItem?.mediaId?.let(::updateNormalization)
        val eq = if (s.equalizer.enabled) ensureEqualizer() else equalizer
        runCatching {
            eq?.enabled = s.equalizer.enabled
            if (s.equalizer.enabled && eq != null) {
                val range = eq.bandLevelRange
                s.equalizer.bandLevelsMb.take(eq.numberOfBands.toInt()).forEachIndexed { i, level ->
                    eq.setBandLevel(i.toShort(), level.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                }
            }
        }
    }

    private fun ensureEqualizer(): Equalizer? {
        equalizer?.let { return it }
        val session = player.audioSessionId
        if (session == 0) return null
        return runCatching { Equalizer(0, session) }.getOrNull().also { equalizer = it }
    }

    /**
     * Crossfade real con dos reproductores: cuando a la canción le quedan `crossfadeSeconds`, su cola
     * pasa al reproductor secundario (fade-out `cos`) y el principal salta a la siguiente (fade-in `sin`):
     * curva de igual potencia. Sin crossfade, ExoPlayer ya encadena las pistas sin huecos (gapless).
     */
    private suspend fun crossfadeLoop() {
        while (scope.isActive) {
            delay(200)
            val seconds = settings.value.crossfadeSeconds
            if (seconds <= 0 || !player.isPlaying || player.repeatMode == Player.REPEAT_MODE_ONE || !player.hasNextMediaItem()) continue
            val duration = player.duration
            val item = player.currentMediaItem ?: continue
            if (duration <= seconds * 2000L || crossfadingFrom == item.mediaId) continue
            val remaining = duration - player.currentPosition
            if (remaining > seconds * 1000L) continue
            crossfadingFrom = item.mediaId
            val outGain = normalizationGain
            outgoing.setMediaItem(item, player.currentPosition)
            outgoing.volume = outGain
            outgoing.prepare()
            outgoing.play()
            fadeGain = 0f
            applyVolume()
            player.seekToNextMediaItem()
            val total = remaining.coerceAtLeast(500)
            val start = System.currentTimeMillis()
            while (true) {
                val t = (System.currentTimeMillis() - start).toFloat() / total
                if (t >= 1f) break
                fadeGain = Gain.fadeIn(t)
                applyVolume()
                outgoing.volume = outGain * Gain.fadeOut(t)
                delay(40)
            }
            fadeGain = 1f
            applyVolume()
            outgoing.stop()
            outgoing.clearMediaItems()
        }
    }

    private fun ensureService() {
        if (serviceRequested) return
        serviceRequested = true
        // Conectar un MediaController arranca PlaybackService, que publica la sesión y la notificación.
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        MediaController.Builder(context, token).buildAsync()
    }

    private companion object {
        val RECOVERABLE_HTTP = setOf(403, 404, 410, 416)
    }
}
