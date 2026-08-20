package com.dshio.dshmobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshio.dshmobile.log.AppLog
import com.dshio.dshmobile.ui.ExtractScreen
import com.dshio.dshmobile.ui.LogsScreen
import com.dshio.dshmobile.ui.TerminalScreen
import com.dshio.dshmobile.ui.WebviewScreen
import com.dshio.dshmobile.ui.theme.DeepCodeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private companion object {
        // Four supervised boot attempts, each allowed the service's full
        // never-ready window, plus process-stop/restart overhead.
        const val ENGINE_READY_UI_TIMEOUT_MS = 9 * 60_000L
        val engineFlowRunning = AtomicBoolean(false)
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Single-flight CAS (device-verified in harness-mobile: onCreate +
    // onResume both trigger the flow; two threads extracting/starting in
    // parallel kill the engine).
    @Volatile private var ownsEngineFlow = false
    private val retryAfterOtherFlow = AtomicBoolean(false)

    private sealed interface AppState {
        data class Extracting(val text: String) : AppState
        data class Error(val message: String) : AppState
        object Starting : AppState
        object Ready : AppState
    }

    private var state by mutableStateOf<AppState>(AppState.Starting)
    private var tab by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeLib.initNative(filesDir.absolutePath)
        state = AppState.Extracting(getString(R.string.checking_installation))
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            DeepCodeTheme {
                if (state is AppState.Ready) {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        bottomBar = { NavBar(tab = tab, onSelect = { tab = it }) },
                    ) { padding ->
                        when (tab) {
                            0 -> WebviewScreen(Modifier.fillMaxSize().padding(padding))
                            1 -> TerminalScreen(
                                filesDir = filesDir,
                                distro = "debian",
                                shell = "/bin/bash",
                                noSeccomp = false,
                                onExit = { tab = 0 },
                            )
                            else -> LogsScreen(Modifier.fillMaxSize().padding(padding))
                        }
                    }
                } else {
                    ExtractScreen(
                        progressText = (state as? AppState.Extracting)?.text
                            ?.ifEmpty { getString(R.string.starting) }
                            ?: getString(R.string.starting),
                        error = (state as? AppState.Error)?.message,
                        onRetry = { startExtract() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        startExtract()
    }

    override fun onResume() {
        super.onResume()
        startExtract() // re-entry (rotation, relaunch); CAS makes it single-flight
    }

    private fun startExtract() {
        if (!engineFlowRunning.compareAndSet(false, true)) {
            AppLog.d("Main", "startExtract: CAS lost — another flow is running")
            // The current flow may belong to an Activity instance that is
            // being replaced. Its result cannot update this instance, so wait
            // once and re-enter after it releases the process-wide CAS.
            if (!ownsEngineFlow && retryAfterOtherFlow.compareAndSet(false, true)) {
                CoroutineScope(Dispatchers.IO).launch {
                    while (engineFlowRunning.get()) Thread.sleep(100)
                    retryAfterOtherFlow.set(false)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) startExtract()
                    }
                }
            }
            return
        }
        ownsEngineFlow = true
        AppLog.i("Main", "startExtract: CAS won — starting bootstrap")
        CoroutineScope(Dispatchers.IO).launch {
            val t0 = System.currentTimeMillis()
            try {
                runOnUiThread { state = AppState.Extracting(getString(R.string.checking_installation)) }
                ensureProotBinary()
                val missing = ensureAssetsExtracted { text ->
                    runOnUiThread { state = AppState.Extracting(text) }
                }
                if (missing != null) {
                    AppLog.e("Main", "bootstrap failed: $missing")
                    runOnUiThread { state = AppState.Error(missing) }
                    return@launch
                }
                AppLog.i("Main", "assets ready in ${System.currentTimeMillis() - t0}ms")
                val intent = Intent(this@MainActivity, DshService::class.java)
                intent.action = DshService.ACTION_START
                runOnUiThread { state = AppState.Starting }
                startForegroundService(intent) // minSdk 26, no legacy branch needed
                // startForegroundService() only enqueues service creation. The
                // old `runningPid > 0` loop therefore skipped immediately on
                // most devices, before onStartCommand had a chance to spawn.
                val deadline = SystemClock.elapsedRealtime() + ENGINE_READY_UI_TIMEOUT_MS
                while (
                    !DshService.isReady &&
                    DshService.fatalError == null &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    Thread.sleep(500)
                }
                runOnUiThread {
                    state = if (DshService.isReady) {
                        AppLog.i("Main", "engine ready after ${System.currentTimeMillis() - t0}ms")
                        AppState.Ready
                    } else {
                        val detail = DshService.fatalError
                            ?: getString(R.string.engine_startup_timed_out)
                        AppLog.e("Main", "engine not ready (pid=${DshService.runningPid}): $detail")
                        AppState.Error(detail)
                    }
                }
            } catch (e: Exception) {
                AppLog.e("Main", "bootstrap threw: $e")
                runOnUiThread { state = AppState.Error(e.message ?: getString(R.string.unexpected_failure)) }
            } finally {
                ownsEngineFlow = false
                engineFlowRunning.set(false)
            }
        }
    }

    private fun ensureProotBinary() {
        val dir = File(filesDir, "proot").apply { mkdirs() }
        val target = File(dir, "proot")
        // Skip if present: the binary was made read-only by W^X below, so
        // overwriting it would EACCES forever (reinstall-without-clear,
        // harness-mobile ProotRuntime pattern).
        if (target.exists()) {
            check(target.isFile && target.length() > 0) { getString(R.string.installed_proot_invalid) }
            // Repair permissions from older/interrupted app versions without
            // ever overwriting the existing ELF.
            check(target.setExecutable(true)) { getString(R.string.cannot_make_proot_executable) }
            check(target.setWritable(false, false)) { getString(R.string.cannot_make_proot_read_only) }
            return
        }
        // Copy and chmod a temporary file, then publish it atomically. A
        // force-stop halfway through the copy must never leave a truncated
        // `proot` that the skip-if-exists rule would trust forever.
        val tmp = File(dir, "proot.tmp")
        if (tmp.exists() && !tmp.delete()) error(getString(R.string.cannot_clean_stale_proot))
        try {
            assets.open("proot/proot-aarch64").use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            check(tmp.setExecutable(true)) { getString(R.string.cannot_make_proot_executable) }
            // W^X: strict OEMs refuse a writable app-data ELF.
            check(tmp.setWritable(false, false)) { getString(R.string.cannot_make_proot_read_only) }
            check(tmp.renameTo(target)) { getString(R.string.cannot_install_proot_atomically) }
        } finally {
            if (tmp.exists()) {
                tmp.setWritable(true)
                tmp.delete()
            }
        }
    }

    private fun ensureAssetsExtracted(onProgress: (String) -> Unit): String? {
        val rootfsShell = File(filesDir, "rootfs/debian/bin/sh")
        val dshEntry = File(filesDir, "dsh/node_modules/@deepseek-ai/dsh/lib/bin.js")
        val rootfsReady = rootfsShell.isFile && rootfsShell.canExecute()
        val dshReady = dshEntry.isFile && dshEntry.canRead()
        val rootfsTar = File(filesDir, "downloads/rootfs.tar.xz")
        val dshTar = File(filesDir, "downloads/dsh.tar.gz")
        // Failed or interrupted extraction attempts must not permanently
        // consume the free space required by Retry. These are disposable
        // copies of APK assets and are always recreated below when needed.
        listOf(rootfsTar, dshTar).forEach { stale ->
            if (stale.exists() && !stale.delete()) {
                return getString(R.string.cannot_clean_stale_extraction, stale.name)
            }
        }
        // Do not reject a healthy, already-installed runtime just because
        // extraction consumed most of the initially available free space.
        if (rootfsReady && dshReady) return null

        // Real writable space on the filesystem holding filesDir. Per the
        // Android docs getAvailableBytes() (statvfs.f_bavail) is "the number
        // of bytes that are free on the file system and available to
        // applications" — the correct standard API for a writability check.
        // freeBytes includes blocks reserved from applications; using it can
        // overestimate writable space and start an extraction that must fail.
        val stat = StatFs(filesDir.absolutePath)
        val usable = stat.availableBytes
        // Rootfs extraction needs room for both its compressed asset and the
        // expanded tree. A missing dsh package alone is much smaller and must
        // not reject an otherwise healthy install with a blanket 1.5GB gate.
        val required = if (!rootfsReady) 1_500_000_000L else 300_000_000L
        if (usable < required) {
            AppLog.e(
                "Main",
                "storage check failed: available=${stat.availableBytes} free=${stat.freeBytes} " +
                    "usable=$usable required=$required",
            )
            return getString(
                R.string.insufficient_storage,
                required / 1_000_000_000.0,
                usable / 1_000_000_000.0,
            )
        }
        // rootfs: assets/rootfs/debian.tar.xz + .sha256 → files/rootfs/debian.
        // The .sha256 assets are bare 64-hex hashes (see build-rootfs.sh / CI).
        if (!rootfsReady) {
            onProgress(getString(R.string.extracting_rootfs))
            copyAssetToFile("rootfs/debian.tar.xz", rootfsTar)
            val sha = assets.open("rootfs/debian.tar.xz.sha256").bufferedReader().use { it.readText().trim() }
            AppLog.i("Main", "extracting rootfs: tar=${rootfsTar.length()}B sha=$sha")
            val rc = try {
                NativeLib.extractVerified(rootfsTar.absolutePath, sha, File(filesDir, "rootfs/debian").absolutePath)
            } finally {
                if (rootfsTar.exists() && !rootfsTar.delete()) {
                    AppLog.w("Main", "could not delete temporary rootfs archive")
                }
            }
            if (rc != 0) return extractError(getString(R.string.rootfs_name), rc)
            stampExecAttributes(File(filesDir, "rootfs/debian"))
        }
        // dsh package: assets/dsh/dsh-arm64-0.1.0-rc.6.tar.gz + .sha256 → files/dsh
        if (!dshReady) {
            onProgress(getString(R.string.extracting_dsh))
            copyAssetToFile("dsh/dsh-arm64-0.1.0-rc.6.tar.gz", dshTar)
            val sha = assets.open("dsh/dsh-arm64-0.1.0-rc.6.tar.gz.sha256").bufferedReader().use { it.readText().trim() }
            AppLog.i("Main", "extracting dsh: tar=${dshTar.length()}B sha=$sha")
            val rc = try {
                NativeLib.extractVerified(dshTar.absolutePath, sha, File(filesDir, "dsh").absolutePath)
            } finally {
                if (dshTar.exists() && !dshTar.delete()) {
                    AppLog.w("Main", "could not delete temporary dsh archive")
                }
            }
            if (rc != 0) return extractError(getString(R.string.dsh_package_name), rc)
            stampExecAttributes(File(filesDir, "dsh"))
        }
        return null
    }

    // NativeLib.extractVerified error codes (see jni-bridge lib.rs):
    // -1 = unexpected failure, -2 = sha256 mismatch, -3 = extraction error,
    // -4 = IO/cleanup error. The exact error string is retained by the Rust
    // side and surfaced via NativeLib.lastExtractError() so the UI shows the
    // real cause instead of a blanket code.
    private fun extractError(what: String, rc: Int): String {
        val detail = NativeLib.lastExtractError().ifEmpty { null }
        val hint = when (rc) {
            -2 -> getString(R.string.archive_corrupt)
            -3 -> getString(R.string.extraction_failed)
            -4 -> getString(R.string.cleanup_io_failed)
            else -> getString(R.string.unexpected_failure_with_code, rc)
        }
        return getString(R.string.extract_failure_message, what, hint) + (detail?.let { "\n$it" } ?: "")
    }

    // Best-effort Android 15+ compatibility (harness-mobile SnapshotExtractor
    // pattern): stamp the security.android.exec attribute on extracted
    // executables via the system setfattr. targetSdk 34 already avoids the
    // SDK-35 requirement; this covers vendor/OEM enforcement that ignores
    // targetSdk. Batches of 64, 30s cap, silent on failure (kernels without
    // the check don't need it). Arg array passed directly — no shell.
    private fun stampExecAttributes(root: File) {
        val setfattr = File("/system/bin/setfattr")
        if (!setfattr.canExecute()) {
            AppLog.d("Main", "setfattr unavailable; skipping security.android.exec stamps")
            return
        }
        val execFiles = mutableListOf<File>()
        try {
            val dirs = ArrayDeque<File>()
            dirs.add(root)
            while (dirs.isNotEmpty()) {
                val dir = dirs.removeLast()
                dir.listFiles()?.forEach { child ->
                    // Debian carries absolute symlinks. Never follow them out
                    // of the app tree while collecting stamp targets.
                    if (Files.isSymbolicLink(child.toPath())) return@forEach
                    if (child.isDirectory) dirs.add(child)
                    else if (child.isFile && child.canExecute()) execFiles.add(child)
                }
            }
        } catch (e: Exception) {
            AppLog.d("Main", "setfattr scan failed: ${e.message}")
            return
        }
        if (execFiles.isEmpty()) return
        val base = listOf(setfattr.absolutePath, "-n", "security.android.exec", "-v", "1")
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        execFiles.chunked(64).forEach { batch ->
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return
            try {
                // setfattr accepts multiple paths: one process per batch,
                // bounded by one 30s cap for the whole best-effort pass.
                val p = ProcessBuilder(base + batch.map { it.absolutePath })
                    .redirectErrorStream(true)
                    .start()
                if (!p.waitFor(remaining, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                    return
                }
            } catch (e: Exception) {
                AppLog.d("Main", "setfattr unavailable/denied: ${e.message}")
                return
            }
        }
    }

    private fun copyAssetToFile(asset: String, target: File) {
        target.parentFile?.mkdirs()
        assets.open(asset).use { input -> target.outputStream().use { out -> input.copyTo(out) } }
    }
}

@Composable
private fun NavBar(tab: Int, onSelect: (Int) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                stringResource(R.string.nav_harness) to 0,
                stringResource(R.string.nav_terminal) to 1,
                stringResource(R.string.nav_logs) to 2,
            ).forEach { (label, index) ->
                val selected = tab == index
                Surface(
                    onClick = { onSelect(index) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ) {
                    Text(
                        text = label,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
