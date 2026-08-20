package com.dshio.dshmobile.ui

import android.view.KeyEvent
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.dshio.dshmobile.NativeLib
import com.dshio.dshmobile.log.AppLog
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(
    filesDir: File,
    distro: String,
    shell: String,
    noSeccomp: Boolean,
    onExit: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var showRetryDialog by remember { mutableStateOf(false) }
    var useNoSeccomp by remember { mutableStateOf(noSeccomp) }
    var useLinker by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    val attemptStartedAt = remember(attempt) { SystemClock.elapsedRealtime() }

    BackHandler { onExit() }

    androidx.compose.runtime.key(attempt) {
        TerminalViewHost(
            filesDir = filesDir,
            distro = distro,
            shell = shell,
            noSeccomp = useNoSeccomp,
            useLinker = useLinker,
            onSessionFinished = { exitStatus, transcript ->
                // Termux invokes this from its process-waiter thread. Marshal
                // Compose state changes back to the main dispatcher.
                uiScope.launch {
                    val failedDuringStartup = exitStatus != 0 &&
                        SystemClock.elapsedRealtime() - attemptStartedAt < 5_000L &&
                        transcript.contains("proot", ignoreCase = true)
                    // Termux's native launcher prints `exec("..."):
                    // Permission denied` and exits 1 when execvp gets EACCES.
                    // Exit code alone is ambiguous with a user's `exit 1`, so
                    // only linker-retry the explicit launcher diagnostic.
                    val execDenied = failedDuringStartup &&
                        transcript.contains("exec(", ignoreCase = true) &&
                        transcript.contains("Permission denied", ignoreCase = true)
                    if (!useLinker && execDenied) {
                        AppLog.w("Terminal", "direct proot exec denied; retrying through linker64")
                        useLinker = true
                        attempt++
                    } else if (!useNoSeccomp && failedDuringStartup) {
                        AppLog.w("Terminal", "proot startup failed; offering no-seccomp mode")
                        showRetryDialog = true
                    } else {
                        onExit()
                    }
                }
            },
        )
    }

    if (showRetryDialog) {
        AlertDialog(
            onDismissRequest = { onExit() },
            title = { Text("Session exited") },
            text = { Text("The session ended abnormally. Retry with PROOT_NO_SECCOMP (compatibility mode)?") },
            confirmButton = {
                TextButton(onClick = {
                    showRetryDialog = false
                    useNoSeccomp = true
                    attempt++
                }) { Text("Retry with compatibility mode") }
            },
            dismissButton = {
                TextButton(onClick = { onExit() }) { Text("Back") }
            },
        )
    }
}

@Composable
private fun TerminalViewHost(
    filesDir: File,
    distro: String,
    shell: String,
    noSeccomp: Boolean,
    useLinker: Boolean,
    onSessionFinished: (Int, String) -> Unit,
) {
    val sessionRef = remember { arrayOfNulls<TerminalSession>(1) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val terminalView = TerminalView(ctx, null)
            val client = object : TerminalViewClient {
                override fun onScale(scale: Float): Float = scale
                override fun onSingleTapUp(e: android.view.MotionEvent) {}
                override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                override fun shouldEnforceCharBasedInput(): Boolean = false
                override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                override fun isTerminalViewSelected(): Boolean = true
                override fun copyModeChanged(copyMode: Boolean) {}
                override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
                override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
                override fun onLongPress(event: android.view.MotionEvent): Boolean = false
                override fun readControlKey(): Boolean = false
                override fun readAltKey(): Boolean = false
                override fun readShiftKey(): Boolean = false
                override fun readFnKey(): Boolean = false
                override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
                override fun onEmulatorSet() {}
                override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
                override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
                override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message) }
                override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message) }
                override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message) }
                override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
                    android.util.Log.e(tag, message, e)
                }
                override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e(tag, "", e) }
            }
            terminalView.setTerminalViewClient(client)

            val prootPath = File(filesDir, "proot/proot").absolutePath
            val prootArgs = NativeLib.buildProotArgs(distro, shell)
                ?: error("Rootfs is missing: $distro")
            val executable = if (useLinker) "/system/bin/linker64" else prootPath
            val args = if (useLinker) {
                arrayOf(executable, prootPath) + prootArgs
            } else {
                arrayOf(prootPath) + prootArgs
            }
            val env = buildList {
                add("PATH=/system/bin:/system/xbin")
                if (noSeccomp) add("PROOT_NO_SECCOMP=1")
            }.toTypedArray()

            val sessionClient = object : TerminalSessionClient {
                override fun onTextChanged(session: TerminalSession) {}
                override fun onTitleChanged(session: TerminalSession) {}
                override fun onSessionFinished(session: TerminalSession) {
                    val transcript = try {
                        session.emulator?.screen?.transcriptText.orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    onSessionFinished(session.getExitStatus(), transcript)
                }
                override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
                override fun onPasteTextFromClipboard(session: TerminalSession) {}
                override fun onBell(session: TerminalSession) {}
                override fun onColorsChanged(session: TerminalSession) {}
                override fun onTerminalCursorStateChange(state: Boolean) {}
                override fun getTerminalCursorStyle(): Int? = null
                override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
                override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
                override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message) }
                override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message) }
                override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message) }
                override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
                    android.util.Log.e(tag, message, e)
                }
                override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e(tag, "", e) }
            }

            val session = TerminalSession(executable, "/", args, env, null, sessionClient)
            sessionRef[0] = session
            terminalView.attachSession(session)
            terminalView
        },
    )
    DisposableEffect(Unit) {
        onDispose {
            sessionRef[0]?.finishIfRunning()
            sessionRef[0] = null
        }
    }
}
