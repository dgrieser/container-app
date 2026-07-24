package de.davidgrieser.container

import android.net.Uri

/**
 * Central place for the "cannot navigate away to another domain" rule.
 *
 * A candidate URL is allowed only when it uses http/https AND its host belongs
 * to the same registered domain as the currently selected app. Subdomains in
 * either direction are permitted (e.g. an app on `portal.example.com` may link
 * to `example.com` and `cdn.example.com`), but a jump to `other.com` is not.
 */
object DomainRules {

    fun host(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val h = Uri.parse(url).host?.lowercase()?.trim() ?: return null
        return h.removePrefix("www.").ifEmpty { null }
    }

    fun isHttp(url: String?): Boolean {
        val scheme = Uri.parse(url ?: return false).scheme?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** True when [candidateUrl] may be loaded while the app is anchored to [appUrl]. */
    fun isAllowed(appUrl: String?, candidateUrl: String?): Boolean {
        if (!isHttp(candidateUrl)) return false
        val appHost = host(appUrl) ?: return false
        val candidateHost = host(candidateUrl) ?: return false
        return candidateHost == appHost ||
            candidateHost.endsWith(".$appHost") ||
            appHost.endsWith(".$candidateHost")
    }
}
