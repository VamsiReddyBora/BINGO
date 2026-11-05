package com.bingo.multiplayer.domain.network

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val LATENCY_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
internal const val LATENCY_BUCKET_MS = 10_000L
internal data class LatencyPoint(val at: Long, val ms: Float)

/** Bounded on-device history of real broker echo measurements. No background polling is added. */
internal class LatencyArchive(context: Context, name: String = "admin-latency-v1.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE samples (at INTEGER PRIMARY KEY, peak INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun record(at: Long, pingMs: Long, now: Long = System.currentTimeMillis()) {
        if (pingMs <= 0 || at < now - LATENCY_RETENTION_MS || at > now + 60_000) return
        val bucket = at / LATENCY_BUCKET_MS * LATENCY_BUCKET_MS
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("INSERT OR IGNORE INTO samples(at,peak) VALUES(?,?)", arrayOf(bucket, pingMs))
            writableDatabase.execSQL("UPDATE samples SET peak=MAX(peak,?) WHERE at=?", arrayOf(pingMs, bucket))
            writableDatabase.delete("samples", "at < ?", arrayOf((now - LATENCY_RETENTION_MS).toString()))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    @Synchronized fun read(start: Long, end: Long, now: Long = System.currentTimeMillis()): List<LatencyPoint> {
        require(end > start && end - start <= LATENCY_RETENTION_MS)
        val from = maxOf(start, now - LATENCY_RETENTION_MS)
        val width = maxOf(LATENCY_BUCKET_MS, (end - start + 239) / 240)
        val result = mutableListOf<LatencyPoint>()
        readableDatabase.rawQuery(
            "SELECT MIN(at), MAX(peak) FROM samples WHERE at>=? AND at<=? GROUP BY ((at-?)/?) ORDER BY MIN(at)",
            arrayOf(from.toString(), minOf(end, now).toString(), start.toString(), width.toString())
        ).use { cursor -> while (cursor.moveToNext()) result.add(LatencyPoint(cursor.getLong(0), cursor.getLong(1).toFloat())) }
        return result
    }
}

internal object AdminLatencyArchive {
    @Volatile private var archive: LatencyArchive? = null
    fun init(context: Context) { if (archive == null) synchronized(this) {
        if (archive == null) archive = LatencyArchive(context.applicationContext)
    } }
    fun record(at: Long, ping: Long) { runCatching { archive?.record(at, ping) } }
    suspend fun read(start: Long, end: Long): List<LatencyPoint> = withContext(Dispatchers.IO) {
        archive?.read(start, end).orEmpty()
    }
}
