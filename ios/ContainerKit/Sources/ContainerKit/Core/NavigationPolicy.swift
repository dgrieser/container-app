import Foundation

/// What to do with a navigation the web view is about to make.
///
/// The decision is a pure function of the anchor, the candidate URL and the
/// variant's `allowExternalNavigation`, and it is separate from the delegate
/// that asks for it — not for tidiness, but because `WKNavigationAction` has no
/// usable initialiser and cannot be constructed in a test. Keeping the rule here
/// is what makes the domain lock testable at all.
///
/// This is `KioskWebViewClient.shouldOverrideUrlLoading`, unchanged in substance.
public enum NavigationPolicy {

    public enum Decision: Equatable {
        /// Inside the anchored domain: let the web view load it.
        case allow
        /// Off-domain, and the variant opts into sending links out: hand it to
        /// the system, which is normally the browser.
        case handOffExternally
        /// Off-domain and refused. The user is told which domain the app is
        /// locked to.
        case blocked
    }

    /// - Parameters:
    ///   - anchor: the URL of the app currently showing, which the lock follows.
    ///   - candidate: where the page wants to go.
    ///   - allowExternalNavigation: the variant's build-time choice.
    public static func decide(
        anchor: String?,
        candidate: String?,
        allowExternalNavigation: Bool
    ) -> Decision {
        if DomainRules.isAllowed(anchor: anchor, candidate: candidate) {
            return .allow
        }
        // Only http(s) is ever handed over, so a page cannot use the setting to
        // reach arbitrary other apps through a custom scheme. Anything else is
        // refused exactly as it would be without the setting.
        if allowExternalNavigation, DomainRules.isHTTP(candidate) {
            return .handOffExternally
        }
        return .blocked
    }
}
