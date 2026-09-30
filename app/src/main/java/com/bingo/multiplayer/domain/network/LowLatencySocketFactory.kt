package com.bingo.multiplayer.domain.network

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Socket factory tuned specifically for ultra-low latency real-time gaming:
 * 1. Disables Nagle's algorithm (tcpNoDelay = true) so packets flush immediately without kernel buffering.
 * 2. Sets trafficClass = 0x10 (IPTOS_LOWDELAY) to request priority queuing on routers and cellular radios.
 */
class LowLatencySocketFactory : SocketFactory() {
    private val defaultFactory: SocketFactory = SocketFactory.getDefault()

    private fun tuneSocket(socket: Socket): Socket {
        try {
            socket.tcpNoDelay = true // Disables 40-100ms Nagle buffering
            socket.trafficClass = 0x10 // Low-delay QoS priority
            socket.sendBufferSize = 8192
            socket.receiveBufferSize = 8192
        } catch (_: Exception) {}
        return socket
    }

    override fun createSocket(): Socket {
        return tuneSocket(defaultFactory.createSocket())
    }

    override fun createSocket(host: String?, port: Int): Socket {
        return tuneSocket(defaultFactory.createSocket(host, port))
    }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        return tuneSocket(defaultFactory.createSocket(host, port, localHost, localPort))
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket {
        return tuneSocket(defaultFactory.createSocket(host, port))
    }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
        return tuneSocket(defaultFactory.createSocket(address, port, localAddress, localPort))
    }
}
