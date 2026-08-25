import UIKit
import WebKit

/// Everything the container asks of its web view, and what it reports back.
@MainActor
public protocol KioskWebViewDelegate: AnyObject {
    /// An off-domain navigation was refused. The message names the anchored domain.
    func webViewBlockedNavigation(to url: String)
    /// An off-domain link is to be opened outside the container.
    /// - Returns: false when nothing on the device could open it, which leaves the
    ///   navigation blocked, exactly as it would be without the setting.
    func webViewHandOffExternally(_ url: String) -> Bool
    /// A certificate was refused. `host` is nil when the challenge did not say.
    func webViewRefusedCertificate(host: String?)
    /// A page load failed for a reason worth telling the user about.
    func webViewFailedToLoad(error: Error)
    func webViewDidStartLoading()
    func webViewDidFinishLoading()
    /// The web content process died — a failure mode Android's WebView has no
    /// counterpart for, and which leaves a permanently blank page if ignored.
    func webViewContentProcessDidTerminate()
}

/// Enforces the domain lock, and decides what a TLS error means.
///
/// The port of `KioskWebViewClient.kt`. `shouldOverrideUrlLoading` becomes
/// `decidePolicyFor(navigationAction:)`, with `decidePolicyFor(navigationResponse:)`
/// as a second layer — Android's single interception point covers server-side
/// redirects, and on iOS the action callback does not always fire for them.
///
/// Two cases need naming, because they differ from Android:
///
/// * **`target="_blank"` inside the domain.** With multiple windows disabled the
///   Android WebView routes these through the same callback and loads them in
///   place. On iOS `targetFrame` is nil and returning `.allow` does nothing at
///   all, so the load has to be re-issued by hand or the tap silently fails.
/// * **Off-domain sub-frames.** These are blocked, as on Android, but they are
///   deliberately *not* handed to Safari even in a variant that opts into
///   external links: being thrown out of the app because an advert iframe loaded
///   would be far worse than the Android behaviour, which merely refuses it.
@MainActor
public final class KioskWebViewCoordinator: NSObject {

    /// The URL that anchors the domain lock; updated whenever the user switches app.
    public var anchorURL: String?

    private let variant: VariantConfig
    private let allowUnverifiedSSL: () -> Bool
    private weak var delegate: KioskWebViewDelegate?

    public init(
        variant: VariantConfig,
        allowUnverifiedSSL: @escaping () -> Bool,
        delegate: KioskWebViewDelegate
    ) {
        self.variant = variant
        self.allowUnverifiedSSL = allowUnverifiedSSL
        self.delegate = delegate
        super.init()
    }

    /// Errors the challenge handler has already reported, so they are not
    /// announced twice.
    ///
    /// Listed rather than expressed as a range: these constants run *downwards*
    /// from -1200, so the obvious range literal would have its bounds the wrong
    /// way round.
    static let trustFailures: Set<Int> = [
        NSURLErrorSecureConnectionFailed,
        NSURLErrorServerCertificateHasBadDate,
        NSURLErrorServerCertificateUntrusted,
        NSURLErrorServerCertificateHasUnknownRoot,
        NSURLErrorServerCertificateNotYetValid,
        NSURLErrorClientCertificateRejected,
        NSURLErrorClientCertificateRequired,
    ]

    /// The web view the container runs, configured to match the Android one.
    public static func makeWebView(variant: VariantConfig) -> WKWebView {
        let configuration = WKWebViewConfiguration()

        // `setSupportMultipleWindows(false)` and
        // `javaScriptCanOpenWindowsAutomatically = false`: a new window would
        // escape the lock, so there are none. Links that wanted one arrive at the
        // navigation delegate with a nil target frame instead.
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false

        // Android sets `mediaPlaybackRequiresUserGesture = true`.
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        configuration.allowsInlineMediaPlayback = true

        // No persistent data store beyond what the page needs: the container is
        // not a browser, and nothing should outlive an app it was locked to.
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true

        let webView = WKWebView(frame: .zero, configuration: configuration)

        // `setSupportZoom(false)`: pinch is off, and the page's own viewport
        // decides the rest. Double-tap zoom can survive this on some pages; iOS
        // offers no switch for it.
        webView.scrollView.pinchGestureRecognizer?.isEnabled = false
        webView.scrollView.bouncesZoom = false

        // iOS has no Back button. The edge swipe is the only way back through
        // history, and it stays inside the anchored domain because everything in
        // the history already passed the lock.
        webView.allowsBackForwardNavigationGestures = true
        webView.allowsLinkPreview = false

        // The page owns the whole display in full-screen mode, cutout included.
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.isOpaque = false
        return webView
    }
}

