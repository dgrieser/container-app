import Foundation

/// Central place for the "cannot navigate away to another domain" rule.
///
/// A candidate URL is allowed only when it uses http/https AND its host belongs
/// to the same registered domain as the currently selected app. Subdomains in
/// either direction are permitted (an app on `portal.example.com` may link to
/// `example.com` and `cdn.example.com`), but a jump to `other.com` is not.
///
/// A direct port of `DomainRules.kt`, including the part its own comment is
/// careful about: this is a suffix match, not a public-suffix (eTLD+1) list, so
/// `example.co.uk` and `other.co.uk` are *not* related but `co.uk` would be
/// treated as the parent of both. The behaviour is deliberately identical on the
/// two platforms; changing it is a change to both.
public enum DomainRules {

    /// The host a URL is anchored to: lowercased, trimmed, and without `www.`.
    ///
    /// Uses `URLComponents` rather than `URL`, because `URL(string:)` became
    /// RFC 3986-strict in iOS 17 and started rejecting URLs that Android's
    /// `Uri.parse` accepts — which would silently tighten the lock into refusing
    /// pages that used to load.
    public static func host(of url: String?) -> String? {
        guard let url, !url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        guard let host = URLComponents(string: url)?.host?.lowercased()
            .trimmingCharacters(in: .whitespacesAndNewlines), !host.isEmpty
        else {
            return nil
        }
        let bare = host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
        return bare.isEmpty ? nil : bare
    }

    /// Whether a URL is one the container would ever load: http or https only.
    ///
    /// Everything else — `mailto:`, `tel:`, a custom app scheme — is refused
    /// whatever the domain lock says, so a page cannot use a link to reach
    /// another app.
    public static func isHTTP(_ url: String?) -> Bool {
        guard let url, let scheme = URLComponents(string: url)?.scheme?.lowercased() else {
            return false
        }
        return scheme == "http" || scheme == "https"
    }

    /// True when `candidate` may be loaded while the app is anchored to `appURL`.
    public static func isAllowed(anchor appURL: String?, candidate candidateURL: String?) -> Bool {
        guard isHTTP(candidateURL),
              let appHost = host(of: appURL),
              let candidateHost = host(of: candidateURL)
        else {
            return false
        }
        return candidateHost == appHost
            || candidateHost.hasSuffix(".\(appHost)")
            || appHost.hasSuffix(".\(candidateHost)")
    }
}
