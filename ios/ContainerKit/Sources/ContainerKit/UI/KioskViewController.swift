import UIKit
import WebKit

/// The whole app: one full-bleed web view, a small menu button, and the dialogs.
///
/// The port of `MainActivity`. It holds the same plain mutable state the Android
/// one does — there is no ViewModel there, and inventing one here would make the
/// two harder to compare than they need to be.
@MainActor
public final class KioskViewController: UIViewController {

    // MARK: Collaborators

    private let variant: VariantConfig
    private let prefs: Prefs
    private let pins: PinManager
    private let http: HTTPClient
    private let config: ConfigRepository
    private let icons: IconLoader
    private let location: LocationBroker

    // MARK: Views

    private lazy var webView = KioskWebViewCoordinator.makeWebView(variant: variant)
    private let progress = UIProgressView(progressViewStyle: .bar)
    private let barBackground = UIView()
    private let state = StateView()
    private lazy var banner = BannerPresenter(host: view)
    private var menuButton: MenuButton?
    private var cornerGesture: AdminCornerGesture?

    private var webViewCoordinator: KioskWebViewCoordinator!
    private var progressObservation: NSKeyValueObservation?

    // MARK: State

    private var apps: [AppEntry] = []
    private var currentApp: AppEntry?
    private var topConstraints: (fullBleed: NSLayoutConstraint, safeArea: NSLayoutConstraint)!
    private var bottomConstraints: (fullBleed: NSLayoutConstraint, safeArea: NSLayoutConstraint)!
    private var policy: ScreenModePolicy!

    public init(variant: VariantConfig) {
        self.variant = variant
        let prefs = Prefs(variant: variant)
        self.prefs = prefs
        self.pins = PinManager(store: KeychainPinStore(service: variant.bundleIdentifier))

        let http = HTTPClient(allowUnverifiedSSL: { prefs.allowUnverifiedSSL })
        self.http = http
        self.config = ConfigRepository(prefs: prefs, http: http)
        self.icons = IconLoader(http: http)
        self.location = LocationBroker(variant: variant)
        super.init(nibName: nil, bundle: nil)

        // The web view's anchor is the single source of truth for the domain lock,
        // and the app's own downloads follow it too, so the TLS bypass can never be
        // wider for an icon than it is for a page.
        http.anchorURL = { [weak self] in self?.webViewCoordinator?.anchorURL ?? nil }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not used") }

    // MARK: Lifecycle

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        webViewCoordinator = KioskWebViewCoordinator(
            variant: variant,
            allowUnverifiedSSL: { [prefs] in prefs.allowUnverifiedSSL },
            delegate: self
        )
        webView.navigationDelegate = webViewCoordinator
        webView.uiDelegate = webViewCoordinator

        layout()
        applyScreenMode()
        wireInteraction()

        // iOS asks for location once, ever, so a denial is permanent until the
        // user goes to Settings -- hence the action, which Android does not need.
        location.onAuthorizationDenied = { [weak self] in
            self?.banner.show(
                Strings.locationPermissionDenied,
                duration: .long,
                actionTitle: Strings.openSettings,
                action: {
                    guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                    UIApplication.shared.open(url)
                }
            )
        }

        Task { await start() }
    }

    /// First run: a PIN before anything else, unless the variant asks for none.
    private func start() async {
        if variant.requirePin, !pins.isPinSet {
            await PinPrompt.setUp(on: self, manager: pins, banner: banner)
        }
        await loadConfiguration()
    }

    // MARK: Layout

    private func layout() {
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)

        // The strip a kept status bar sits in. iOS has no bar background to set,
        // so the app paints its own and the page starts below it.
        barBackground.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(barBackground)

        progress.translatesAutoresizingMaskIntoConstraints = false
        progress.isHidden = true
        view.addSubview(progress)

        state.translatesAutoresizingMaskIntoConstraints = false
        state.isHidden = true
        view.addSubview(state)

