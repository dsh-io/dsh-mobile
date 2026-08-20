package com.dshio.dshmobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.dshio.dshmobile.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var restartCount = 0

    companion object {
        const val CHANNEL_ID = "dsh"
        const val NOTIF_RUNNING_ID = 1
        const val ACTION_START = "com.dshio.dshmobile.START"
        const val ACTION_STOP = "com.dshio.dshmobile.STOP"
        @Volatile var runningPid: Int = -1
        @Volatile var isReady: Boolean = false
        @Volatile var fatalError: String? = null

        // Double-start protection (device-verified in the deprecated
        // harness-mobile project: a second proot/node start while the first
        // is still booting dies with EADDRINUSE on port 3080, then the
        // watchdog restarts a corpse forever).
        const val START_COOLDOWN_MS = 90_000L
        const val NEVER_READY_TIMEOUT_MS = 120_000L
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
        AppLog.i("Svc", "onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        when (intent?.action) {
            ACTION_STOP -> {
                AppLog.i("Svc", "STOP requested")
                fatalError = "Runtime stopped"
                stopDshInternal()
                getSystemService(NotificationManager::class.java).cancelAll()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                restartCount = 0
                fatalError = null
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
        if (!STARTING.compareAndSet(false, true)) {
            AppLog.d("Svc", "startDshInternal: STARTING CAS lost")
            return
        }
        var spawnFailure: String? = null
        try {
            if (runningPid > 0) {
                AppLog.d("Svc", "startDshInternal: already running (pid=$runningPid)")
                return
            }
            // Cold node boot takes 20-45s (plugin tree + first bind); within
            // the cooldown window of the last real start, do not start again
            // — the supervision poll keeps watching.
            if (
                lastStartAttemptAt > 0 &&
                SystemClock.elapsedRealtime() - lastStartAttemptAt < START_COOLDOWN_MS
            ) {
                AppLog.w("Svc", "startDshInternal: inside cooldown, skipping (restartCount=$restartCount)")
                return
            }
            // rotate the engine log once per service start so it cannot grow
            // unbounded across days of running
            rotateEngineLog()
            val dshDir = File(filesDir, "dsh").absolutePath
            val logPath = File(filesDir, "logs/dsh.log").absolutePath
            AppLog.i("Svc", "starting engine: dshDir=$dshDir log=$logPath")
            // Record the real attempt before entering JNI. It is cleared only
            // after the corresponding process is confirmed dead.
            lastStartAttemptAt = SystemClock.elapsedRealtime()
            val pid = try {
                NativeLib.startDsh(dshDir, logPath)
            } catch (t: Throwable) {
                AppLog.e("Svc", "startDsh JNI call failed: $t")
                -1
            }
            if (pid <= 0) {
                AppLog.e("Svc", "startDsh returned pid=$pid — engine failed to spawn")
                // Native returned no live child (fast-fail child is reaped).
                lastStartAttemptAt = 0
                spawnFailure = "Engine failed to spawn (rc=$pid)"
            } else {
                runningPid = pid
                AppLog.i("Svc", "engine spawned pid=$pid (restartCount=$restartCount)")
                pollJob?.cancel()
                pollJob = scope.launch { supervise(pid) }
            }
        } finally {
            STARTING.set(false)
        }
        // Do not recurse into startDshInternal while holding the STARTING CAS:
        // the old code lost the CAS and silently skipped all spawn retries.
        spawnFailure?.let { onCrash(it) }
    }

    // Rotate dsh.log once per service start (>1MB → dsh.log.1) so the engine
    // log stays bounded and the newest failure is always in the primary file.
    private fun rotateEngineLog() {
        val log = File(filesDir, "logs/dsh.log")
        if (!log.exists()) return
        if (log.length() < 1_048_576) return
        val gen = File(filesDir, "logs/dsh.log.1")
        gen.delete()
        if (log.renameTo(gen)) AppLog.i("Svc", "rotated dsh.log (${log.length()} bytes)")
    }

    private suspend fun supervise(pid: Int) {
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url("http://127.0.0.1:3080").build()
        var ready = false
        var backoff = 1000L
        val neverReadyDeadline = SystemClock.elapsedRealtime() + NEVER_READY_TIMEOUT_MS
        while (runningPid == pid) {
            // Check the child even when the port responds. Otherwise an
            // orphan/foreign server on 3080 can mask an EADDRINUSE exit from
            // the process we actually own and create false readiness.
            val status = NativeLib.reapExitStatus(pid)
            if (status != NativeLib.PROCESS_RUNNING) {
                when {
                    status > 0 -> AppLog.e("Svc", "engine died with signal $status (SIGSEGV=11 SIGABRT=6 SIGKILL=9)")
                    status == NativeLib.PROCESS_GONE -> AppLog.e("Svc", "engine process disappeared (pid=$pid)")
                    else -> AppLog.e("Svc", "engine exited with code ${-status - 1}")
                }
                onCrash("Engine process exited")
                return
            }
            val up = try {
                client.newCall(request).execute().use { it.code == 200 }
            } catch (_: Exception) {
                false
            }
            if (up) {
                if (!ready) AppLog.i("Svc", "engine became ready on 127.0.0.1:3080 (pid=$pid)")
                ready = true
                isReady = true
                // The retry budget counts consecutive failed boots, not
                // unrelated crashes separated by a healthy run.
                restartCount = 0
                delay(5000) // healthy: re-check every 5s to catch crashes
                continue
            }
            isReady = false
            if (ready || SystemClock.elapsedRealtime() >= neverReadyDeadline) {
                // A previously healthy endpoint is now down, or the full
                // 120s cold-boot allowance expired. Only the latter may kill
                // a boot that never bound the port, and it is safely >=90s.
                AppLog.e("Svc", "engine DOWN (ready=$ready) — process alive but port 3080 is not answering")
                onCrash(if (ready) "Engine became unresponsive" else "Engine did not become ready within 120s")
                return
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(5_000L)
        }
    }

    private fun onCrash(reason: String) {
        // stopDshInternal confirms death: stop_pgid SIGTERMs the group and
        // escalates to SIGKILL after 3s, so a hung process holding port 3080
        // (the EADDRINUSE corpse-loop case from harness-mobile) is killed
        // here, not left to poison the next start. Death ⇒ startDshInternal
        // clears the cooldown and restarts immediately.
        if (!stopDshInternal()) {
            publishFatal("$reason; unable to confirm the old process is dead")
            return
        }
        if (restartCount >= 3) {
            publishFatal("$reason; engine crashed repeatedly")
            return
        }
        restartCount++
        AppLog.w("Svc", "restarting engine (attempt $restartCount/3)")
        startDshInternal()
    }

    private fun publishFatal(reason: String) {
        AppLog.e("Svc", "fatal: $reason")
        fatalError = "$reason — see logs/dsh.log"
        val lastLines = try {
            File(filesDir, "logs/dsh.log")
                .takeIf { it.exists() }
                ?.readLines()?.takeLast(10)?.joinToString("\n")
                ?: "(no log yet)"
        } catch (_: Exception) {
            "(log unavailable)"
        }
        // Replace the foreground notification in-place: leaving a second
        // stale "runtime running" notification is actively misleading.
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_RUNNING_ID,
            buildNotification("$reason\n$lastLines"),
        )
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

    private fun stopDshInternal(): Boolean {
        pollJob?.cancel()
        pollJob = null
        var confirmedDead = runningPid <= 0
        if (runningPid > 0) {
            AppLog.i("Svc", "stopping engine pid=$runningPid")
            confirmedDead = NativeLib.stopDsh(runningPid)
            if (confirmedDead) {
                runningPid = -1
                // The cooldown protects a boot in progress. Once stop_pgid
                // confirms death there is no overlap left to protect.
                lastStartAttemptAt = 0
            } else {
                AppLog.e("Svc", "failed to confirm engine death pid=$runningPid")
            }
        }
        isReady = false
        return confirmedDead
    }

    override fun onDestroy() {
        AppLog.i("Svc", "service destroyed")
        stopDshInternal()
        scope.cancel()
        super.onDestroy()
    }
}
