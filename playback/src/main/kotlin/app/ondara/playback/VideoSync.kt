package app.ondara.playback

import kotlin.math.abs

object VideoSync {
    /**
     * Corrección O(1): tolera 150 ms y conserva el último fotograma de vídeos más cortos.
     * En pausa solo se sincronizan los saltos explícitos desde el controlador.
     */
    fun correction(audioMs: Long, videoMs: Long, videoDurationMs: Long, audioPlaying: Boolean): Long? {
        if (!audioPlaying) return null
        val target = audioMs.coerceAtLeast(0)
        if (videoDurationMs > 0 && target >= videoDurationMs) return null
        return target.takeIf { abs(it - videoMs) > 150 }
    }
}

object VideoNetworkPolicy {
    /** Decisión O(1); Wi-Fi/Ethernet deben además ser redes no medidas. */
    fun allowed(enabled: Boolean, wifiOnly: Boolean, connected: Boolean, unmetered: Boolean, wifiOrEthernet: Boolean): Boolean =
        enabled && connected && (!wifiOnly || unmetered && wifiOrEthernet)
}
