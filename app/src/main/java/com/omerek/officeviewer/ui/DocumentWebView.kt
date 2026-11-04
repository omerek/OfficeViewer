package com.omerek.officeviewer.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/** Imperative handle so the toolbar can drive search and printing. */
class WebViewHandle {
    internal var webView: WebView? = null
    var onFindResult: ((active: Int, total: Int, done: Boolean) -> Unit)? = null

    fun find(query: String) {
        val wv = webView ?: return
        if (query.isEmpty()) {
            wv.clearMatches()
            onFindResult?.invoke(0, 0, true)
        } else {
            wv.findAllAsync(query)
        }
    }

    fun findNext(forward: Boolean) {
        webView?.findNext(forward)
    }

    fun clearFind() {
        webView?.clearMatches()
    }

    fun print(context: Context, jobName: String) {
        val wv = webView ?: return
        val pm = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        pm.print(jobName, wv.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
    }
}

private const val DOC_ORIGIN = "https://ov.local/"
private const val DOC_URL = DOC_ORIGIN + "document.html"

/**
 * Shows converted HTML. The page is served from a virtual origin through
 * shouldInterceptRequest, so there is no size limit as with data URLs and no request can
 * ever reach the network (the app also has no INTERNET permission).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DocumentWebView(html: String, handle: WebViewHandle, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.parseColor("#E8EAED"))
                settings.apply {
                    // Scripts are only our own (sheet tabs, slide scaling); document text is escaped.
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    textZoom = 100
                    setGeolocationEnabled(false)
                }
                webViewClient = DocClient()
                setFindListener { active, total, done ->
                    handle.onFindResult?.invoke(if (total == 0) 0 else active + 1, total, done)
                }
                handle.webView = this
                (webViewClient as DocClient).html = html
                loadUrl(DOC_URL)
                tag = html.hashCode()
            }
        },
        update = { wv ->
            if (wv.tag != html.hashCode()) {
                (wv.webViewClient as DocClient).html = html
                wv.tag = html.hashCode()
                wv.loadUrl(DOC_URL)
            }
        },
    )
    DisposableEffect(handle) {
        onDispose {
            handle.webView?.destroy()
            handle.webView = null
        }
    }
}

private class DocClient : WebViewClient() {
    @Volatile var html: String = ""

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val url = request.url.toString()
        return if (url == DOC_URL) {
            WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        } else {
            // Block everything else (remote images, trackers, ...).
            WebResourceResponse("text/plain", "utf-8", 404, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (uri.toString().startsWith(DOC_ORIGIN)) return false
        val scheme = uri.scheme?.lowercase()
        if (scheme in setOf("http", "https", "mailto", "tel")) {
            try {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
            }
        }
        return true
    }
}

fun shareDocument(context: Context, uri: Uri, mime: String?, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime ?: "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, title)) }
}

fun openWith(context: Context, uri: Uri, mime: String?, title: String): Boolean {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching { context.startActivity(Intent.createChooser(view, title)) }.isSuccess
}
