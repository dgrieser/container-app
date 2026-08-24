package de.davidgrieser.container

import android.content.Context
import android.content.SharedPreferences
import de.davidgrieser.container.model.AppEntry
import de.davidgrieser.container.model.KioskConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin wrapper around [SharedPreferences] holding all persisted state:
 * the PIN (salted hash), the configuration URL, the screen mode, the
 * last-known-good config and the currently selected app.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- PIN ---------------------------------------------------------------

    var pinHash: String?
        get() = sp.getString(KEY_PIN_HASH, null)
        set(value) = sp.edit().putString(KEY_PIN_HASH, value).apply()

    var pinSalt: String?
        get() = sp.getString(KEY_PIN_SALT, null)
        set(value) = sp.edit().putString(KEY_PIN_SALT, value).apply()

    val isPinSet: Boolean get() = !pinHash.isNullOrEmpty() && !pinSalt.isNullOrEmpty()

    // --- Configuration URL -------------------------------------------------

    /**
     * Which kiosk.json to read. Falls back to the variant's built-in URL, so
     * clearing the admin field restores the default. Empty when the variant has
     * no configuration file at all — such a build only shows its
     * `defaultKioskPath`.
     */
    var configUrl: String
        get() = sp.getString(KEY_CONFIG_URL, null)?.trim()?.ifEmpty { null }
            ?: BuildConfig.DEFAULT_CONFIG_URL
        set(value) = sp.edit().putString(KEY_CONFIG_URL, value.trim()).apply()

    // --- TLS -----------------------------------------------------------------

    /**
     * When true, pages (and the config/icon downloads) are loaded even if the
     * server's certificate cannot be verified — self-signed, expired, issued by
     * an unknown CA or for a different host name. Defaults to the variant's
     * `allowUnverifiedSsl` (itself false unless declared), and stays whatever the
     * admin last chose.
     */
    var allowUnverifiedSsl: Boolean
        get() = sp.getBoolean(KEY_ALLOW_UNVERIFIED_SSL, BuildConfig.ALLOW_UNVERIFIED_SSL)
        set(value) = sp.edit().putBoolean(KEY_ALLOW_UNVERIFIED_SSL, value).apply()

    // --- Screen mode ---------------------------------------------------------

    /**
     * Which system bars stay visible over the page. Starts at the variant's
     * `screenMode` and then stays whatever the admin last picked.
     */
    var screenMode: ScreenMode
        get() = ScreenMode.fromId(
            sp.getString(KEY_SCREEN_MODE, null) ?: BuildConfig.SCREEN_MODE
        )
        set(value) = sp.edit().putString(KEY_SCREEN_MODE, value.id).apply()

    // --- Selected app -------------------------------------------------------

    var selectedAppUrl: String?
        get() = sp.getString(KEY_SELECTED_APP, null)
        set(value) = sp.edit().putString(KEY_SELECTED_APP, value).apply()

    // --- Cached configuration ----------------------------------------------

    fun saveCachedConfig(config: KioskConfig) {
        val arr = JSONArray()
        config.apps.forEach { app ->
            arr.put(
                JSONObject()
                    .put("name", app.name)
                    .put("icon", app.iconUrl ?: JSONObject.NULL)
                    .put("url", app.url)
            )
        }
        sp.edit().putString(KEY_CACHED_CONFIG, JSONObject().put("apps", arr).toString()).apply()
    }

    fun loadCachedConfig(): KioskConfig? {
        val raw = sp.getString(KEY_CACHED_CONFIG, null) ?: return null
        return runCatching {
            val arr = JSONObject(raw).optJSONArray("apps") ?: JSONArray()
            val apps = ArrayList<AppEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.optString("url").trim()
                if (url.isEmpty()) continue
                val icon = o.optString("icon").takeIf { it.isNotBlank() && it != "null" }
                apps.add(AppEntry(o.optString("name").trim(), icon, url))
            }
            KioskConfig(apps)
        }.getOrNull()
    }

    companion object {
        private const val FILE = "container_prefs"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_CONFIG_URL = "config_url"
        private const val KEY_ALLOW_UNVERIFIED_SSL = "allow_unverified_ssl"
        private const val KEY_SCREEN_MODE = "screen_mode"
        private const val KEY_SELECTED_APP = "selected_app_url"
        private const val KEY_CACHED_CONFIG = "cached_config"
    }
}
