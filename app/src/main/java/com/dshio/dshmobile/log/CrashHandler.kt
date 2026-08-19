package com.dshio.dshmobile.log

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

// Wraps the platform's default uncaught-exception handler: appends a
// timestamped record (thread, stack, dsh.log tail) to logs/crash.log,
// mirrors it into AppLog, then delegates to the previous handler so the
// system still shows the crash dialog and kills the process.
object CrashHandler {
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val crashFile = File(app.filesDir, "logs/crash.log")
                crashFile.parentFile?.mkdirs()
                val dshTail = File(app.filesDir, "logs/dsh.log")
                    .takeIf { it.exists() }
                    ?.readLines()?.takeLast(30)?.joinToString("\n")
                    ?: "(no engine log)"
                crashFile.appendText(
                    "=== ${AppLog.Entry(System.currentTimeMillis(), AppLog.Level.E, "crash", "").line} " +
                        "thread=${thread.name} ===\n" +
                        sw.toString() + "\n" +
                        "--- dsh.log tail ---\n$dshTail\n\n",
                )
                AppLog.e("Crash", "uncaught on ${thread.name}: $throwable")
            } catch (_: Exception) {
                // never mask the original crash
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}