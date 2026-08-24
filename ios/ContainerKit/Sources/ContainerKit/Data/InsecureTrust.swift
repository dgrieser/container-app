import Foundation

/// The other half of "allow unverified certificates".
///
/// The Info.plist half — an ATS exception, so the connection is attempted at all
/// — is written per variant by `ios/tools/plists.py`. This is the half that
/// actually accepts the certificate, and it is the direct counterpart of
/// `InsecureSsl.kt` plus `KioskWebViewClient.onReceivedSslError`.
///
/// It is worth being blunt about the split, because getting it wrong looks like
/// the feature not working: **ATS cannot accept an invalid certificate.**
/// `NSAllowsArbitraryLoads` relaxes TLS versions and ciphers and can permit
/// cleartext, nothing more. Without the ATS exception this code is never
/// reached; without this code the exception achieves nothing.
///
/// The bypass follows the domain lock, exactly as on Android: a broken
/// certificate on an unrelated third-party host is still refused, and the app
/// says which host was blocked.
public enum InsecureTrust {

    /// How to answer a server-trust challenge.
    public enum Response: Equatable {
        /// Accept this certificate: the switch is on and the host is inside the
        /// anchored domain.
        case trust
        /// Let the system decide, which for a valid certificate means "load it".
        case performDefaultHandling
        /// Refuse, and tell the user which host it was.
        case reject(host: String?)
    }

    /// - Parameters:
    ///   - host: the host presenting the certificate.
    ///   - anchor: the URL of the app currently showing.
    ///   - allowUnverifiedSSL: the admin's switch.
    ///   - isServerTrust: whether this is a server-trust challenge at all;
    ///     anything else (a client certificate, HTTP auth) is left to the system.
    public static func decide(
        host: String?,
        anchor: String?,
        allowUnverifiedSSL: Bool,
        isServerTrust: Bool
    ) -> Response {
        guard isServerTrust else { return .performDefaultHandling }
        guard allowUnverifiedSSL else { return .performDefaultHandling }

        // The host arrives on its own here, not as a URL, so it is compared as a
        // candidate under https — the scheme the lock requires anyway.
        guard let host, !host.isEmpty else { return .reject(host: host) }
        if DomainRules.isAllowed(anchor: anchor, candidate: "https://\(host)") {
            return .trust
        }
        return .reject(host: host)
    }
}
