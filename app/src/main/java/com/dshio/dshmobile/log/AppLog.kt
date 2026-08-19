package com.dshio.dshmobile.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Unified logging for the whole app. Every AppLog call hits three sinks:
//   1. in-memory ring buffer (powers the Logs viewer, survives across
//      recompositions; seeded from the file tail at init so history
//      survives app restarts),
//   2. files/logs/app.log — rolling, MAX_FILE_BYTES per generation,
//      MAX_GENERATIONS kept (rotate happens at init and when a write
//      would overflow),
//   3. logcat mirror (tag DeepCode) for adb debugging.
// The engine's raw output stays in files/logs/dsh.log (see DshService);
// AppLog.exportBundle() merges app.log + dsh.log tail for sharing.
object AppLog {
    enum class Level(val tag: Char) {
        V('V'), D('D'), I('I'), W('W'), E('E'),
    }

    data class Entry(val ts: Long, val level: Level, val tag: String, val msg: String) {
        val line: String
            get() = "${TS_FORMAT.format(Date(ts))} ${level.tag} $tag: $msg"
    }

    private const val MAX_ENTRIES = 2000
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MAX_GENERATIONS = 2
    private val TS_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private var logFile: File? = null

    @Synchronized
    fun init(logsDir: File) {
        logsDir.mkdirs()
        val file = File(logsDir, "app.log")
        rotateIfNeeded(file)
        // seed the ring from the file tail so the viewer shows history
        if (file.exists()) {
            file.readLines().takeLast(MAX_ENTRIES).forEach { line ->
                buffer.addLast(parseLine(line))
            }
        }
        logFile = file
        emit()
        i("AppLog", "initialized (${buffer.size} history lines)")
    }

    @Synchronized
    private fun rotateIfNeeded(file: File) {
        if (!file.exists()) return
        if (file.length() < MAX_FILE_BYTES) return
        val gen1 = File(file.parentFile, "app.log.1")
        gen1.delete()
        file.renameTo(gen1)
    }

    fun v(tag: String, msg: String) = log(Level.V, tag, msg)
    fun d(tag: String, msg: String) = log(Level.D, tag, msg)
    fun i(tag: String, msg: String) = log(Level.I, tag, msg)
    fun w(tag: String, msg: String) = log(Level.W, tag, msg)
    fun e(tag: String, msg: String) = log(Level.E, tag, msg)

    @Synchronized
    private fun log(level: Level, tag: String, msg: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, msg)
        buffer.addLast(entry)
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        emit()
        writeFile(entry)
        mirrorLogcat(level, tag, msg)
    }

    private fun emit() {
        _entries.value = buffer.toList()
    }

    private fun writeFile(entry: Entry) {
        val file = logFile ?: return
        rotateIfNeeded(file)
        try {
            file.appendText(entry.line + "\n")
        } catch (_: Exception) {
            // logging must never crash the app
        }
    }

    private fun mirrorLogcat(level: Level, tag: String, msg: String) {
        when (level) {
            Level.V -> Log.v("DeepCode", "$tag: $msg")
            Level.D -> Log.d("DeepCode", "$tag: $msg")
            Level.I -> Log.i("DeepCode", "$tag: $msg")
            Level.W -> Log.w("DeepCode", "$tag: $msg")
            Level.E -> Log.e("DeepCode", "$tag: $msg")
        }
    }

    // Merge app.log (+ .1) with the dsh.log tail and crash.log into a
    // single shareable file. Returns the file (in cache) or null.
    @Synchronized
    fun exportBundle(cacheDir: File): File? {
        return try {
            val out = File(cacheDir, "logs/deepcode-logs-${System.currentTimeMillis()}.txt")
            out.parentFile?.mkdirs()
            out.bufferedWriter().use { w ->
                w.write("=== DeepCode log bundle ===\n")
                w.write("generated: ${TS_FORMAT.format(Date())}\n\n")
                w.write("----- app.log -----\n")
                val file = logFile
                if (file != null && file.exists()) file.forEachLine { w.write(it + "\n") }
                File(file?.parentFile, "app.log.1").takeIf { it.exists() }?.let { gen ->
                    w.write("\n----- app.log.1 (older) -----\n")
                    gen.forEachLine { w.write(it + "\n") }
                }
                val dsh = File(file?.parentFile, "dsh.log")
                if (dsh.exists()) {
                    w.write("\n----- dsh.log (engine, last 500 lines) -----\n")
                    dsh.readLines().takeLast(500).forEach { w.write(it + "\n") }
                }
                val crash = File(file?.parentFile, "crash.log")
                if (crash.exists()) {
                    w.write("\n----- crash.log -----\n")
                    crash.forEachLine { w.write(it + "\n") }
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLine(line: String): Entry {
        val level = line.substringAfter(' ', "")
            .firstOrNull()
            ?.let { c -> Level.entries.firstOrNull { it.tag == c } }
            ?: Level.I
        return Entry(System.currentTimeMillis(), level, "file", line)
    }
}