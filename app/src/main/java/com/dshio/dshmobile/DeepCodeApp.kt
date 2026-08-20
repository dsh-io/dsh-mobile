package com.dshio.dshmobile

import android.app.Application
import com.dshio.dshmobile.log.AppLog
import com.dshio.dshmobile.log.CrashHandler
import java.io.File

class DeepCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        AppLog.init(File(filesDir, "logs"))
        // A START_STICKY service can be recreated in a fresh process without
        // MainActivity ever running. JNI global state must therefore be
        // initialized at the process entry point, not only by the Activity.
        NativeLib.initNative(filesDir.absolutePath)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        AppLog.i("App", "DeepCode $version starting (pid=${android.os.Process.myPid()})")
    }
}
