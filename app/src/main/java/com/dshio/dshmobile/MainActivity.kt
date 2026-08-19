package com.dshio.dshmobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dshio.dshmobile.ui.ExtractScreen
import com.dshio.dshmobile.ui.TerminalScreen
import com.dshio.dshmobile.ui.WebviewScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Single-flight CAS (device-verified in harness-mobile: onCreate +
    // onResume both trigger the flow; two threads extracting/starting in
    // parallel kill the engine).
    private val engineFlowRunning = AtomicBoolean(false)

    private sealed interface AppState {
        data class Extracting(val text: String) : AppState
        data class Error(val message: String) : AppState
        object Starting : AppState
        object Ready : AppState
    }

    private var state by mutableStateOf<AppState>(AppState.Extracting("Checking installation…"))
    private var tab by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeLib.initNative(filesDir.absolutePath)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                if (state is AppState.Ready) {
                    Scaffold(
                        bottomBar = {
                            BottomAppBar {
                                TextButton(onClick = { tab = 0 }) { Text("Harness") }
                                TextButton(onClick = { tab = 1 }) { Text("Terminal") }
                            }
                        },
                    ) { padding ->
                        if (tab == 0) {
                            WebviewScreen(Modifier.fillMaxSize().padding(padding))
                        } else {
                            TerminalScreen(
                                filesDir = filesDir,
                                distro = "debian",
                                shell = "/bin/bash",
                                noSeccomp = false,
                                onExit = { tab = 0 },
                            )
                        }
                    }
                } else {
                    ExtractScreen(
                        progressText = (state as? AppState.Extracting)?.text ?: "Starting…",
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
        if (!engineFlowRunning.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ensureProotBinary()
                val missing = ensureAssetsExtracted { text -> state = AppState.Extracting(text) }
                if (missing != null) {
                    state = AppState.Error(missing)
                    return@launch
                }
                val intent = Intent(this@MainActivity, DshService::class.java)
                intent.action = DshService.ACTION_START
                startForegroundService(intent) // minSdk 26, no legacy branch needed
                var waited = 0
                while (!DshService.isReady && waited < 90 && DshService.runningPid > 0) {
                    Thread.sleep(1000)
                    waited++
                }
                state = if (DshService.isReady) AppState.Ready else AppState.Error("dsh failed to start — see logs/dsh.log")
            } finally {
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
        if (target.exists()) return
        assets.open("proot/proot-aarch64").use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
        target.setExecutable(true)
        // W^X: a writable proot binary is refused by Huawei/EMUI exec (and
        // by mmap PROT_EXEC); the binary is never self-modifying.
        target.setWritable(false, false)
    }

    private fun ensureAssetsExtracted(onProgress: (String) -> Unit): String? {
        // ~1.5GB free is enough for the extracted rootfs (~1GB) + dsh package
        if (StatFs(filesDir.absolutePath).availableBytes < 1_500_000_000L) {
            return "Insufficient storage: ~1.5GB of free space is required."
        }
        // rootfs: assets/rootfs/debian.tar.xz + .sha256 → files/rootfs/debian.
        // The .sha256 assets are bare 64-hex hashes (see build-rootfs.sh / CI).
        val rootfsTar = File(filesDir, "downloads/rootfs.tar.xz")
        if (!File(filesDir, "rootfs/debian/bin/sh").exists()) {
            onProgress("Extracting rootfs (~1GB)…")
            copyAssetToFile("rootfs/debian.tar.xz", rootfsTar)
            val sha = assets.open("rootfs/debian.tar.xz.sha256").bufferedReader().use { it.readText().trim() }
            val rc = NativeLib.extractVerified(rootfsTar.absolutePath, sha, File(filesDir, "rootfs/debian").absolutePath)
            if (rc != 0) return "Rootfs extraction failed (rc=$rc). ~1.5GB of free storage is required."
            stampExecAttributes(File(filesDir, "rootfs/debian"))
        }
        // dsh package: assets/dsh/dsh-arm64-0.1.0-rc.6.tar.gz + .sha256 → files/dsh
        val dshTar = File(filesDir, "downloads/dsh.tar.gz")
        if (!File(filesDir, "dsh/node_modules/@deepseek-ai/dsh/lib/bin.js").exists()) {
            onProgress("Extracting dsh package…")
            copyAssetToFile("dsh/dsh-arm64-0.1.0-rc.6.tar.gz", dshTar)
            val sha = assets.open("dsh/dsh-arm64-0.1.0-rc.6.tar.gz.sha256").bufferedReader().use { it.readText().trim() }
            val rc = NativeLib.extractVerified(dshTar.absolutePath, sha, File(filesDir, "dsh").absolutePath)
            if (rc != 0) return "dsh package extraction failed (rc=$rc)."
            stampExecAttributes(File(filesDir, "dsh"))
        }
        return null
    }

    // Best-effort Android 15+ compatibility (harness-mobile SnapshotExtractor
    // pattern): stamp the security.android.exec attribute on extracted
    // executables via the system setfattr. targetSdk 34 already avoids the
    // SDK-35 requirement; this covers vendor/OEM enforcement that ignores
    // targetSdk. Batches of 64, 30s cap, silent on failure (kernels without
    // the check don't need it). Arg array passed directly — no shell.
    private fun stampExecAttributes(root: File) {
        val execFiles = mutableListOf<File>()
        root.walkTopDown().forEach { if (it.isFile && it.canExecute()) execFiles.add(it) }
        if (execFiles.isEmpty()) return
        val base = listOf("/system/bin/setfattr", "-n", "security.android.exec", "-v", "1")
        execFiles.chunked(64).forEach { batch ->
            batch.map { f ->
                ProcessBuilder(base + f.absolutePath).redirectErrorStream(true).start()
            }.forEach { p ->
                if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
    }

    private fun copyAssetToFile(asset: String, target: File) {
        target.parentFile?.mkdirs()
        assets.open(asset).use { input -> target.outputStream().use { out -> input.copyTo(out) } }
    }
}