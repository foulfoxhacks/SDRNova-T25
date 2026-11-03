package com.sdrnova.t25.radio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.sdrnova.t25.playlist.PlaylistAccess
import com.sdrnova.t25.web.Dashboard
import com.sdrnova.t25.web.MiniHttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

data class DashboardState(
    val running: Boolean = false,
    val url: String? = null,
    val pin: String? = null,
    val error: String? = null,
)

/**
 * Serves the channel editor to browsers on the same Wi-Fi (or on the phone's own hotspot). It listens only on that
 * network's address, never on mobile data, and every API call needs a PIN that changes each time it starts.
 */
class WebDashboard(private val context: Context, private val playlists: PlaylistAccess) {
    private val current = MutableStateFlow(DashboardState())
    private var server: MiniHttpServer? = null

    val state: StateFlow<DashboardState> = current

    @Synchronized
    fun start() {
        if (server != null) return
        val address = localAddress() ?: run {
            current.value = DashboardState(error = "Connect the phone to Wi-Fi (or turn on its hotspot) to use the web dashboard.")
            return
        }
        val pin = Dashboard.newPin()
        val dashboard = Dashboard(playlists, pin)
        val started = try {
            MiniHttpServer(address, PORT, dashboard::handle)
        } catch (_: IOException) {
            // Something else has the port; any free one will do, the URL shows which.
            try {
                MiniHttpServer(address, 0, dashboard::handle)
            } catch (e: IOException) {
                current.value = DashboardState(error = "Couldn't start the web dashboard: ${e.message}")
                return
            }
        }
        server = started.start()
        current.value = DashboardState(running = true, url = "http://${address.hostAddress}:${started.port}", pin = pin)
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
        current.value = DashboardState()
    }

    /** The phone's IPv4 address on Wi-Fi or Ethernet, else on the hotspot it's sharing. */
    private fun localAddress(): Inet4Address? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        connectivity.activeNetwork?.let { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            val local = capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
            if (local) {
                connectivity.getLinkProperties(network)?.linkAddresses
                    ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull { !it.isLoopbackAddress }
                    ?.let { return it }
            }
        }
        // A hotspot's interface isn't a network the phone uses, so find it by name.
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && HOTSPOT_INTERFACES.any { prefix -> it.name.startsWith(prefix) } }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
        }.getOrNull()
    }

    private companion object {
        const val PORT = 8025
        val HOTSPOT_INTERFACES = listOf("ap", "swlan", "wlan", "softap")
    }
}
