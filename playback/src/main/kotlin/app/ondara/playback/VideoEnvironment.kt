package app.ondara.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** Observadores registrados únicamente mientras está visible el reproductor Cine. */
internal class VideoEnvironment(private val context: Context, private val changed: () -> Unit) : DefaultLifecycleObserver {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val lifecycle get() = ProcessLifecycleOwner.get().lifecycle
    val visible: Boolean get() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && power.isInteractive
    private val capabilities get() = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    val wifi: Boolean get() = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = changed()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = changed()
    }

    fun allowed(enabled: Boolean, wifiOnly: Boolean): Boolean {
        val caps = capabilities
        return VideoNetworkPolicy.allowed(
            enabled, wifiOnly, caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            !connectivity.isActiveNetworkMetered,
            caps?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } == true,
        )
    }

    fun start() {
        connectivity.registerDefaultNetworkCallback(callback)
        context.registerReceiver(receiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) })
        lifecycle.addObserver(this)
    }

    fun stop() {
        connectivity.unregisterNetworkCallback(callback)
        context.unregisterReceiver(receiver)
        lifecycle.removeObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) = changed()
    override fun onStop(owner: LifecycleOwner) = changed()
}