        topConstraints = (
            fullBleed: webView.topAnchor.constraint(equalTo: view.topAnchor),
            safeArea: webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor)
        )
        bottomConstraints = (
            fullBleed: webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            safeArea: webView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor)
        )

        NSLayoutConstraint.activate([
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            barBackground.topAnchor.constraint(equalTo: view.topAnchor),
            barBackground.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            barBackground.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            barBackground.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),

            progress.leadingAnchor.constraint(equalTo: webView.leadingAnchor),
            progress.trailingAnchor.constraint(equalTo: webView.trailingAnchor),
            progress.topAnchor.constraint(equalTo: webView.topAnchor),

            state.topAnchor.constraint(equalTo: webView.topAnchor),
            state.bottomAnchor.constraint(equalTo: webView.bottomAnchor),
            state.leadingAnchor.constraint(equalTo: webView.leadingAnchor),
            state.trailingAnchor.constraint(equalTo: webView.trailingAnchor),
        ])

        // Pull to refresh, the way browser apps do it, on the web view's own
        // scroll view. As on Android it only fires from the top of the page, so it
        // never interferes with scrolling. A variant whose page reads a downward
        // drag itself declares `pullToRefresh: false` and gets no control at all,
        // so the gesture stays the page's; the `endRefreshing()` calls elsewhere
        // are written against the optional and simply do nothing then.
        if variant.pullToRefresh {
            let refresh = UIRefreshControl()
            refresh.addAction(
                UIAction { [weak self] _ in self?.reloadCurrentPage() },
                for: .valueChanged
            )
            webView.scrollView.refreshControl = refresh
        }

        // WKWebView reports progress on the main thread, which is where every
        // reader of it here lives.
        progressObservation = webView.observe(\.estimatedProgress, options: [.new]) {
            [weak self] webView, _ in
            guard let self else { return }
            self.progress.setProgress(Float(webView.estimatedProgress), animated: true)
            // Visible only while something is actually in flight, matching the
            // Android bar's `progress in 1..99`.
            self.progress.isHidden = !webView.isLoading
        }
    }

    private func wireInteraction() {
        if variant.showMenu {
            let button = MenuButton { [weak self] in self?.openMenu() }
            view.addSubview(button)
            NSLayoutConstraint.activate([
                button.trailingAnchor.constraint(
                    equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
                button.bottomAnchor.constraint(
                    equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16),
            ])
            button.wake()
            menuButton = button
        } else {
            // No visible entry point, so the admin menu is behind a held corner —
            // watched without consuming touches, so the page keeps working.
            cornerGesture = AdminCornerGesture(host: view) { [weak self] in
                self?.openAdmin()
            }
        }
    }

    // MARK: Screen mode

    /// Applies the current mode: which bars stay, what the strip is painted, and
    /// which edges the page is kept clear of.
    private func applyScreenMode() {
        let dark = traitCollection.userInterfaceStyle == .dark
        let color = variant.barColor(dark: dark)
        policy = ScreenModePolicy(mode: prefs.screenMode, barColor: color)

        barBackground.backgroundColor = color.uiColor
        barBackground.isHidden = !policy.respectsSafeAreaTop

        topConstraints.fullBleed.isActive = !policy.respectsSafeAreaTop
        topConstraints.safeArea.isActive = policy.respectsSafeAreaTop
        bottomConstraints.fullBleed.isActive = !policy.respectsSafeAreaBottom
        bottomConstraints.safeArea.isActive = policy.respectsSafeAreaBottom

        setNeedsStatusBarAppearanceUpdate()
        setNeedsUpdateOfHomeIndicatorAutoHidden()
        setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
    }

    public override var prefersStatusBarHidden: Bool { policy?.statusBarHidden ?? true }
    public override var preferredStatusBarStyle: UIStatusBarStyle {
        policy?.statusBarStyle ?? .default
    }
    public override var prefersHomeIndicatorAutoHidden: Bool {
        policy?.homeIndicatorAutoHidden ?? true
    }
    public override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        policy?.deferringSystemGestureEdges ?? []
    }

    public override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        // The bar colour follows the device's theme, and the page is expected to
        // follow it too — that is the pair the two YAML keys are for. Android
        // recreates the activity for this; here it is a repaint.
        if previous?.userInterfaceStyle != traitCollection.userInterfaceStyle {
            applyScreenMode()
        }
    }

    // MARK: Configuration

    private func loadConfiguration() async {
        state.hide()
        let result = await config.load()

        var loaded: KioskConfig
        switch result {
        case .success(let fetched):
            loaded = fetched
        case .failure:
            // The last configuration that worked, so a temporarily unreachable
            // server does not take the kiosk down with it.
            guard let cached = config.cached() else {
                state.show(
                    message: Strings.configLoadFailed,
                    primaryTitle: Strings.configRetry,
                    primaryAction: { [weak self] in Task { await self?.loadConfiguration() } },
                    secondaryTitle: Strings.configOpenAdmin,
                    secondaryAction: { [weak self] in self?.openAdmin() }
                )
                return
            }
            banner.show(Strings.configLoadFailed)
            loaded = cached
        }

        let selection = KioskPathResolver.resolve(
            config: loaded,
            variant: variant,
            lastSelectedURL: prefs.selectedAppURL
        )
        apps = selection.apps
        guard let target = selection.selected else {
            state.show(
                message: Strings.configEmpty,
                primaryTitle: Strings.configRetry,
                primaryAction: { [weak self] in Task { await self?.loadConfiguration() } },
                secondaryTitle: Strings.configOpenAdmin,
                secondaryAction: { [weak self] in self?.openAdmin() }
            )
            return
        }
        select(target)
    }

    private func select(_ app: AppEntry) {
        currentApp = app
        prefs.selectedAppURL = app.url
        // Re-anchoring the lock before the load, so the very first navigation is
        // already judged against the new app.
        webViewCoordinator.anchorURL = app.url
        state.hide()
        guard let url = URL(string: app.url) else { return }
        webView.load(URLRequest(url: url))
    }

    /// Pull-to-refresh, and the admin menu's Reload.
    private func reloadCurrentPage() {
        if webView.url != nil {
            webView.reload()
        } else if let app = currentApp {
            // The first load failed, so there is nothing to reload: start over.
            select(app)
        } else {
            webView.scrollView.refreshControl?.endRefreshing()
            Task { await loadConfiguration() }
        }
    }

    // MARK: Menu and admin

    private func openMenu() {
        menuButton?.wake()
        let sheet = MenuSheetViewController(
            apps: apps,
            currentURL: currentApp?.url,
            icons: icons,
            onSelect: { [weak self] app in self?.select(app) },
            onAdmin: { [weak self] in self?.openAdmin() }
        )
        let host = UINavigationController(rootViewController: sheet)
        host.sheetPresentationController?.detents = [.medium(), .large()]
        host.sheetPresentationController?.prefersGrabberVisible = true
        present(host, animated: true)
    }

    private func openAdmin() {
        Task {
            // A variant with requirePin: false opens the menu straight away, as on
            // Android — there is no PIN to ask for.
            if variant.requirePin {
                guard await PinPrompt.unlock(on: self, manager: pins, banner: banner) else {
                    return
                }
            }
            presentAdmin()
        }
    }

    private func presentAdmin() {
        let admin = AdminViewController(
            variant: variant,
            prefs: prefs,
            onSave: { [weak self] settings in self?.apply(settings) },
            onReload: { [weak self] in
                self?.banner.show(Strings.reloading)
                Task { await self?.loadConfiguration() }
            },
            onChangePin: { [weak self] in
                guard let self else { return }
                Task { await PinPrompt.change(on: self.presentedViewController ?? self,
                                              manager: self.pins, banner: self.banner) }
            }
        )
        present(UINavigationController(rootViewController: admin), animated: true)
    }

    private func apply(_ settings: AdminViewController.Settings) {
        prefs.configURL = settings.configURL
        prefs.screenMode = settings.screenMode
        prefs.allowUnverifiedSSL = settings.allowUnverifiedSSL
        applyScreenMode()

        if settings.tlsChanged {
            // Android clears the WebView's remembered per-host decisions here.
            // WKWebView keeps none, so what is left is to make sure nothing
            // fetched under the old setting is reused.
            http.reset()
            clearWebContentCaches()
        }
        banner.show(Strings.adminSaved)
        Task { await loadConfiguration() }
    }

    private func clearWebContentCaches() {
        let types: Set<String> = [
            WKWebsiteDataTypeDiskCache,
            WKWebsiteDataTypeMemoryCache,
            WKWebsiteDataTypeOfflineWebApplicationCache,
        ]
        WKWebsiteDataStore.default().removeData(
            ofTypes: types,
            modifiedSince: .distantPast
        ) {}
    }
}