// MARK: - Navigation

extension KioskWebViewCoordinator: WKNavigationDelegate {

    public func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        let candidate = navigationAction.request.url?.absoluteString
        let decision = NavigationPolicy.decide(
            anchor: anchorURL,
            candidate: candidate,
            allowExternalNavigation: variant.allowExternalNavigation
        )

        switch decision {
        case .allow:
            // A link that asked for a new window: nothing would happen if this
            // simply allowed it, because there is no window to open it in.
            if navigationAction.targetFrame == nil, let candidate,
               let url = URL(string: candidate) {
                decisionHandler(.cancel)
                webView.load(URLRequest(url: url))
                return
            }
            decisionHandler(.allow)

        case .handOffExternally:
            decisionHandler(.cancel)
            // A sub-frame is never sent out of the app; see the class comment.
            guard navigationAction.targetFrame == nil
                || navigationAction.targetFrame?.isMainFrame == true
            else {
                candidate.map { delegate?.webViewBlockedNavigation(to: $0) }
                return
            }
            if let candidate, delegate?.webViewHandOffExternally(candidate) != true {
                delegate?.webViewBlockedNavigation(to: candidate)
            }

        case .blocked:
            decisionHandler(.cancel)
            candidate.map { delegate?.webViewBlockedNavigation(to: $0) }
        }
    }

    /// The second layer of the lock, for a redirect the action callback did not see.
    public func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationResponse: WKNavigationResponse,
        decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void
    ) {
        let candidate = navigationResponse.response.url?.absoluteString
        guard navigationResponse.isForMainFrame else {
            decisionHandler(.allow)
            return
        }
        guard DomainRules.isAllowed(anchor: anchorURL, candidate: candidate) else {
            decisionHandler(.cancel)
            candidate.map { delegate?.webViewBlockedNavigation(to: $0) }
            return
        }
        // WKWebView will happily render a PDF or start a download where the
        // Android WebView did neither. The container shows pages; anything it
        // cannot display is refused rather than opening a viewer the user cannot
        // get out of.
        guard navigationResponse.canShowMIMEType else {
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    public func webView(
        _ webView: WKWebView,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let isServerTrust =
            challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust
        switch InsecureTrust.decide(
            host: challenge.protectionSpace.host,
            anchor: anchorURL,
            allowUnverifiedSSL: allowUnverifiedSSL(),
            isServerTrust: isServerTrust
        ) {
        case .trust:
            guard let trust = challenge.protectionSpace.serverTrust else {
                completionHandler(.performDefaultHandling, nil)
                return
            }
            completionHandler(.useCredential, URLCredential(trust: trust))
        case .performDefaultHandling:
            completionHandler(.performDefaultHandling, nil)
        case .reject(let host):
            completionHandler(.cancelAuthenticationChallenge, nil)
            delegate?.webViewRefusedCertificate(host: host)
        }
    }

    public func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        delegate?.webViewDidStartLoading()
    }

    public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        delegate?.webViewDidFinishLoading()
    }

    public func webView(
        _ webView: WKWebView,
        didFailProvisionalNavigation navigation: WKNavigation!,
        withError error: Error
    ) {
        delegate?.webViewDidFinishLoading()

        // There is no "I cancelled a TLS error, here is the URL" callback the way
        // `onReceivedSslError` gives one, so a refused certificate arrives here as
        // a plain error code. Those are reported by the challenge handler already,
        // and a cancelled navigation is not a failure at all.
        let code = (error as NSError).code
        guard !KioskWebViewCoordinator.trustFailures.contains(code),
              code != NSURLErrorCancelled
        else {
            return
        }
        delegate?.webViewFailedToLoad(error: error)
    }

    public func webView(
        _ webView: WKWebView,
        didFail navigation: WKNavigation!,
        withError error: Error
    ) {
        delegate?.webViewDidFinishLoading()
        guard (error as NSError).code != NSURLErrorCancelled else { return }
        delegate?.webViewFailedToLoad(error: error)
    }

    public func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        delegate?.webViewContentProcessDidTerminate()
    }
}

// MARK: - UI

extension KioskWebViewCoordinator: WKUIDelegate {

    /// A page asking for a new window gets none; the navigation delegate above
    /// has already re-issued the load in place where the lock allows it.
    public func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        nil
    }
}
