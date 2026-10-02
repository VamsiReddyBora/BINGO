package com.bingo.multiplayer.domain.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Keep
@Serializable
data class LanDiscoveredGame(
    val hostId: String,
    val hostDisplayName: String,
    val hostUsername: String = "",
    val avatarUrl: String? = null,
    val boardSize: Int = 5,
    val roomCode: String, // Internal room session identifier, completely invisible to the user
    val hostIp: String = "",
    val ssid: String = "",
    val isInLobby: Boolean = false,
    val broadcastTimestamp: Long = System.currentTimeMillis(),
    val lastSeenTimestamp: Long = System.currentTimeMillis()
) {
    val isAlive: Boolean
        get() = (System.currentTimeMillis() - lastSeenTimestamp) < 4000L
}

/**
 * High-performance Zero-Configuration LAN & Nearby Network Auto-Discovery Manager.
 * Operates simultaneously over:
 * 1. Local Wi-Fi / Hotspot UDP Subnet Broadcasts (Port 9876) — zero internet required.
 * 2. Low-latency P2P cloud bridge topic (bingo/v3/lan_hosts) — for devices where multicast is restricted.
 * 
 * Guarantees 1-tap connection with ZERO room codes needed!
 */
class LanDiscoveryManager(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val brokerUrl = NetworkConfig.BROKER_URL
    private val udpPort = 9876

    private val _discoveredGames = MutableStateFlow<List<LanDiscoveredGame>>(emptyList())
    val discoveredGames: StateFlow<List<LanDiscoveredGame>> = _discoveredGames.asStateFlow()

    private val gamesCache = ConcurrentHashMap<String, LanDiscoveredGame>()

    private var broadcastJob: Job? = null
    private var udpListenJob: Job? = null
    private var mqttClient: MqttAsyncClient? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var isBroadcasting = false
    private var isDiscovering = false
    private var localPlayerId: String? = null
    private var currentBroadcastingRoomCode: String? = null
    private var currentBroadcastGameInfo: LanDiscoveredGame? = null

    /**
     * Starts broadcasting a local game on the Wi-Fi / Hotspot network.
     */
    fun startBroadcasting(
        host: Player,
        boardSize: Int,
        internalRoomCode: String,
        ssid: String = ""
    ) {
        stopBroadcasting()
        isBroadcasting = true
        localPlayerId = host.id
        currentBroadcastingRoomCode = internalRoomCode

        val gameInfo = LanDiscoveredGame(
            hostId = host.id,
            hostDisplayName = host.displayName,
            hostUsername = host.id,
            avatarUrl = host.avatarUrl,
            boardSize = boardSize,
            roomCode = internalRoomCode,
            hostIp = getLocalIpAddress(),
            ssid = ssid,
            isInLobby = false,
            broadcastTimestamp = System.currentTimeMillis()
        )
        currentBroadcastGameInfo = gameInfo

        broadcastJob = scope.launch(Dispatchers.IO) {
            var udpSocket: DatagramSocket? = null
            try {
                udpSocket = DatagramSocket().apply {
                    broadcast = true
                }
            } catch (e: Exception) {
                Log.w("LanDiscovery", "Could not bind UDP broadcast socket: ${e.message}")
            }

            // Also advertise on MQTT LAN channel
            startMqttPublisher(gameInfo)

            // Determine broadcast address (fallback to 255.255.255.255 if cannot compute)
            val broadcastAddr = try {
                getBroadcastAddress()
            } catch (e: Exception) {
                Log.w("LanDiscovery", "Failed to compute broadcast address, using 255.255.255.255: ${e.message}")
                InetAddress.getByName("255.255.255.255")
            }

            while (isActive && isBroadcasting) {
                val currentInfo = currentBroadcastGameInfo?.copy(broadcastTimestamp = System.currentTimeMillis()) ?: gameInfo
                val payload = json.encodeToString(currentInfo)

                // 1. Broadcast over UDP on LAN
                try {
                    val bytes = payload.toByteArray(StandardCharsets.UTF_8)
                    val packet = DatagramPacket(
                        bytes,
                        bytes.size,
                        broadcastAddr,
                        udpPort
                    )
                    udpSocket?.send(packet)
                } catch (ex: Exception) {
                    Log.w("LanDiscovery", "Failed to send broadcast packet: ${ex.message}")
                }

                delay(1200L)
            }

            try {
                udpSocket?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Updates ongoing broadcast to indicate whether the host has moved to the lobby.
     */
    fun updateLobbyState(isInLobby: Boolean) {
        currentBroadcastGameInfo = currentBroadcastGameInfo?.copy(
            isInLobby = isInLobby,
            broadcastTimestamp = System.currentTimeMillis()
        )
    }

    /**
     * Stops broadcasting.
     */
    fun stopBroadcasting() {
        isBroadcasting = false
        broadcastJob?.cancel()
        broadcastJob = null
        val codeToClear = currentBroadcastingRoomCode
        currentBroadcastingRoomCode = null

        scope.launch(Dispatchers.IO) {
            try {
                if (codeToClear != null && mqttClient?.isConnected == true) {
                    val topic = "bingo/v4/lan_hosts/$codeToClear"
                    val emptyMsg = MqttMessage(ByteArray(0)).apply {
                        qos = 1
                        isRetained = false
                    }
                    mqttClient?.publish(topic, emptyMsg)?.waitForCompletion(1000L)
                }
                mqttClient?.disconnect()
                mqttClient?.close()
            } catch (_: Exception) {}
            mqttClient = null
        }
    }

    /**
     * Starts listening for nearby games on the local Wi-Fi / Hotspot network.
     */
    fun startDiscovering(myPlayerId: String? = null) {
        if (myPlayerId != null) {
            localPlayerId = myPlayerId
        }
        if (isDiscovering) return
        isDiscovering = true
        gamesCache.clear()
        _discoveredGames.value = emptyList()

        acquireMulticastLock()

        // 1. Listen for UDP broadcasts
        udpListenJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(udpPort).apply {
                    reuseAddress = true
                    broadcast = true
                }
                val buffer = ByteArray(2048)

                while (isActive && isDiscovering) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        val dataStr = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                        val game = json.decodeFromString<LanDiscoveredGame>(dataStr)
                        onGameDiscovered(game)
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w("LanDiscovery", "UDP listener failed to bind: ${e.message}")
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {}
            }
        }

        // 2. Also listen for games advertised via the fallback LAN bridge
        startMqttSubscriber()

        // 3. Cleanup stale games every 1 second
        scope.launch(Dispatchers.IO) {
            while (isActive && isDiscovering) {
                delay(1000L)
                val now = System.currentTimeMillis()
                gamesCache.entries.removeIf { (_, g) -> (now - g.lastSeenTimestamp) > 4000L }
                _discoveredGames.value = gamesCache.values.sortedByDescending { it.lastSeenTimestamp }
            }
        }
    }

    /**
     * Stops discovering nearby games.
     */
    fun stopDiscovering() {
        isDiscovering = false
        udpListenJob?.cancel()
        udpListenJob = null
        releaseMulticastLock()

        scope.launch(Dispatchers.IO) {
            try {
                mqttClient?.disconnect()
                mqttClient?.close()
            } catch (_: Exception) {}
            mqttClient = null
        }
        gamesCache.clear()
        _discoveredGames.value = emptyList()
    }

    private fun onGameDiscovered(game: LanDiscoveredGame) {
        if (localPlayerId != null && game.hostId == localPlayerId) {
            // Ignore own game
            return
        }
        val now = System.currentTimeMillis()
        // Discard any stale packets from past sessions or invalid timestamps
        if (game.broadcastTimestamp <= 0L || (now - game.broadcastTimestamp) > 3000L) {
            return
        }
        gamesCache[game.roomCode] = game.copy(lastSeenTimestamp = now)
        gamesCache.entries.removeIf { (_, g) -> (now - g.lastSeenTimestamp) > 4000L }
        _discoveredGames.value = gamesCache.values.sortedByDescending { it.lastSeenTimestamp }
    }

    // Original MQTT publisher removed (duplicate)

    // Original MQTT subscriber removed (duplicate)

    // Restores getLocalIpAddress utility used by getBroadcastAddress
    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("BingoMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }

    private fun getBroadcastAddress(): InetAddress {
        return try {
            val localIp = getLocalIpAddress()
            if (localIp.isBlank()) {
                Log.w("LanDiscovery", "Local IP is blank, using 255.255.255.255 as broadcast address")
                InetAddress.getByName("255.255.255.255")
            } else {
                val parts = localIp.split('.')
                if (parts.size == 4) {
                    val broadcastIp = "${parts[0]}.${parts[1]}.${parts[2]}.255"
                    InetAddress.getByName(broadcastIp)
                } else {
                    Log.w("LanDiscovery", "Unexpected IP format '$localIp', using global broadcast")
                    InetAddress.getByName("255.255.255.255")
                }
            }
        } catch (e: Exception) {
            Log.w("LanDiscovery", "Failed to compute broadcast address: ${e.message}")
            InetAddress.getByName("255.255.255.255")
        }
    }

    // Updated MQTT publisher with logging
    private fun startMqttPublisher(game: LanDiscoveredGame) {
        scope.launch(Dispatchers.IO) {
            try {
                val clientId = "lan_host_${UUID.randomUUID().toString().take(8)}"
                val client = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
                mqttClient = client
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 3
                    socketFactory = LowLatencySocketFactory()
                }
                client.connect(options).waitForCompletion(2000L)
                Log.d("LanDiscovery", "MQTT publisher connected for room ${game.roomCode}")

                val topic = "bingo/v4/lan_hosts/${game.roomCode}"
                val payload = json.encodeToString(game)
                val msg = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = 1
                    isRetained = false
                }
                client.publish(topic, msg)
                Log.d("LanDiscovery", "Published MQTT for room ${game.roomCode} to $topic")
            } catch (ex: Exception) {
                Log.w("LanDiscovery", "Failed MQTT publish: ${ex.message}")
            }
        }
    }

    // Updated MQTT subscriber with logging
    private fun startMqttSubscriber() {
        scope.launch(Dispatchers.IO) {
            try {
                val clientId = "lan_disc_${UUID.randomUUID().toString().take(8)}"
                val client = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
                mqttClient = client
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 3
                    socketFactory = LowLatencySocketFactory()
                }
                client.connect(options).waitForCompletion(2000L)
                Log.d("LanDiscovery", "MQTT subscriber connected")
                client.subscribe("bingo/v4/lan_hosts/+", 1) { topic, message ->
                    try {
                        if (message.isRetained) {
                            Log.d("LanDiscovery", "Ignoring retained broker message on $topic")
                            return@subscribe
                        }
                        Log.d("LanDiscovery", "MQTT message received on $topic")
                        if (message.payload == null || message.payload.isEmpty()) {
                            val rCode = topic.substringAfterLast("/")
                            gamesCache.remove(rCode)
                            _discoveredGames.value = gamesCache.values.filter { it.isAlive }.sortedByDescending { it.lastSeenTimestamp }
                            return@subscribe
                        }
                        val payload = String(message.payload, StandardCharsets.UTF_8)
                        if (payload.isBlank()) {
                            val rCode = topic.substringAfterLast("/")
                            gamesCache.remove(rCode)
                            _discoveredGames.value = gamesCache.values.filter { it.isAlive }.sortedByDescending { it.lastSeenTimestamp }
                            return@subscribe
                        }
                        val game = json.decodeFromString<LanDiscoveredGame>(payload)
                        onGameDiscovered(game)
                    } catch (ex: Exception) {
                        Log.w("LanDiscovery", "MQTT message handling error: ${ex.message}")
                    }
                }
            } catch (ex: Exception) {
                Log.w("LanDiscovery", "MQTT subscriber error: ${ex.message}")
            }
        }
    }
}
