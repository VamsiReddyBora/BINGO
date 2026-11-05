package com.bingo.multiplayer.domain.network

import android.content.Context
import com.bingo.multiplayer.domain.model.UserProfile
import org.json.JSONObject

internal const val CLOUD_HISTORY_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
internal data class CloudLatencyPoint(
    val at: Long, val averageMs: Float?, val peakMs: Float?, val successfulProbes: Int,
    val failedProbes: Int, val observations: Int
)
internal data class CloudLatencyHistory(
    val from: Long, val to: Long, val intervalMs: Long, val bucketMs: Long,
    val startedAt: Long, val lastRecordedAt: Long?, val points: List<CloudLatencyPoint>
)

internal object CloudLatencyApi {
    suspend fun fetch(context: Context, user: UserProfile, from: Long, to: Long): CloudLatencyHistory {
        require(to > from && to - from <= CLOUD_HISTORY_RETENTION_MS)
        val json = AutomaticCleanupApi.requestAdminJson(context, user,
            "/client/admin/latency-history?from=$from&to=$to&points=240", maximumChars = 262_144)
        return parse(json)
    }

    internal fun parse(json: JSONObject): CloudLatencyHistory {
        require(json.getInt("schema") == 1 && json.getString("source") == "cloud-monitor" &&
            json.getString("measurement") == "monitor-to-emqx-mqtt-echo") { "Unexpected history source" }
        val from = json.getLong("from"); val to = json.getLong("to")
        require(to > from && to - from <= CLOUD_HISTORY_RETENTION_MS)
        val interval = json.getLong("intervalMs"); val bucket = json.getLong("bucketMs")
        require(interval == 60_000L && bucket >= interval)
        val array = json.getJSONArray("points")
        require(array.length() <= 481)
        var previous = Long.MIN_VALUE
        val points = List(array.length()) { index ->
            val point = array.getJSONObject(index)
            val at = point.getLong("at")
            require(at in from..to && at > previous)
            previous = at
            fun metric(key: String): Float? = if (point.isNull(key)) null else point.getDouble(key).toFloat().also {
                require(it.isFinite() && it > 0f)
            }
            val average = metric("averageMs"); val peak = metric("peakMs")
            val success = point.getInt("successfulProbes"); val failure = point.getInt("failedProbes")
            val observations = point.getInt("observations")
            require(observations in 1..43_201 && success >= 0 && failure >= 0 && success + failure == observations * 3)
            require(if (success == 0) average == null && peak == null else average != null && peak != null && peak >= average)
            CloudLatencyPoint(at, average, peak, success, failure, observations)
        }
        return CloudLatencyHistory(from, to, interval, bucket, json.getLong("startedAt"),
            if (json.isNull("lastRecordedAt")) null else json.getLong("lastRecordedAt"), points)
    }
}
