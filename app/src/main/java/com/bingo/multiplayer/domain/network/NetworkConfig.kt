package com.bingo.multiplayer.domain.network

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object NetworkConfig {
    private const val TAG = "NetworkConfig"

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
     * Cloudflare Worker relay endpoint for secure background push notifications via FCM HTTP v1.
     * Keeps service account private key off the client binary.
     */
    const val FCM_RELAY_URL = "https://bingo-fcm-relay.vamsireddy2534.workers.dev/send"

    /**
     * Current client application version metadata.
     */
    const val APP_VERSION_NAME = "1.4"
    const val APP_VERSION_CODE = 40

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
     * Resilient DNS provider that first delegates to system DNS, and if the mobile carrier / local ISP
     * fails or drops host resolution (e.g. UnknownHostException on api.github.com or vamsireddybora.github.io),
     * resolves using anycast IPs and backup DNS.
     */
    class ResilientDns : Dns {
        private val fallbackDnsMap = mapOf(
            "api.github.com" to listOf(
                "20.207.73.85",
                "140.82.112.5",
                "140.82.113.5",
                "140.82.114.5",
                "20.201.28.151"
            ),
            "vamsireddybora.github.io" to listOf(
                "185.199.108.153",
                "185.199.109.153",
                "185.199.110.153",
                "185.199.111.153"
            ),
            "raw.githubusercontent.com" to listOf(
                "185.199.108.133",
                "185.199.109.133",
                "185.199.110.133",
                "185.199.111.133"
            ),
            "cdn.jsdelivr.net" to listOf(
                "104.16.85.20",
                "104.16.86.20"
            ),
            "keyvalue.immanuel.co" to listOf(
                "192.250.231.30"
            )
        )

        private fun resolveViaDoh(hostname: String): List<InetAddress>? {
            val dohUrls = listOf(
                "https://1.1.1.1/dns-query?name=$hostname&type=A",
                "https://8.8.8.8/resolve?name=$hostname&type=A"
            )
            for (urlStr in dohUrls) {
                try {
                    val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 2000
                        readTimeout = 2000
                        setRequestProperty("Accept", "application/dns-json")
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = org.json.JSONObject(body)
                        val answers = json.optJSONArray("Answer")
                        if (answers != null && answers.length() > 0) {
                            val ips = mutableListOf<InetAddress>()
                            for (i in 0 until answers.length()) {
                                val item = answers.getJSONObject(i)
                                val data = item.optString("data")
                                if (data.isNotBlank()) {
                                    try {
                                        ips.add(InetAddress.getByName(data))
                                    } catch (_: Exception) {}
                                }
                            }
                            if (ips.isNotEmpty()) {
                                Log.i(TAG, "Resolved $hostname via DoH (${ips.size} IPs)")
                                return ips
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "DoH lookup attempt failed for $urlStr: ${e.message}")
                }
            }
            return null
        }

        override fun lookup(hostname: String): List<InetAddress> {
            try {
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (addresses.isNotEmpty()) {
                    return addresses
                }
            } catch (e: UnknownHostException) {
                Log.w(TAG, "System DNS lookup failed for $hostname: ${e.message}. Attempting DoH / fallback.")
            } catch (e: Exception) {
                Log.w(TAG, "System DNS exception for $hostname: ${e.message}")
            }

            // 1. Try DNS-over-HTTPS (Cloudflare 1.1.1.1 / Google 8.8.8.8)
            resolveViaDoh(hostname)?.let { return it }

            // 2. Fallback: Use official anycast IP list for this hostname
            val hostLower = hostname.lowercase()
            val ipList = fallbackDnsMap[hostLower]
            if (!ipList.isNullOrEmpty()) {
                val resolved = ipList.mapNotNull { ipStr ->
                    try {
                        InetAddress.getByName(ipStr)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (resolved.isNotEmpty()) {
                    Log.i(TAG, "Resolved $hostname via resilient anycast IP pool (${resolved.size} IPs)")
                    return resolved
                }
            }

            throw UnknownHostException("Unable to resolve host $hostname via system DNS, DoH, or fallback tables")
        }
    }

    /**
     * Shared high-performance HTTP client with warm connection pool and resilient DNS.
     * Keeps TLS connections and sockets warm between calls to eliminate TCP/TLS handshake latency.
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ResilientDns())
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
