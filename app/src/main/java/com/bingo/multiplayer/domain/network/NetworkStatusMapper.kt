package com.bingo.multiplayer.domain.network

/**
 * Shared production mapper that strictly separates network latency and connectivity sources:
 * 1. Internet reachability (Google DNS / HTTP 204 probe via NetworkPingMonitor)
 * 2. Broker TCP reachability (TCP port 8883 handshake via EmqxBrokerMonitor)
 * 3. Broker RTT (MQTT PING echo via OnlineRoomSyncManager)
 * 4. Peer RTT (Direct LAN or peer packet response in active game)
 */
enum class NetworkMetricSource {
    INTERNET_PROBE,
    BROKER_TCP,
    BROKER_ECHO,
    PEER_RTT,
    NONE
}

data class FormattedLatencyDisplay(
    val displayText: String,
    val latencyMs: Long,
    val source: NetworkMetricSource,
    val label: String,
    val isReliable: Boolean
)

object NetworkStatusMapper {
    const val FRESHNESS_WINDOW_MS = 15_000L

    fun mapMainMenuLatency(
        internetLatencyMs: Long,
        isInternetFresh: Boolean,
        isBrokerDown: Boolean,
        brokerRttMs: Long = 0L,
        isBrokerRttFresh: Boolean = false
    ): FormattedLatencyDisplay {
        return when {
            isBrokerRttFresh && brokerRttMs > 0L -> FormattedLatencyDisplay(
                displayText = formatMs(brokerRttMs),
                latencyMs = brokerRttMs,
                source = NetworkMetricSource.BROKER_ECHO,
                label = "Broker RTT",
                isReliable = true
            )
            isInternetFresh && !isBrokerDown && internetLatencyMs > 0L -> FormattedLatencyDisplay(
                displayText = formatMs(internetLatencyMs),
                latencyMs = internetLatencyMs,
                source = NetworkMetricSource.INTERNET_PROBE,
                label = "Internet",
                isReliable = true
            )
            else -> FormattedLatencyDisplay(
                displayText = "--ms",
                latencyMs = 0L,
                source = NetworkMetricSource.NONE,
                label = "Unknown",
                isReliable = false
            )
        }
    }

    fun mapGameLatency(
        isOnlineRoom: Boolean,
        isNearby: Boolean,
        lanPingMs: Long,
        isLanFresh: Boolean,
        brokerRttMs: Long,
        isBrokerRttFresh: Boolean,
        isMqttSubscribed: Boolean,
        isPeerResponsive: Boolean,
        peerRttMs: Long = 0L,
        internetLatencyMs: Long = 0L,
        isInternetFresh: Boolean = false
    ): FormattedLatencyDisplay {
        if (isNearby) {
            return if (isLanFresh && lanPingMs > 0L) {
                FormattedLatencyDisplay(formatMs(lanPingMs), lanPingMs, NetworkMetricSource.PEER_RTT, "LAN Peer", true)
            } else {
                FormattedLatencyDisplay("--ms", 0L, NetworkMetricSource.NONE, "Unknown", false)
            }
        }

        if (isOnlineRoom) {
            if (!isMqttSubscribed) {
                return FormattedLatencyDisplay("--ms", 0L, NetworkMetricSource.NONE, "Unsubscribed", false)
            }
            if (peerRttMs > 0L) {
                return FormattedLatencyDisplay(formatMs(peerRttMs), peerRttMs, NetworkMetricSource.PEER_RTT, "Peer RTT", true)
            }
            if (isBrokerRttFresh && brokerRttMs > 0L) {
                return FormattedLatencyDisplay(formatMs(brokerRttMs), brokerRttMs, NetworkMetricSource.BROKER_ECHO, "Broker RTT", isPeerResponsive)
            }
            if (!isPeerResponsive) {
                return FormattedLatencyDisplay("--ms", 0L, NetworkMetricSource.NONE, "Unanswered Peer", false)
            }
            return FormattedLatencyDisplay("--ms", 0L, NetworkMetricSource.NONE, "Unknown", false)
        }

        return if (isInternetFresh && internetLatencyMs > 0L) {
            FormattedLatencyDisplay(formatMs(internetLatencyMs), internetLatencyMs, NetworkMetricSource.INTERNET_PROBE, "Internet", true)
        } else {
            FormattedLatencyDisplay("--ms", 0L, NetworkMetricSource.NONE, "Unknown", false)
        }
    }

    fun formatMs(ms: Long): String {
        return when {
            ms <= 0L -> "--ms"
            ms > 999L -> "999+ms"
            else -> "${ms}ms"
        }
    }
}
