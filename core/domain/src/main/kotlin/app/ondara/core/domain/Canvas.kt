package app.ondara.core.domain

import app.ondara.core.model.CanvasState
import app.ondara.core.model.CanvasSurfaces
import app.ondara.core.model.Song
import app.ondara.core.model.VideoOffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Resuelve el canvas con la cadena de proveedores (caché incluida). */
interface CanvasRepository {
    /** Emite Loading y después Ready o None. */
    fun observeCanvas(song: Song): Flow<CanvasState>

    /** Resuelve y precarga URL y primeros bytes de una canción futura. */
    suspend fun prefetch(song: Song)
}

/** Reproduce en bucle el canvas actual sobre dos superficies alternas. */
interface CanvasController {
    val state: StateFlow<CanvasState>
    val surfaces: StateFlow<CanvasSurfaces>
    val videoOffer: StateFlow<VideoOffer?>

    fun toggleFullVideo()

    /** Cine está en pantalla. Idempotente. */
    fun start()

    /** Cine se cerró: libera los reproductores. Idempotente. */
    fun stop()

    /** Superficie Android de la ranura 0 o 1, o null al destruirse. */
    fun setSurface(slot: Int, surface: Any?)
}
