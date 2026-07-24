package de.davidgrieser.container

import android.util.Log
import de.davidgrieser.container.model.AppEntry
import de.davidgrieser.container.model.KioskConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads the remotely-controlled configuration from the admin-configured URL.
 *
 * Expected JSON shape:
 * ```
 * {
 *   "apps": [
 *     { "name": "Portal", "icon": "https://…/portal.png", "url": "https://portal.example.com/" }
 *   ]
 * }
 * ```
 * A bare top-level array is also accepted for convenience.
 *
 * The last successfully parsed config is cached via [Prefs] so the app keeps
 * working when the network or the config host is unavailable.
 */
class ConfigRepository(private val prefs: Prefs) {

    suspend fun load(): Result<KioskConfig> = withContext(Dispatchers.IO) {
        val url = prefs.configUrl
        runCatching {
            val body = fetch(url)
            val config = parse(body)
            prefs.saveCachedConfig(config)
            config
        }.onFailure { Log.w(TAG, "Failed to load config from $url", it) }
    }

    fun cached(): KioskConfig? = prefs.loadCachedConfig()

    private fun fetch(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code loading config")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): KioskConfig {
        val trimmed = body.trim()
        val array: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> JSONObject(trimmed).optJSONArray("apps") ?: JSONArray()
        }
        val apps = ArrayList<AppEntry>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            if (!DomainRules.isHttp(url)) continue
            val name = o.optString("name").trim().ifEmpty { DomainRules.host(url) ?: url }
            val icon = o.optString("icon").trim().takeIf { it.isNotBlank() && it != "null" }
            apps.add(AppEntry(name, icon, url))
        }
        return KioskConfig(apps)
    }

    companion object {
        private const val TAG = "ConfigRepository"
    }
}
