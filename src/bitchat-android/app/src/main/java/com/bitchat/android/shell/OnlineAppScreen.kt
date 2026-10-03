package com.bitchat.android.shell

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/**
 * The Indradhanu PWA, full screen, signed in as the phone's account.
 *
 * Only the command centre's own app origin is shown here; any other link opens in the
 * browser. When the page cannot load (the network dropped between the check and the
 * request), [onUnreachable] hands control back to the mesh rather than showing Chrome's
 * dinosaur.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OnlineAppScreen(
    url: String,
    bridgeFactory: (currentUrl: () -> String?) -> BiChatBridge,
    onUnreachable: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val origin = remember(url) { BiChatBridge.originOf(url) }
    var progress by remember { mutableFloatStateOf(0f) }
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val pageUrl = remember { arrayOf<String?>(null) }

    // <input type="file"> for report photos.
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) } ?: emptyArray())
        fileCallback = null
    }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    // The page asked for the microphone (voice report) before this app had permission:
    // ask Android, then answer the page.
    var pendingMic by remember { mutableStateOf<PermissionRequest?>(null) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        pendingMic?.let { req ->
            if (ok) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
        }
        pendingMic = null
    }

    val background = MaterialTheme.colorScheme.background

    Box(modifier.fillMaxSize().background(background)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(background.toArgb())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.setGeolocationEnabled(true)
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = true
                    settings.userAgentString = settings.userAgentString + " BiChat/1"
                    addJavascriptInterface(bridgeFactory { pageUrl[0] }, "BiChatNative")
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url.toString()
                            if (BiChatBridge.originOf(target) == origin) return false
                            // Supabase and map tiles load as subresources, not navigations, so
                            // only real link-outs end up here.
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                            } catch (_: Exception) { }
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            pageUrl[0] = url
                            loading = true
                        }

                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                            pageUrl[0] = url
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            // Only the page itself failing means "offline"; a missing tile does not.
                            if (request.isForMainFrame) onUnreachable()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress / 100f
                        }

                        override fun onGeolocationPermissionsShowPrompt(
                            requestOrigin: String, callback: GeolocationPermissions.Callback,
                        ) {
                            val ok = BiChatBridge.originOf(requestOrigin) == origin &&
                                (granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                                    granted(Manifest.permission.ACCESS_COARSE_LOCATION))
                            callback.invoke(requestOrigin, ok, false)
                        }

                        override fun onPermissionRequest(request: PermissionRequest) {
                            if (BiChatBridge.originOf(request.origin.toString()) != origin) {
                                request.deny(); return
                            }
                            val wantsMic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources
                            if (wantsMic && !granted(Manifest.permission.RECORD_AUDIO)) {
                                pendingMic?.deny()
                                pendingMic = request
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return
                            }
                            val allowed = request.resources.filter {
                                (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && granted(Manifest.permission.RECORD_AUDIO)) ||
                                    (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && granted(Manifest.permission.CAMERA))
                            }
                            if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
                        }

                        override fun onShowFileChooser(
                            view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
                        ): Boolean {
                            fileCallback?.onReceiveValue(emptyArray())
                            fileCallback = callback
                            val type = params.acceptTypes.firstOrNull { it.isNotBlank() } ?: "image/*"
                            return try { picker.launch(type); true } catch (_: Exception) {
                                fileCallback = null; false
                            }
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            },
        )

        AnimatedVisibility(loading, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)) {
                LinearProgressIndicator(
                    progress = { progress.coerceAtLeast(0.05f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
            }
        }
    }

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onExit()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                removeJavascriptInterface("BiChatNative")
                destroy()
            }
            webView = null
        }
    }
}

/** Shown while the online app is being prepared (config fetch). */
@Composable
fun OnlineAppPlaceholder(text: String) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}
