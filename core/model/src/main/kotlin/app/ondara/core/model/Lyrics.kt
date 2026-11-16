package app.ondara.core.model

import kotlinx.serialization.Serializable

@Serializable
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

@Serializable
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Vacío si el proveedor solo sincroniza por línea. */
    val words: List<LyricWord> = emptyList(),
    /** Traducción de la línea al idioma del usuario, si se pidió y existe. */
    val translation: String? = null,
)

@Serializable
data class Lyrics(
    val lines: List<LyricLine>,
    /** `false` = texto plano sin tiempos (todas las líneas con startMs = 0). */
    val synced: Boolean,
    val provider: String,
) {
    val wordSynced: Boolean get() = synced && lines.any { it.words.isNotEmpty() }

    /**
     * Índice de la línea activa en [positionMs] por búsqueda binaria sobre `startMs` (ordenados).
     *
     * Devuelve la última línea cuyo inicio es `<= positionMs`, o `-1` antes de la primera.
     *
     * Complejidad: **O(log n)** tiempo, **O(1)** espacio — se ejecuta en cada frame.
     */
    fun activeLineIndex(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

/** Datos que los proveedores necesitan para buscar una letra. */
data class LyricsQuery(val videoId: String, val title: String, val artist: String, val album: String?, val durationSec: Int)
