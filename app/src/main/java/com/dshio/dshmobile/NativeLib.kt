package com.dshio.dshmobile

import android.util.Log

object NativeLib {
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

    /** Hash-checked extract of a tarball asset to an explicit destination. */
    external fun extractVerified(tarballPath: String, expectedSha256: String, dest: String): Int

    @JvmStatic
    fun onRustPanic(msg: String) {
        Log.e("DshMobile", "Rust panic: $msg")
    }
}