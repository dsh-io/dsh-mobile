package com.dshio.dshmobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DshService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var pollJob: Job? = null
    private var restartCount = 0

    companion object {
        const val CHANNEL_ID = "dsh"
        const val NOTIF_RUNNING_ID = 1
        const val NOTIF_FATAL_ID = 2
        const val ACTION_START = "com.dshio.dshmobile.START"
        const val ACTION_STOP = "com.dshio.dshmobile.STOP"
        @Volatile var runningPid: Int = -1
        @Volatile var isReady: Boolean = false

        // Double-start protection (device-verified in the deprecated
        // harness-mobile project: a second proot/node start while the first
        // is still booting dies with EADDRINUSE on port 3080, then the
        // watchdog restarts a corpse forever).
        const val START_COOLDOWN_MS = 90_000L
        val STARTING = AtomicBoolean(false)
        @Volatile var lastStartAttemptAt: Long = 0
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "dsh runtime", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopDshInternal()
                getSystemService(NotificationManager::class.java).cancelAll()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                restartCount = 0
                startForeground(NOTIF_RUNNING_ID, buildNotification("DeepCode runtime running"))
            }
        }
        startDshInternal()
        return START_STICKY
    }

    private fun startDshInternal() {
        // Single-flight: only one caller actually starts the engine; the
        // losing caller returns immediately (system restart + activity
        // ACTION_START + crash-retry can overlap).
        if (!STARTING.compareAndSet(false, true)) return
        try {
            if (runningPid > 0) return
            // No live process ⇒ no double-start race: clear the cooldown so
            // recovery is not delayed by a stale window (I-11 pattern).
            if (runningPid <= 0) lastStartAttemptAt = 0
            // Cold node boot takes 20-45s (plugin tree + first bind); within
            // the cooldown window of the last real start, do not start again
            // — the supervision poll keeps watching.
            if (System.currentTimeMillis() - lastStartAttemptAt < START_COOLDOWN_MS) return
            val dshDir = File(filesDir, "dsh").absolutePath
            val logPath = File(filesDir, "logs/dsh.log").absolutePath
            val pid = NativeLib.startDsh(dshDir, logPath)
            if (pid <= 0) {
                onCrash()
                return
            }
            runningPid = pid
            lastStartAttemptAt = System.currentTimeMillis()
            pollJob?.cancel()
            pollJob = scope.launch { supervise(pid) }
        } finally {
            STARTING.set(false)
        }
    }

    private fun supervise(pid: Int) {
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url("http://127.0.0.1:3080").build()
        var ready = false
        var backoff = 1000L
        while (runningPid == pid) {
            val up = try {
                client.newCall(request).execute().use { it.code == 200 }
            } catch (_: Exception) {
                false
            }
            if (up) {
                ready = true
                isReady = true
                delay(5000) // healthy: re-check every 5s to catch crashes
                continue
            }
            if (ready || backoff > 90_000L) {
                // was healthy and went down → crash; or never came up in
                // ~90s (cold boot can exceed 60s on slow devices — the
                // harness-mobile project measured 20-45s, so 90s = the
                // START_COOLDOWN_MS window, never kill a boot that is
                // still in progress)
                onCrash()
                return
            }
            delay(backoff)
            backoff *= 2
        }
    }

    private fun onCrash() {
        // stopDshInternal confirms death: stop_pgid SIGTERMs the group and
        // escalates to SIGKILL after 3s, so a hung process holding port 3080
        // (the EADDRINUSE corpse-loop case from harness-mobile) is killed
        // here, not left to poison the next start. Death ⇒ startDshInternal
        // clears the cooldown and restarts immediately.
        stopDshInternal()
        if (restartCount >= 3) {
            val lastLines = File(filesDir, "logs/dsh.log")
                .takeIf { it.exists() }
                ?.readLines()?.takeLast(10)?.joinToString("\n")
                ?: "(no log yet)"
            getSystemService(NotificationManager::class.java).notify(
                NOTIF_FATAL_ID,
                buildNotification("DeepCode crashed repeatedly — last log:\n$lastLines")
            )
            return
        }
        restartCount++
        startDshInternal()
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, DshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DeepCode")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun stopDshInternal() {
        pollJob?.cancel()
        if (runningPid > 0) {
            NativeLib.stopDsh(runningPid)
            runningPid = -1
        }
        isReady = false
    }

    override fun onDestroy() {
        stopDshInternal()
        super.onDestroy()
    }
}