package com.dshio.dshmobile

object NativeLib {
    init { System.loadLibrary("proot_apk") }

    external fun initNative(filesDir: String)

    /** Builds the proot argv for a distro, or null if the rootfs is missing. */
    external fun buildProotArgs(distro: String, shell: String): Array<String>?

    external fun rootfsVerifyExtract(tarballPath: String, expectedSha256: String): Int
    external fun rootfsDelete(distro: String): Int

    external fun probePtrace(): Boolean

    @JvmStatic
    fun onRustPanic(msg: String) {
        android.util.Log.e("DshMobile", "Rust panic: $msg")
    }
}
