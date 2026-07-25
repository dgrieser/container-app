package de.davidgrieser.container

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.davidgrieser.container.databinding.ActivityMainBinding
import de.davidgrieser.container.databinding.DialogAdminBinding
import de.davidgrieser.container.databinding.DialogPinBinding
import de.davidgrieser.container.databinding.ItemAppBinding
import de.davidgrieser.container.databinding.SheetMenuBinding
import de.davidgrieser.container.model.AppEntry
import de.davidgrieser.container.model.KioskConfig
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var pinManager: PinManager
    private lateinit var repository: ConfigRepository
    private lateinit var webClient: KioskWebViewClient

    private var config: KioskConfig = KioskConfig(emptyList())
    private var currentApp: AppEntry? = null

    /** Guards against a toast per failing sub-resource; reset on every page load. */
    private var sslErrorReported = false

    private val idleHandler = Handler(Looper.getMainLooper())

    /** Fades the menu button back down once the user has stopped interacting. */
    private val dimMenuButton = Runnable {
        binding.fabMenu.animate().alpha(FAB_ALPHA_IDLE).setDuration(400).start()
    }

    /** Where a still-running corner hold (the hidden admin gesture) started. */
    private var adminGestureOrigin: Pair<Float, Float>? = null

    private val openAdminMenu = Runnable {
        adminGestureOrigin = null
        onAdminClicked()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        pinManager = PinManager(prefs)
        repository = ConfigRepository(prefs)

        enableImmersiveMode()
        setupWebView()

        // Variants that pin the app to a single page hide the menu entirely; the
        // admin menu is then reached by holding the bottom-right corner.
        binding.fabMenu.isVisible = BuildConfig.SHOW_MENU
        binding.fabMenu.setOnClickListener { openMenu() }
        binding.statePrimaryButton.setOnClickListener { loadConfig() }
        binding.stateSecondaryButton.setOnClickListener { onAdminClicked() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    // Stay inside the container instead of exiting to the launcher.
                    moveTaskToBack(true)
                }
            }
        })

        wakeMenuButton()
        bootstrap()
    }

    // --- Bootstrapping -----------------------------------------------------

    private fun bootstrap() {
        if (BuildConfig.REQUIRE_PIN && !pinManager.isPinSet) {
            promptSetPin(mandatory = true) { loadConfig() }
        } else {
            loadConfig()
        }
    }

    private fun loadConfig() {
        showState(getString(R.string.loading), primary = null, secondary = null)
        lifecycleScope.launch {
            val result = repository.load()
            val loaded = result.getOrNull() ?: repository.cached()
            if (loaded == null) {
                showState(
                    getString(R.string.config_load_failed),
                    primary = getString(R.string.config_retry),
                    secondary = getString(R.string.config_open_admin)
                )
                return@launch
            }
            if (result.isFailure) {
                Toast.makeText(
                    this@MainActivity, R.string.config_load_failed, Toast.LENGTH_SHORT
                ).show()
            }
            render(loaded)
        }
    }

    private fun render(loaded: KioskConfig) {
        config = loaded
        // An absolute defaultKioskPath resolves on its own, so a variant without a
        // configuration file still has a page here; anything else needs the list.
        val fallback = defaultApp(loaded) ?: loaded.apps.firstOrNull()
        if (fallback == null) {
            showState(
                getString(R.string.config_empty),
                primary = getString(R.string.config_retry),
                secondary = getString(R.string.config_open_admin)
            )
            return
        }
        // A page pinned by defaultKioskPath is not necessarily one of the listed
        // apps (and for a variant without a configuration file there is no list at
        // all), so put it in the menu — otherwise switching away from it would be
        // a one-way trip.
        if (loaded.apps.none { it.url == fallback.url }) {
            config = KioskConfig(loaded.apps + fallback)
        }
        // With the menu hidden the user cannot switch anyway, so such a variant
        // always opens its configured page instead of the last selection.
        val target = if (!BuildConfig.SHOW_MENU) {
            fallback
        } else {
            val previous = prefs.selectedAppUrl
            loaded.apps.firstOrNull { it.url == previous } ?: fallback
        }
        selectApp(target)
    }

    /**
     * Resolves this variant's `defaultKioskPath` against the loaded config. It
     * may be an absolute http(s) URL, a path such as `/dashboard`, or the name
     * of one of the configured apps. An absolute URL that matches nothing in the
     * config is still honoured, so a variant can be pinned to a page the shared
     * config file does not list.
     */
    private fun defaultApp(loaded: KioskConfig): AppEntry? {
        val wanted = BuildConfig.DEFAULT_KIOSK_PATH.trim()
        if (wanted.isEmpty()) return null

        if (DomainRules.isHttp(wanted)) {
            return loaded.apps.firstOrNull { it.url == wanted }
                ?: loaded.apps.firstOrNull { it.url.startsWith(wanted) }
                ?: AppEntry(getString(R.string.app_name), null, wanted)
        }

        val path = if (wanted.startsWith("/")) wanted else "/$wanted"
        return loaded.apps.firstOrNull { pathOf(it.url) == path }
            ?: loaded.apps.firstOrNull { pathOf(it.url).startsWith(path) }
            ?: loaded.apps.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    }

    private fun pathOf(url: String): String =
        Uri.parse(url).path?.ifEmpty { "/" } ?: "/"

    private fun selectApp(entry: AppEntry) {
        currentApp = entry
        prefs.selectedAppUrl = entry.url
        webClient.anchorUrl = entry.url
        hideState()
        binding.webView.loadUrl(entry.url)
    }

    // --- WebView -----------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webClient = KioskWebViewClient(
            allowUnverifiedSsl = { prefs.allowUnverifiedSsl },
            onBlocked = { blocked ->
                val host = DomainRules.host(currentApp?.url) ?: ""
                Toast.makeText(
                    this, getString(R.string.nav_blocked, host), Toast.LENGTH_SHORT
                ).show()
            },
            onSslError = { error ->
                if (!sslErrorReported) {
                    sslErrorReported = true
                    val host = DomainRules.host(error.url) ?: getString(R.string.ssl_blocked_host)
                    Toast.makeText(
                        this, getString(R.string.ssl_blocked, host), Toast.LENGTH_LONG
                    ).show()
                }
            },
            onPageStarted = {
                sslErrorReported = false
                binding.progress.isVisible = true
            },
            onPageFinished = {
                binding.progress.isVisible = false
                binding.swipeRefresh.isRefreshing = false
            }
        )
        binding.swipeRefresh.apply {
            setColorSchemeResources(R.color.brand_primary)
            setOnRefreshListener { reloadCurrentPage() }
        }
        binding.webView.apply {
            webViewClient = webClient
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    binding.progress.progress = newProgress
                    binding.progress.isVisible = newProgress in 1..99
                }
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = true
            }
        }
    }

    /**
     * Pull-to-refresh target. Reloads the page in place; if there is nothing
     * loaded (the very first load failed, so the WebView still sits on
     * `about:blank`), the selected app is loaded from scratch instead.
     */
    private fun reloadCurrentPage() {
        val loaded = binding.webView.url
        val entry = currentApp
        when {
            !loaded.isNullOrBlank() && loaded != BLANK_URL -> binding.webView.reload()
            entry != null -> binding.webView.loadUrl(entry.url)
            else -> {
                binding.swipeRefresh.isRefreshing = false
                loadConfig()
            }
        }
    }

    // --- Menu --------------------------------------------------------------

    private fun openMenu() {
        val sheet = BottomSheetDialog(this)
        val menu = SheetMenuBinding.inflate(layoutInflater)
        sheet.setContentView(menu.root)

        menu.appList.removeAllViews()
        config.apps.forEach { entry ->
            val row = ItemAppBinding.inflate(layoutInflater, menu.appList, false)
            row.appName.text = entry.name
            row.appSelected.isVisible = entry.url == currentApp?.url
            row.appIcon.setImageResource(R.drawable.ic_app_placeholder)
            entry.iconUrl?.let { iconUrl ->
                lifecycleScope.launch {
                    IconLoader.load(iconUrl, prefs.allowUnverifiedSsl)
                        ?.let { row.appIcon.setImageBitmap(it) }
                }
            }
            row.root.setOnClickListener {
                sheet.dismiss()
                if (entry.url != currentApp?.url) selectApp(entry)
            }
            menu.appList.addView(row.root)
        }

        menu.adminRow.setOnClickListener {
            sheet.dismiss()
            onAdminClicked()
        }
        sheet.show()
    }

    // --- Admin -------------------------------------------------------------

    private fun onAdminClicked() {
        if (!BuildConfig.REQUIRE_PIN) {
            showAdminDialog()
            return
        }
        if (!pinManager.isPinSet) {
            promptSetPin(mandatory = true) { showAdminDialog() }
            return
        }
        promptEnterPin { showAdminDialog() }
    }

    private fun showAdminDialog() {
        val view = DialogAdminBinding.inflate(layoutInflater)
        view.configUrlInput.setText(prefs.configUrl)
        view.configUrlInput.hint = BuildConfig.DEFAULT_CONFIG_URL
            .ifEmpty { getString(R.string.admin_config_url_optional) }
        view.allowUnverifiedSslSwitch.isChecked = prefs.allowUnverifiedSsl
        view.btnChangePin.isVisible = BuildConfig.REQUIRE_PIN

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_title)
            .setView(view.root)
            .setPositiveButton(R.string.admin_save, null)
            .setNegativeButton(R.string.admin_cancel, null)
            .create()

        view.btnChangePin.setOnClickListener {
            dialog.dismiss()
            promptChangePin()
        }
        view.btnReload.setOnClickListener {
            if (saveAdminSettings(view)) {
                dialog.dismiss()
                Toast.makeText(this, R.string.reloading, Toast.LENGTH_SHORT).show()
                loadConfig()
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saveAdminSettings(view)) {
                    dialog.dismiss()
                    Toast.makeText(this, R.string.admin_url_saved, Toast.LENGTH_SHORT).show()
                    loadConfig()
                }
            }
        }
        dialog.show()
    }

    /**
     * Validates and persists the admin settings (config URL and TLS handling).
     * Returns false (and shows an error) if the URL is invalid. An empty field
     * means "use this variant's built-in URL" — and for a variant that ships
     * without one, that no configuration file is read at all.
     */
    private fun saveAdminSettings(view: DialogAdminBinding): Boolean {
        val url = view.configUrlInput.text?.toString()?.trim().orEmpty()
        if (url.isNotEmpty() && !DomainRules.isHttp(url)) {
            Toast.makeText(this, R.string.admin_url_invalid, Toast.LENGTH_SHORT).show()
            return false
        }
        prefs.configUrl = url

        val allowUnverifiedSsl = view.allowUnverifiedSslSwitch.isChecked
        if (allowUnverifiedSsl != prefs.allowUnverifiedSsl) {
            prefs.allowUnverifiedSsl = allowUnverifiedSsl
            // Drop per-host "proceed" decisions the WebView remembered, so the
            // new setting takes effect for hosts that were already visited.
            binding.webView.clearSslPreferences()
        }
        return true
    }

    // --- PIN dialogs -------------------------------------------------------

    private fun promptSetPin(mandatory: Boolean, onDone: () -> Unit) {
        collectPin(
            title = getString(R.string.pin_set_title),
            message = getString(R.string.pin_set_message),
            cancelable = !mandatory
        ) { first ->
            collectPin(
                title = getString(R.string.pin_confirm_title),
                message = getString(R.string.pin_confirm_message),
                cancelable = !mandatory
            ) { second ->
                if (first != second) {
                    Toast.makeText(this, R.string.pin_mismatch, Toast.LENGTH_SHORT).show()
                    promptSetPin(mandatory, onDone)
                } else {
                    pinManager.setPin(first)
                    onDone()
                }
            }
        }
    }

    private fun promptEnterPin(onSuccess: () -> Unit) {
        collectPin(
            title = getString(R.string.pin_enter_title),
            message = getString(R.string.pin_enter_message),
            cancelable = true,
            validateLength = false
        ) { entered ->
            if (pinManager.verify(entered)) {
                onSuccess()
            } else {
                Toast.makeText(this, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun promptChangePin() {
        collectPin(
            title = getString(R.string.pin_change_title),
            message = getString(R.string.pin_current_message),
            cancelable = true,
            validateLength = false
        ) { current ->
            if (!pinManager.verify(current)) {
                Toast.makeText(this, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                return@collectPin
            }
            collectPin(
                title = getString(R.string.pin_change_title),
                message = getString(R.string.pin_new_message),
                cancelable = true
            ) { newPin ->
                collectPin(
                    title = getString(R.string.pin_confirm_title),
                    message = getString(R.string.pin_confirm_message),
                    cancelable = true
                ) { confirm ->
                    if (newPin != confirm) {
                        Toast.makeText(this, R.string.pin_mismatch, Toast.LENGTH_SHORT).show()
                    } else {
                        pinManager.setPin(newPin)
                        Toast.makeText(this, R.string.pin_changed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * Shows a single PIN-entry dialog. [validateLength] enforces the minimum PIN
     * length (used when creating/changing a PIN, not when merely entering one).
     */
    private fun collectPin(
        title: String,
        message: String,
        cancelable: Boolean,
        validateLength: Boolean = true,
        onEntered: (String) -> Unit
    ) {
        val view = DialogPinBinding.inflate(LayoutInflater.from(this))
        view.pinMessage.text = message

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view.root)
            .setCancelable(cancelable)
            .setPositiveButton(android.R.string.ok, null)
        if (cancelable) builder.setNegativeButton(R.string.admin_cancel, null)

        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setOnShowListener {
            view.pinInput.requestFocus()
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = view.pinInput.text?.toString().orEmpty()
                if (validateLength && pin.length < PinManager.MIN_LENGTH) {
                    Toast.makeText(this, R.string.pin_too_short, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onEntered(pin)
            }
        }
        dialog.show()
    }

    // --- State view --------------------------------------------------------

    private fun showState(message: String, primary: String?, secondary: String?) {
        binding.swipeRefresh.isRefreshing = false
        binding.swipeRefresh.isVisible = false
        binding.stateContainer.isVisible = true
        binding.stateMessage.text = message
        binding.statePrimaryButton.isVisible = primary != null
        primary?.let { binding.statePrimaryButton.text = it }
        binding.stateSecondaryButton.isVisible = secondary != null
        secondary?.let { binding.stateSecondaryButton.text = it }
    }

    private fun hideState() {
        binding.stateContainer.isVisible = false
        binding.swipeRefresh.isVisible = true
    }

    // --- Menu button ---------------------------------------------------------

    /**
     * Brings the menu button back to (still modest) full opacity while the user
     * is touching the screen, then schedules it to fade away again.
     */
    private fun wakeMenuButton() {
        if (!BuildConfig.SHOW_MENU) return
        idleHandler.removeCallbacks(dimMenuButton)
        binding.fabMenu.animate().alpha(FAB_ALPHA_ACTIVE).setDuration(120).start()
        idleHandler.postDelayed(dimMenuButton, FAB_IDLE_DELAY_MS)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        wakeMenuButton()
    }

    // --- Hidden admin gesture ------------------------------------------------

    /**
     * Menu-less variants have no visible way into the admin menu, so holding the
     * bottom-right corner for [ADMIN_HOLD_MS] opens it. The gesture is tracked
     * without consuming the events, so nothing is taken away from the page — the
     * corner keeps working for whatever the web app puts there.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!BuildConfig.SHOW_MENU) trackAdminGesture(event)
        return super.dispatchTouchEvent(event)
    }

    private fun trackAdminGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val corner = resources.displayMetrics.density * ADMIN_CORNER_DP
                val inCorner = event.x > binding.root.width - corner &&
                    event.y > binding.root.height - corner
                if (inCorner) {
                    adminGestureOrigin = event.x to event.y
                    idleHandler.postDelayed(openAdminMenu, ADMIN_HOLD_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val (startX, startY) = adminGestureOrigin ?: return
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (abs(event.x - startX) > slop || abs(event.y - startY) > slop) {
                    cancelAdminGesture()
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN -> cancelAdminGesture()
        }
    }

    private fun cancelAdminGesture() {
        if (adminGestureOrigin == null) return
        adminGestureOrigin = null
        idleHandler.removeCallbacks(openAdminMenu)
    }

    // --- Immersive mode ----------------------------------------------------

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onDestroy() {
        idleHandler.removeCallbacks(dimMenuButton)
        idleHandler.removeCallbacks(openAdminMenu)
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val BLANK_URL = "about:blank"

        /** Opacity of the menu button while the user is interacting, and at rest. */
        private const val FAB_ALPHA_ACTIVE = 0.75f
        private const val FAB_ALPHA_IDLE = 0.3f
        private const val FAB_IDLE_DELAY_MS = 2_500L

        /** Hidden admin gesture: hold this corner square for this long. */
        private const val ADMIN_CORNER_DP = 72f
        private const val ADMIN_HOLD_MS = 1_500L
    }
}
