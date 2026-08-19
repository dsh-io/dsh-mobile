package com.dshio.dshmobile.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebviewScreen(modifier: Modifier = Modifier) {
    val scope = remember { CoroutineScope(Dispatchers.Main) }
    val reloadJob = remember { arrayOfNulls<Job>(1) }
    // I-26 (device-verified in harness-mobile): onPageFinished fires even
    // for ERROR pages, so a boolean "failed" flag cleared in onPageFinished
    // is cleared instantly and the page is never reloaded. Track errors per
    // load: pendingError is set in onPageStarted → cleared by a main-frame
    // error → survives until the next page start. pageFailed stays true
    // until a load completes with no error.
    val pendingError = remember { booleanArrayOf(false) }
    val pageFailed = remember { booleanArrayOf(false) }
    val retryBackoff = remember { longArrayOf(2000L) }
    val webView = remember {
        WebView(androidx.compose.ui.platform.LocalContext.current).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // OEM WebViews do not follow the system dark theme on their own.
            if (Build.VERSION.SDK_INT >= 29) settings.setForceDark(WebSettings.FORCE_DARK_AUTO)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    pendingError[0] = false
                }
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) pendingError[0] = true
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    if (pendingError[0]) {
                        // error page finished — schedule the next retry
                        pageFailed[0] = true
                        scheduleReload()
                    } else {
                        pageFailed[0] = false
                        retryBackoff[0] = 2000L
                    }
                }
            }
            loadUrl("http://127.0.0.1:3080")
        }
    }

    fun scheduleReload() {
        reloadJob[0]?.cancel()
        reloadJob[0] = scope.launch {
            delay(retryBackoff[0])
            retryBackoff[0] = (retryBackoff[0] * 2).coerceAtMost(30_000L)
            webView.loadUrl("http://127.0.0.1:3080")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            reloadJob[0]?.cancel()
            webView.destroy()
        }
    }

    AndroidView(factory = { webView }, modifier = modifier)
}