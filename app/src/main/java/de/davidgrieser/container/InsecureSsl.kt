package de.davidgrieser.container

import android.annotation.SuppressLint
import java.net.HttpURLConnection
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Opt-in relaxation of TLS verification for the app's own HTTP calls (config
 * fetch and menu icons). It is only ever used when the admin has enabled
 * "allow unverified certificates" — see [Prefs.allowUnverifiedSsl].
 *
 * This exists for kiosk deployments against internal servers that use
 * self-signed certificates, an internal CA that is not installed on the device,
 * or a certificate issued for a different name than the one the device dials.
 * It disables certificate *and* hostname checking, so traffic is no longer
 * protected against interception — keep it off unless the network is trusted.
 */
object InsecureSsl {

    @SuppressLint("CustomX509TrustManager")
    private val trustAllCertificates = object : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @SuppressLint("BadHostnameVerifier")
    private val acceptAllHostnames = HostnameVerifier { _, _ -> true }

    private val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf(trustAllCertificates), SecureRandom()) }
            .socketFactory
    }

    /**
     * Turns off certificate and hostname verification for [conn]. Plain-HTTP
     * connections are left untouched.
     */
    fun applyTo(conn: HttpURLConnection) {
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = socketFactory
            conn.hostnameVerifier = acceptAllHostnames
        }
    }
}