// MARK: - Web view

extension KioskViewController: KioskWebViewDelegate {

    public func webViewBlockedNavigation(to url: String) {
        let domain = DomainRules.host(of: currentApp?.url) ?? ""
        banner.show(Strings.navigationBlocked(domain: domain))
    }

    public func webViewHandOffExternally(_ url: String) -> Bool {
        guard let target = URL(string: url) else { return false }
        // `UIApplication.open` cannot filter by category the way Android's
        // CATEGORY_BROWSABLE does, and it reports failure only asynchronously.
        // `canOpenURL` for http(s) is the closest thing to asking first, and the
        // completion handler covers the rest.
        guard UIApplication.shared.canOpenURL(target) else { return false }
        UIApplication.shared.open(target, options: [:]) { [weak self] opened in
            guard !opened else { return }
            self?.webViewBlockedNavigation(to: url)
        }
        return true
    }

    public func webViewRefusedCertificate(host: String?) {
        banner.show(
            Strings.sslBlocked(host: host ?? Strings.sslBlockedHost),
            duration: .long
        )
    }

    public func webViewFailedToLoad(error: Error) {
        banner.show(Strings.pageFailed)
    }

    public func webViewDidStartLoading() {
        progress.isHidden = false
        location.resetPerPageWarnings()
    }

    public func webViewDidFinishLoading() {
        progress.isHidden = true
        webView.scrollView.refreshControl?.endRefreshing()
    }

    public func webViewContentProcessDidTerminate() {
        // A blank page for ever otherwise: the web content process died and the
        // view will not recover on its own. Android's WebView has no counterpart.
        banner.show(Strings.webContentCrashed)
        if let app = currentApp { select(app) }
    }
}

// MARK: - Touches

extension KioskViewController {

    /// Any interaction brings the menu button back to full strength, which is what
    /// `onUserInteraction` does on Android.
    public override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesBegan(touches, with: event)
        menuButton?.wake()
    }
}

private extension BarColor {
    var uiColor: UIColor {
        UIColor(red: red, green: green, blue: blue, alpha: alpha)
    }
}
