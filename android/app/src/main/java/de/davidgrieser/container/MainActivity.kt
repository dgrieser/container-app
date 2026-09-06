package de.davidgrieser.container

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
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

    /** Same, for a page that asks for the position again after being refused. */
    private var locationRefusalReported = false

    /**
     * The page's pending request for the device's position, waiting for Android's
     * own permission dialog to come back.
     */
    private var pendingLocationRequest: Pair<String, GeolocationPermissions.Callback>? = null

    /**
     * Asks Android for the location permission on the page's behalf. Registered
     * here rather than on demand because a launcher has to exist before the
     * activity starts.
     */
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val (origin, callback) = pendingLocationRequest ?: return@registerForActivityResult
        pendingLocationRequest = null
        // Either one is enough: from Android 12 the user may pick the coarse
        // permission alone, which still yields a position, just a rougher one.
        val granted = grants.values.any { it }
        callback.invoke(origin, granted, false)
        if (!granted) reportLocationRefusal(R.string.location_permission_denied)
    }

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

        applyScreenMode()
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
            allowExternalNavigation = { BuildConfig.ALLOW_EXTERNAL_NAVIGATION },
            onExternalNavigation = ::openOutsideContainer,
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
                locationRefusalReported = false
                binding.progress.isVisible = true
            },
            onPageFinished = {
                binding.progress.isVisible = false
                binding.swipeRefresh.isRefreshing = false
            }
        )
        binding.swipeRefresh.apply {
            // A disabled SwipeRefreshLayout stops intercepting the gesture
            // altogether, so a page that reads a downward drag itself keeps every
            // touch. Fixed per build; the admin menu's "Reload configuration"
            // reloads such a page instead.
            isEnabled = BuildConfig.PULL_TO_REFRESH
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

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    onLocationRequested(origin, callback)
                }

                override fun onGeolocationPermissionsHidePrompt() {
                    // The page gave up on the request (a navigation, usually), so
                    // a permission result arriving now has nothing to answer.
                    pendingLocationRequest = null
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
                // A build without location does not merely refuse the prompt: the
                // page's `navigator.geolocation` fails outright, which is the
                // answer a page can actually handle.
                setGeolocationEnabled(BuildConfig.ALLOW_LOCATION)
            }
        }
    }

    /**
     * Hands [url] to whatever the device has registered for it — the default
     * browser, or an app that claims the link. Used for off-domain links in
     * variants built with `allowExternalNavigation: true`; the container keeps
     * showing its own page, and the link opens in its own task so returning
     * lands back here.
     *
     * Returns false when nothing on the device can open it, which leaves the
     * navigation blocked as it would be by default.
     */
    private fun openOutsideContainer(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            // Only components that accept links from the web, so a page cannot
            // reach anything that never expected untrusted input.
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No handler for external link $url", e)
            false
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

    // --- Location ----------------------------------------------------------

    /**
     * Answers the page's request for the device's position. Three things have to
     * hold, in this order: the build allows location at all, the asking origin is
     * inside the domain lock, and Android has granted the app the permission —
     * which is requested here when it has not.
     *
     * The answer is deliberately never retained. The WebView would stop asking,
     * and a permission later revoked in Android's settings could then no longer
     * be noticed; asking again costs nothing, because a permission already held
     * needs no dialog.
     */
    private fun onLocationRequested(origin: String?, callback: GeolocationPermissions.Callback?) {
        if (callback == null) return
        if (origin.isNullOrBlank()) {
            callback.invoke("", false, false)
            return
        }
        if (!BuildConfig.ALLOW_LOCATION) {
            // Belt and braces: geolocation is switched off in the WebView's own
            // settings for such a build, so the page never gets this far.
            callback.invoke(origin, false, false)
            return
        }
        // The position is as much the page's to ask for as anything else it does,
        // so the domain lock decides here too: an embedded third-party frame
        // cannot borrow the permission the anchored site was granted.
        if (!DomainRules.isAllowed(currentApp?.url, origin)) {
            callback.invoke(origin, false, false)
            reportLocationRefusal(
                R.string.location_blocked,
                DomainRules.host(origin) ?: getString(R.string.ssl_blocked_host)
            )
            return
        }
        if (hasLocationPermission()) {
            callback.invoke(origin, true, false)
            return
        }
        // Only one request can be waiting on the dialog. Should a second arrive
        // first, answer it rather than leaving the page waiting on a promise
        // nothing will ever settle; it can ask again.
        pendingLocationRequest?.let { (pendingOrigin, pending) ->
            pending.invoke(pendingOrigin, false, false)
        }
        pendingLocationRequest = origin to callback
        locationPermissionRequest.launch(LOCATION_PERMISSIONS)
    }

    private fun hasLocationPermission(): Boolean = LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /** One explanation per page load, as for a failing certificate. */
    private fun reportLocationRefusal(@StringRes message: Int, vararg formatArgs: Any) {
        if (locationRefusalReported) return
        locationRefusalReported = true
        Toast.makeText(this, getString(message, *formatArgs), Toast.LENGTH_LONG).show()
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
        val pickedScreenMode = setupScreenModePicker(view)

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
            if (saveAdminSettings(view, pickedScreenMode())) {
                dialog.dismiss()
                Toast.makeText(this, R.string.reloading, Toast.LENGTH_SHORT).show()
                loadConfig()
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saveAdminSettings(view, pickedScreenMode())) {
                    dialog.dismiss()
                    Toast.makeText(this, R.string.admin_saved, Toast.LENGTH_SHORT).show()
                    loadConfig()
                }
            }
        }
        dialog.show()
    }

    /**
     * Fills the screen-mode dropdown with the [ScreenMode] labels, preselects the
     * current one and keeps the explanation underneath in step with the choice.
     * Returns a getter for what is selected right now, because the dialog only
     * persists it when the settings are saved.
     */
    private fun setupScreenModePicker(view: DialogAdminBinding): () -> ScreenMode {
        val modes = ScreenMode.entries
        var selected = prefs.screenMode
        view.screenModeInput.setSimpleItems(
            modes.map { getString(it.labelRes) }.toTypedArray()
        )
        // filter = false: this is a fixed list, not something to type into, so the
        // text must not narrow the popup down to itself.
        view.screenModeInput.setText(getString(selected.labelRes), false)
        view.screenModeHint.setText(selected.descriptionRes)
        view.screenModeInput.setOnItemClickListener { _, _, position, _ ->
            selected = modes[position]
            view.screenModeHint.setText(selected.descriptionRes)
        }
        return { selected }
    }

    /**
     * Validates and persists the admin settings (config URL, screen mode and TLS
     * handling). Returns false (and shows an error) if the URL is invalid. An
     * empty field means "use this variant's built-in URL" — and for a variant
     * that ships without one, that no configuration file is read at all.
     */
    private fun saveAdminSettings(view: DialogAdminBinding, screenMode: ScreenMode): Boolean {
        val url = view.configUrlInput.text?.toString()?.trim().orEmpty()
        if (url.isNotEmpty() && !DomainRules.isHttp(url)) {
            Toast.makeText(this, R.string.admin_url_invalid, Toast.LENGTH_SHORT).show()
            return false
        }
        prefs.configUrl = url

        if (screenMode != prefs.screenMode) {
            prefs.screenMode = screenMode
            applyScreenMode()
        }

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

    // --- Screen mode -------------------------------------------------------

    /**
     * Puts the window into the configured [ScreenMode]: the bars that mode wants
     * are shown in the variant's colour, every other one is hidden, and the
     * layout is padded so a visible bar sits next to the page instead of over it.
     *
     * A hidden bar can still be swiped in for a moment. That does not change any
     * inset, so the page never jumps when the user peeks at the clock.
     */
    private fun applyScreenMode() {
        val mode = prefs.screenMode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (mode.hiddenBars != 0) controller.hide(mode.hiddenBars)
        if (mode.visibleBars != 0) controller.show(mode.visibleBars)

        // The window decor paints these behind the bars. Only a bar this mode
        // keeps gets the variant's colour: a hidden one has nothing to paint, and
        // one swiped in transiently is drawn by the system over the page, with a
        // backdrop of its own that should stay untouched.
        val barColor = SystemBarColors.current(this)
        window.statusBarColor = if (mode.showsStatusBar) barColor else Color.TRANSPARENT
        window.navigationBarColor = if (mode.showsNavigationBar) barColor else Color.TRANSPARENT
        // Dark icons over a light bar, light ones over a dark bar — and light
        // ones over a transient bar, whose own backdrop is dark.
        val darkIcons = SystemBarColors.needsDarkIcons(this, barColor)
        controller.isAppearanceLightStatusBars = mode.showsStatusBar && darkIcons
        controller.isAppearanceLightNavigationBars = mode.showsNavigationBar && darkIcons
        padForVisibleBars(mode)
    }

    /**
     * Keeps the page clear of the bars [mode] leaves visible. Only their insets
     * are used, so full-screen mode still draws into the display cutout as
     * before, and the padding follows the bars when the device is rotated.
     */
    private fun padForVisibleBars(mode: ScreenMode) {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val bars = windowInsets.getInsets(mode.visibleBars)
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            windowInsets
        }
        binding.root.requestApplyInsets()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Regaining focus (after a dialog, the recents screen, a transient bar)
        // drops the requested visibility, so the mode is asked for again.
        if (hasFocus) applyScreenMode()
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
        private const val TAG = "MainActivity"

        private const val BLANK_URL = "about:blank"

        /** Opacity of the menu button while the user is interacting, and at rest. */
        private const val FAB_ALPHA_ACTIVE = 0.75f
        private const val FAB_ALPHA_IDLE = 0.3f
        private const val FAB_IDLE_DELAY_MS = 2_500L

        /** Hidden admin gesture: hold this corner square for this long. */
        private const val ADMIN_CORNER_DP = 72f
        private const val ADMIN_HOLD_MS = 1_500L

        /**
         * Asked for together, and either one will do: from Android 12 the user
         * may grant only the coarse permission, which still yields a position.
         * Declared in the manifest of variants built with `allowLocation` only.
         */
        private val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
