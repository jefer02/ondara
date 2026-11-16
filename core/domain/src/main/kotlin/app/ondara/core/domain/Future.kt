package app.ondara.core.domain

import app.ondara.core.model.Song
import kotlinx.coroutines.flow.Flow

/*
 * Contratos de funciones fuera del alcance de la primera versión.
 * No tienen implementación todavía; existen para que la arquitectura ya tenga el hueco
 * (ver "Trabajo futuro" en AGENT.md).
 */

/** Reconocimiento de canciones a partir de una muestra de audio (estilo Shazam). */
interface SongRecognizer {
    /** @param pcm16Mono muestra PCM 16-bit mono a [sampleRate] Hz. */
    suspend fun recognize(pcm16Mono: ShortArray, sampleRate: Int): Song?
}

/** Escucha en grupo sincronizada; requiere un servidor de señalización propio. */
interface ListenTogetherSession {
    val participants: Flow<List<String>>
    suspend fun create(): String
    suspend fun join(code: String)
    suspend fun leave()
    suspend fun broadcast(event: SyncEvent)
    val events: Flow<SyncEvent>
}

sealed interface SyncEvent {
    data class Play(val songId: String, val positionMs: Long, val atEpochMs: Long) : SyncEvent
    data class Pause(val positionMs: Long) : SyncEvent
    data class Seek(val positionMs: Long, val atEpochMs: Long) : SyncEvent
}
