package de.davidgrieser.container

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.webkit.WebViewClientCompat

/**
 * Enforces the domain lock: any navigation whose host leaves the currently
 * selected app's registered domain is refused. This covers user taps on links,
 * redirects and `target="_blank"` (multiple windows are disabled, so those are
 * routed here too).
 */
class KioskWebViewClient(
    private val onBlocked: (String) -> Unit,
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

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStarted()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished()
    }
}
