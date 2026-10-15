package com.bingo.multiplayer.core.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import kotlin.system.exitProcess

/** Private, bounded diagnostics. Never records chat, packet contents, or exception messages. */
object AppExitDiagnostics {
    private const val FILE = "last_app_crash.txt"
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val app = context.applicationContext
        val version = version(app)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { File(app.filesDir, FILE).writeText(formatCrash(error, System.currentTimeMillis(), version)) }
            catch (_: Throwable) { /* Reporting must not prevent the normal crash handler. */ }
            if (previous != null) previous.uncaughtException(thread, error)
            else { Process.killProcess(Process.myPid()); exitProcess(10) }
        }
    }

    internal fun formatCrash(error: Throwable, timestamp: Long, version: String = "unknown"): String = buildString {
        append("Bingo ${version}; crash time (epoch ms): $timestamp\n")
        var cause: Throwable? = error
        val seen = mutableSetOf<Throwable>()
        while (cause != null && seen.size < 5 && seen.add(cause)) {
            append(cause.javaClass.name).append('\n')
            cause.stackTrace.take(30).forEach { append("  at ").append(it).append('\n') }
            cause = cause.cause
        }
    }.take(16_384)

    private fun version(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) { "unknown" }

    /** Only copied by the user's Settings action. Nothing is uploaded. */
    fun read(context: Context): String = buildString {
        append("Bingo ${version(context)}; Android SDK ${Build.VERSION.SDK_INT}\n")
        try {
            val file = File(context.filesDir, FILE)
            if (file.exists()) append(file.inputStream().bufferedReader().use { it.readText().take(16_384) })
            else append("No Java/Kotlin crash recorded by this build.\n")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                manager?.getHistoricalProcessExitReasons(context.packageName, 0, 3)?.forEach {
                    append("Process exit: reason code=${it.reason}; time (epoch ms)=${it.timestamp}; status=${it.status}\n")
                }
            }
        } catch (_: Exception) { append("Some exit diagnostics are unavailable.\n") }
    }
}
