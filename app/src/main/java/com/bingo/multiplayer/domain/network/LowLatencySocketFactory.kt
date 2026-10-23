package com.bingo.multiplayer.domain.network

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

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
            socket.sendBufferSize = 16384
            socket.receiveBufferSize = 16384
            socket.soTimeout = 0
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

/**
 * SSL Socket factory tuned specifically for ultra-low latency encrypted real-time gaming:
 * 1. Disables Nagle's algorithm (tcpNoDelay = true).
 * 2. Sets trafficClass = 0x10 (IPTOS_LOWDELAY).
 */
class LowLatencySSLSocketFactory(
    private val delegate: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
) : SSLSocketFactory() {

    private fun tuneSocket(socket: Socket): Socket {
        try {
            socket.tcpNoDelay = true // Disables 40-100ms Nagle buffering
            socket.trafficClass = 0x10 // Low-delay QoS priority
            socket.sendBufferSize = 16384
            socket.receiveBufferSize = 16384
        } catch (_: Exception) {}
        return socket
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        tuneSocket(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(): Socket = tuneSocket(delegate.createSocket())
    override fun createSocket(host: String?, port: Int): Socket = tuneSocket(delegate.createSocket(host, port))
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        tuneSocket(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress?, port: Int): Socket = tuneSocket(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        tuneSocket(delegate.createSocket(address, port, localAddress, localPort))
}
