package com.dshio.dshmobile

import android.util.Log

object NativeLib {
    const val PROCESS_RUNNING: Int = Int.MIN_VALUE
    const val PROCESS_GONE: Int = Int.MIN_VALUE + 1

    init {
        System.loadLibrary("dshmobile_jni")
    }

    external fun initNative(filesDir: String)

    /** Builds the proot argv for a distro, or null if the rootfs is missing. */
    external fun buildProotArgs(distro: String, shell: String): Array<String>?

    external fun rootfsVerifyExtract(tarballPath: String, expectedSha256: String): Int
    external fun rootfsDelete(distro: String): Int

    external fun probePtrace(): Boolean

    /** Spawn the dsh engine daemon (non-TTY proot); returns pid or -1. */
    external fun startDsh(dshDir: String, logPath: String): Int

    /** Terminate the engine process group (SIGTERM → SIGKILL). */
    external fun stopDsh(pid: Int): Boolean

    /**
     * Reap the engine child and report how it died without blocking:
     *   > 0 → terminated by that signal (11=SIGSEGV, 6=SIGABRT, 9=SIGKILL)
     *   -(code + 1) → exited normally (including code 0)
     *   PROCESS_RUNNING → still alive
     *   PROCESS_GONE → already reaped / no longer our child
     */
    external fun reapExitStatus(pid: Int): Int

    /** Hash-checked extract of a tarball asset to an explicit destination. */
    external fun extractVerified(tarballPath: String, expectedSha256: String, dest: String): Int

    /** Full error string of the most recent extractVerified call ("" on success). */
    external fun lastExtractError(): String

    @JvmStatic
    fun onRustPanic(msg: String) {
        Log.e("DshMobile", "Rust panic: $msg")
    }
}
