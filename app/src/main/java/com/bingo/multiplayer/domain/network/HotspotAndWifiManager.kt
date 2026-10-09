package com.bingo.multiplayer.domain.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manages Wi-Fi, Hotspot states, and automated Wi-Fi connection
 * for Nearby Network (LAN) peer-to-peer gameplay.
 */
object HotspotAndWifiManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Safely unregisters any active NetworkCallback and unbinds process network socket routing.
     * Prevents NetworkCallback exhaustion leaks and restores standard cellular/Wi-Fi routing for the app.
     */
    fun disconnectFromWifi(context: Context) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            activeNetworkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        activeNetworkCallback = null
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {}
    }

    // ── Wi-Fi State & Control ──

    fun isWifiEnabled(context: Context): Boolean {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.isWifiEnabled == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Prompts the user to turn on Wi-Fi.
     * Uses Android 10+ (API 29+) native Wi-Fi settings panel for a clean in-app sheet.
     */
    fun promptEnableWifi(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(panelIntent)
            } else {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val handled = wifi?.setWifiEnabled(true) == true
                if (!handled) {
                    val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            }
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    // ── Hotspot State & Control ──

    private const val PREFS_HOTSPOT = "bingo_hotspot_prefs"
    private const val KEY_HOTSPOT_SSID = "saved_hotspot_ssid"
    private const val KEY_HOTSPOT_PASS = "saved_hotspot_password"

    /**
     * Checks if the phone's mobile hotspot / tethering is currently active.
     * Uses reflection on hidden API + kernel network interface discovery.
     */
    fun isHotspotEnabled(context: Context): Boolean {
        // 1. WifiManager.isWifiApEnabled
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val method = wifi?.javaClass?.getDeclaredMethod("isWifiApEnabled")
            method?.isAccessible = true
            val enabled = method?.invoke(wifi) as? Boolean
            if (enabled == true) return true
        } catch (_: Exception) {}

        // 2. WifiManager.getWifiApState (12 = ENABLING, 13 = ENABLED)
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val method = wifi?.javaClass?.getDeclaredMethod("getWifiApState")
            method?.isAccessible = true
            val state = method?.invoke(wifi) as? Int
            if (state == 12 || state == 13) return true
        } catch (_: Exception) {}

        // 3. Kernel socket network interface heuristic
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val name = iface.name.lowercase()
                if (!iface.isUp || iface.isLoopback) continue

                if (name.contains("ap") || name.contains("swlan") || name.contains("softap") || name.contains("tether")) {
                    return true
                }

                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    val host = addr.hostAddress ?: ""
                    if (host.startsWith("192.168.43.") || host.startsWith("192.168.44.") ||
                        host.startsWith("192.168.49.") || host.startsWith("192.168.50.") ||
                        host.startsWith("192.168.125.") || host.startsWith("192.168.137.") ||
                        host.startsWith("172.20.10.")) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}

        return false
    }

    /**
     * Opens Hotspot / Tethering settings on the device.
     */
    fun openHotspotSettings(context: Context) {
        val intents = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent("android.settings.WIFI_AP_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
    }

    /**
     * Retrieves the device's Hotspot SSID name if detectable, or saved preference.
     */
    fun getHotspotName(context: Context): String {
        val saved = context.getSharedPreferences(PREFS_HOTSPOT, Context.MODE_PRIVATE).getString(KEY_HOTSPOT_SSID, null)
        if (!saved.isNullOrBlank()) return saved

        val fallbackDeviceName = try {
            val devName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            if (!devName.isNullOrBlank()) devName else Build.MODEL
        } catch (_: Exception) {
            Build.MODEL
        }.ifBlank { "Android Hotspot" }

        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val method = wifi?.javaClass?.getDeclaredMethod("getWifiApConfiguration")
            method?.isAccessible = true
            val config = method?.invoke(wifi) as? WifiConfiguration
            val detected = config?.SSID?.replace("\"", "")
            if (!detected.isNullOrBlank()) detected else fallbackDeviceName
        } catch (_: Exception) {
            fallbackDeviceName
        }
    }

    fun getSavedHotspotPassword(context: Context): String {
        return context.getSharedPreferences(PREFS_HOTSPOT, Context.MODE_PRIVATE).getString(KEY_HOTSPOT_PASS, "") ?: ""
    }

    fun saveHotspotCredentials(context: Context, ssid: String, password: String) {
        context.getSharedPreferences(PREFS_HOTSPOT, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOTSPOT_SSID, ssid.trim())
            .putString(KEY_HOTSPOT_PASS, password.trim())
            .apply()
    }

    /**
     * Resolves the gateway IP of the currently connected network (i.e. the Host IP in a Hotspot).
     * Falls back to standard Android Hotspot gateway "192.168.43.1".
     */
    fun getGatewayIp(context: Context): String {
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            if (activeNetwork != null) {
                val lp = cm.getLinkProperties(activeNetwork)
                val routeGateway = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
                if (!routeGateway.isNullOrBlank() && routeGateway != "0.0.0.0") {
                    return routeGateway
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val dhcpServer = lp?.dhcpServerAddress?.hostAddress
                    if (!dhcpServer.isNullOrBlank() && dhcpServer != "0.0.0.0") {
                        return dhcpServer
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wifi?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val g = dhcp.gateway
                val ip = "${g and 0xFF}.${(g shr 8) and 0xFF}.${(g shr 16) and 0xFF}.${(g shr 24) and 0xFF}"
                if (ip != "0.0.0.0") return ip
            }
        } catch (_: Exception) {}

        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        val prefix = host.substringBeforeLast(".")
                        return "$prefix.1"
                    }
                }
            }
        } catch (_: Exception) {}

        return "192.168.43.1"
    }

    /**
     * Scans and returns nearby Wi-Fi AP SSIDs broadcasting in the air.
     */
    fun getNearbyWifiAps(context: Context): List<String> {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.scanResults
                ?.mapNotNull { it.SSID?.trim()?.removeSurrounding("\"") }
                ?.filter { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }
                ?.distinct() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Triggers a system Wi-Fi AP scan.
     */
    fun triggerWifiScan(context: Context) {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.startScan()
        } catch (_: Exception) {}
    }

    /**
     * Checks if the player's device is currently connected to the host's Wi-Fi / hotspot.
     */
    fun isConnectedToHost(context: Context, hostIp: String, hostSsid: String): Boolean {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifi?.connectionInfo
            val currentSsid = info?.ssid?.replace("\"", "") ?: ""
            if (currentSsid.isNotBlank() && hostSsid.isNotBlank() &&
                (currentSsid.equals(hostSsid, ignoreCase = true) || hostSsid.contains(currentSsid, ignoreCase = true) || currentSsid.contains(hostSsid, ignoreCase = true))) {
                return true
            }
        } catch (_: Exception) {}

        // Check if host IP is reachable over TCP P2P port 8999
        if (hostIp.isNotBlank()) {
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(hostIp, 8999), 500)
                socket.close()
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    /**
     * Programmatically connects the joiner's device to the host's Wi-Fi hotspot.
     * On Android 10+ uses WifiNetworkSpecifier with ConnectivityManager.
     * Passes the resolved gateway/host IP into [onConnected].
     */
    fun connectToHostWifi(
        context: Context,
        ssid: String,
        password: String,
        onConnected: (resolvedGatewayIp: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val cleanSsid = ssid.trim().removeSurrounding("\"")
        val cleanPass = password.trim().removeSurrounding("\"")

        if (cleanSsid.isBlank()) {
            onError("SSID cannot be blank")
            return
        }

        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            onError("ConnectivityManager unavailable")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val specifierBuilder = WifiNetworkSpecifier.Builder()
                    .setSsid(cleanSsid)

                if (cleanPass.isNotBlank()) {
                    specifierBuilder.setWpa2Passphrase(cleanPass)
                }

                val specifier = specifierBuilder.build()
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()

                disconnectFromWifi(context)

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        try {
                            cm.bindProcessToNetwork(network)
                        } catch (_: Exception) {}

                        // Allow brief moment for IP routing & DHCP to finalize
                        scope.launch(Dispatchers.IO) {
                            kotlinx.coroutines.delay(400L)
                            var gateway = ""
                            try {
                                val lp = cm.getLinkProperties(network)
                                gateway = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: ""
                                if (gateway.isBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    gateway = lp?.dhcpServerAddress?.hostAddress ?: ""
                                }
                            } catch (_: Exception) {}
                            if (gateway.isBlank()) {
                                gateway = getGatewayIp(context)
                            }
                            val finalGateway = if (gateway.isNotBlank() && gateway != "0.0.0.0") gateway else "192.168.43.1"
                            scope.launch(Dispatchers.Main) {
                                onConnected(finalGateway)
                            }
                        }
                    }

                    override fun onUnavailable() {
                        super.onUnavailable()
                        disconnectFromWifi(context)
                        scope.launch(Dispatchers.Main) {
                            onError("Connection request cancelled or timed out")
                        }
                    }
                }
                activeNetworkCallback = callback
                cm.requestNetwork(request, callback)
            } catch (e: Exception) {
                Log.w("HotspotWifi", "WifiNetworkSpecifier failed: ${e.message}")
                onError(e.message ?: "Could not connect to network")
            }
        } else {
            // Legacy Wi-Fi connection
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val conf = WifiConfiguration().apply {
                    SSID = "\"$cleanSsid\""
                    if (cleanPass.isNotBlank()) {
                        preSharedKey = "\"$cleanPass\""
                    } else {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    }
                }
                val netId = wifi?.addNetwork(conf) ?: -1
                if (netId != -1) {
                    wifi?.disconnect()
                    wifi?.enableNetwork(netId, true)
                    wifi?.reconnect()
                    scope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(800L)
                        val gateway = getGatewayIp(context)
                        scope.launch(Dispatchers.Main) {
                            onConnected(gateway)
                        }
                    }
                } else {
                    onError("Failed to add Wi-Fi configuration")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Connection failed")
            }
        }
    }

    /**
     * Resolves the primary local IPv4 address across Hotspot, Wi-Fi, and active network interfaces.
     */
    fun getLocalIpAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val ipv4List = mutableListOf<Pair<String, String>>()

            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        ipv4List.add(name to host)
                    }
                }
            }

            // 1. Hotspot interfaces
            ipv4List.firstOrNull { (name, ip) ->
                name.contains("ap") || name.contains("swlan") || name.contains("softap") || name.contains("tether") ||
                        ip.startsWith("192.168.43.") || ip.startsWith("192.168.44.") ||
                        ip.startsWith("192.168.49.") || ip.startsWith("192.168.50.") ||
                        ip.startsWith("192.168.125.") || ip.startsWith("192.168.137.") ||
                        ip.startsWith("172.20.10.")
            }?.second
            // 2. Wi-Fi / Ethernet interfaces
            ?: ipv4List.firstOrNull { (name, _) ->
                name.contains("wlan") || name.contains("wifi") || name.contains("eth")
            }?.second
            // 3. Non-cellular LAN interface
            ?: ipv4List.firstOrNull { (name, _) ->
                !name.contains("rmnet") && !name.contains("ccmni") && !name.contains("dummy") &&
                        !name.contains("pdp") && !name.contains("tun")
            }?.second
            // 4. Any IPv4 fallback
            ?: ipv4List.firstOrNull()?.second
            ?: "192.168.43.1"
        } catch (_: Exception) {
            "192.168.43.1"
        }
    }
}
