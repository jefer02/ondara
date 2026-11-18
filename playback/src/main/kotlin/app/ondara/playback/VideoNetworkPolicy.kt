package app.ondara.playback

object VideoNetworkPolicy {
    /** Decisión O(1); Wi-Fi/Ethernet deben además ser redes no medidas. */
    fun allowed(enabled: Boolean, wifiOnly: Boolean, connected: Boolean, unmetered: Boolean, wifiOrEthernet: Boolean): Boolean =
        enabled && connected && (!wifiOnly || unmetered && wifiOrEthernet)
}
