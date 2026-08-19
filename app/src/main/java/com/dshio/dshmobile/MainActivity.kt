package com.dshio.dshmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

// Temporary placeholder during the rebrand fork; replaced in Task 3 Step 7.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeLib.initNative(filesDir.absolutePath)
        setContent {
            MaterialTheme {
                Text("dsh-mobile")
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            ensureProotBinary()
        }
    }

    private fun ensureProotBinary() {
        val dir = File(filesDir, "proot").apply { mkdirs() }
        val target = File(dir, "proot")
        if (target.exists()) return
        assets.open("proot/proot-aarch64").use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
        target.setExecutable(true)
        target.setWritable(false, false)
    }
}