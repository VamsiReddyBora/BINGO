package com.bingo.multiplayer.domain.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import java.util.concurrent.TimeUnit

object NetworkConfig {
    /**
     * Primary low-latency MQTT broker endpoint with distributed global edge (EMQX Serverless Asia Pacific).
     */
    const val BROKER_URL = "ssl://p0812f88.ala.asia-southeast1.emqxsl.com:8883"
    const val MQTT_USERNAME = "Bora"
    const val MQTT_PASSWORD = "bora7989"

    /**
     * KeyValue cloud storage endpoint for user registry & backups.
     */
    const val KEYVALUE_API_URL = "https://keyvalue.immanuel.co/api/KeyVal"
    const val KEYVALUE_APP_KEY = "2464j24f"

    /**
     * Configures MqttConnectOptions with credentials and tuned low-latency socket factory.
     */
    fun applyMqttOptions(options: MqttConnectOptions) {
        if (MQTT_USERNAME.isNotBlank()) {
            options.userName = MQTT_USERNAME
            options.password = MQTT_PASSWORD.toCharArray()
        }
        options.socketFactory = if (BROKER_URL.startsWith("ssl://")) {
            LowLatencySSLSocketFactory()
        } else {
            LowLatencySocketFactory()
        }
    }

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
