package de.davidgrieser.container

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.webkit.WebViewClientCompat

/**
 * Enforces the domain lock: any navigation whose host leaves the currently
 * selected app's registered domain is refused. This covers user taps on links,
 * redirects and `target="_blank"` (multiple windows are disabled, so those are
 * routed here too).
 *
 * It also decides what happens on a TLS error: by default the load is
 * cancelled, but when the admin has enabled "allow unverified certificates"
 * ([allowUnverifiedSsl]) the page is loaded anyway.
 */
class KioskWebViewClient(
    private val allowUnverifiedSsl: () -> Boolean,
    private val onBlocked: (String) -> Unit,
    private val onSslError: (SslError) -> Unit,
    private val onPageStarted: () -> Unit,
    private val onPageFinished: () -> Unit
) : WebViewClientCompat() {

    /** The URL that anchors the domain lock; updated whenever the user switches app. */
    @Volatile
    var anchorUrl: String? = null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val target = request.url?.toString()
        if (DomainRules.isAllowed(anchorUrl, target)) {
            return false // let the WebView load it
        }
        // Block anything that would leave the anchored domain.
        target?.let(onBlocked)
        return true
    }

    /**
     * The WebView cancels loads with a certificate problem unless we say
     * otherwise. When the admin opted in, we proceed — but only for URLs that
     * are inside the anchored domain, so a bad certificate on some unrelated
     * third-party host is still refused.
     */
    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val failingUrl = error.url
        if (allowUnverifiedSsl() && DomainRules.isAllowed(anchorUrl, failingUrl)) {
            Log.w(TAG, "Proceeding despite SSL error ${error.primaryError} for $failingUrl")
            handler.proceed()
            return
        }
        Log.w(TAG, "Cancelling load, SSL error ${error.primaryError} for $failingUrl")
        handler.cancel()
        onSslError(error)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStarted()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished()
    }

    companion object {
        private const val TAG = "KioskWebViewClient"
    }
}
