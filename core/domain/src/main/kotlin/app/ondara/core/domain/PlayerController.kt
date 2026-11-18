package app.ondara.core.domain

import app.ondara.core.model.EqualizerBand
import app.ondara.core.model.PlaybackContext
import app.ondara.core.model.PlayerState
import app.ondara.core.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fachada del reproductor para las pantallas (patrón Observer vía [StateFlow]).
 * La implementa `:playback`; los features solo conocen esta interfaz.
 */
interface PlayerController {
    val state: StateFlow<PlayerState>

    /** Amplitud RMS del audio en curso, 0..1 (la usa el tema Neón para latir con el ritmo). */
    val amplitude: StateFlow<Float>

    /** Posición actual en ms; se consulta por frame, no se emite como flujo para no recomponer. */
    fun positionMs(): Long

    /** Emite la posición cada [periodMs] mientras haya suscriptores. */
    fun positionFlow(periodMs: Long = 250): Flow<Long>

    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false, context: PlaybackContext? = null)
    fun playNext(song: Song)
    fun addToQueue(songs: List<Song>)
    fun togglePlayPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun skipToIndex(index: Int)
    fun moveQueueItem(from: Int, to: Int)
    fun removeQueueItem(index: Int)
    fun toggleShuffle()
    fun cycleRepeat()

    /** Salta [deltaMs] hacia delante (o atrás, si es negativo) dentro de la canción actual. */
    fun seekBy(deltaMs: Long)

    /** Arranca una radio a partir de [seed]: la reproduce y encola relacionadas. */
    fun startRadio(seed: Song)

    /** `null` o 0 cancela el temporizador. */
    fun setSleepTimer(minutes: Int?)

    fun equalizerBands(): List<EqualizerBand>
}
