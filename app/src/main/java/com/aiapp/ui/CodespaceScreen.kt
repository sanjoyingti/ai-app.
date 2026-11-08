package com.aiapp.ui

import android.annotation.SuppressLint
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Which Codespace (if any) is open inside the app. */
object CodespaceState {
    var url by mutableStateOf<String?>(null)
    /** "owner/repo" of the open project, so its codespace can be remembered. */
    var repo by mutableStateOf<String?>(null)

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences("codespaces", android.content.Context.MODE_PRIVATE)

    /** Opens the project's codespace directly if we've been there before; otherwise the picker (once). */
    fun open(context: android.content.Context, repoUrl: String) {
        val path = repoUrl.removePrefix("https://github.com/").trimEnd('/')
        repo = path
        url = prefs(context).getString(path, null) ?: chooserUrl(path)
    }

    fun chooserUrl(path: String) = "https://codespaces.new/$path?quickstart=1"

    fun remember(context: android.content.Context, codespaceUrl: String) {
        val r = repo ?: return
        prefs(context).edit().putString(r, codespaceUrl).apply()
    }

    fun forget(context: android.content.Context) {
        val r = repo ?: return
        prefs(context).edit().remove(r).apply()
    }
}

/**
 * GitHub Codespaces (VS Code) running inside the app. Log in to GitHub once here; the login is
 * remembered. Back goes back inside VS Code first, then returns to the project.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodespaceScreen(url: String, onClose: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0) }
    var title by remember { mutableStateOf("Codespace") }
    val context = androidx.compose.ui.platform.LocalContext.current

    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close") } },
                actions = {
                    TextButton(onClick = { web?.reload() }) { Text("Reload") }
                    // If the saved codespace was deleted, pick or create another.
                    TextButton(onClick = {
                        CodespaceState.forget(context)
                        CodespaceState.repo?.let { web?.loadUrl(CodespaceState.chooserUrl(it)) }
                    }) { Text("Switch") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportMultipleWindows(true)
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        // Look like normal Chrome, so GitHub serves the full editor.
                        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            // Once VS Code opens (https://<name>.github.dev), remember it: next time it opens directly.
                            override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
                                val host = u?.let { android.net.Uri.parse(it).host }.orEmpty()
                                if (host.endsWith(".github.dev") && host != "github.dev") CodespaceState.remember(context, u!!)
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val scheme = request.url.scheme.orEmpty()
                                return scheme != "http" && scheme != "https" // keep web links inside; ignore app links
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView, t: String?) {
                                if (!t.isNullOrBlank()) title = t
                            }

                            // Sign-in pop-ups open in the same view.
                            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                                val popup = WebView(view.context)
                                popup.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                                        view.loadUrl(request.url.toString())
                                        return true
                                    }
                                }
                                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                                resultMsg.sendToTarget()
                                return true
                            }
                        }
                        loadUrl(url)
                        web = this
                    }
                },
                onRelease = { w ->
                    CookieManager.getInstance().flush()
                    w.destroy()
                },
            )
        }
    }
}
