package de.davidgrieser.container

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        pinManager = PinManager(prefs)
        repository = ConfigRepository(prefs)

        enableImmersiveMode()
        setupWebView()

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

        bootstrap()
    }

    // --- Bootstrapping -----------------------------------------------------

    private fun bootstrap() {
        if (!pinManager.isPinSet) {
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
        if (loaded.isEmpty) {
            showState(
                getString(R.string.config_empty),
                primary = getString(R.string.config_retry),
                secondary = getString(R.string.config_open_admin)
            )
            return
        }
        val previous = prefs.selectedAppUrl
        val target = loaded.apps.firstOrNull { it.url == previous } ?: loaded.apps.first()
        selectApp(target)
    }

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
            onPageFinished = { binding.progress.isVisible = false }
        )
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
        if (!pinManager.isPinSet) {
            promptSetPin(mandatory = true) { showAdminDialog() }
            return
        }
        promptEnterPin { showAdminDialog() }
    }

    private fun showAdminDialog() {
        val view = DialogAdminBinding.inflate(layoutInflater)
        view.configUrlInput.setText(prefs.configUrl)
        view.allowUnverifiedSslSwitch.isChecked = prefs.allowUnverifiedSsl

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
     * Returns false (and shows an error) if the URL is invalid.
     */
    private fun saveAdminSettings(view: DialogAdminBinding): Boolean {
        val url = view.configUrlInput.text?.toString()?.trim().orEmpty()
        if (!DomainRules.isHttp(url)) {
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
        binding.webView.isVisible = false
        binding.stateContainer.isVisible = true
        binding.stateMessage.text = message
        binding.statePrimaryButton.isVisible = primary != null
        primary?.let { binding.statePrimaryButton.text = it }
        binding.stateSecondaryButton.isVisible = secondary != null
        secondary?.let { binding.stateSecondaryButton.text = it }
    }

    private fun hideState() {
        binding.stateContainer.isVisible = false
        binding.webView.isVisible = true
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
        binding.webView.destroy()
        super.onDestroy()
    }
}
