package com.bingo.multiplayer.domain.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object NetworkConfig {
    /**
     * Primary low-latency MQTT broker endpoint with distributed global edge.
     */
    const val BROKER_URL = "tcp://broker.emqx.io:1883"

    /**
     * KeyValue cloud storage endpoint for user registry & backups.
     */
    const val KEYVALUE_API_URL = "https://keyvalue.immanuel.co/api/KeyVal"
    const val KEYVALUE_APP_KEY = "2464j24f"

    /**
     * Shared high-performance HTTP client with warm connection pool.
     * Keeps TLS connections and sockets warm between calls to eliminate TCP/TLS handshake latency.
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
