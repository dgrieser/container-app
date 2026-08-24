import Foundation

/// The app's own HTTP: the configuration fetch and the menu icons.
///
/// Dependency-free on purpose, mirroring the Android side's hand-rolled
/// `HttpURLConnection` calls — there is no Retrofit there and no Alamofire here.
/// Its one piece of real behaviour is the trust challenge, which routes through
/// ``InsecureTrust`` so the app's own downloads follow exactly the same rule as
/// the web view.
public final class HTTPClient: NSObject {

    /// 15 s, as on Android. Long enough for a slow internal server, short enough
    /// that a dead one does not leave the app on a blank page.
    public static let configTimeout: TimeInterval = 15

    /// Icons are decoration: a slow one should not hold up the menu.
    public static let iconTimeout: TimeInterval = 10

    /// Reads the switch when a challenge arrives rather than at construction, so
    /// toggling it in the admin menu takes effect on the next request.
    private let allowUnverifiedSSL: () -> Bool

    /// The URL anchoring the domain lock. Set after construction, because the
    /// object that knows it is the one that owns this client.
    public var anchorURL: () -> String? = { nil }

    private lazy var session: URLSession = URLSession(
        configuration: .ephemeral,
        delegate: self,
        delegateQueue: nil
    )

    public init(allowUnverifiedSSL: @escaping () -> Bool) {
        self.allowUnverifiedSSL = allowUnverifiedSSL
        super.init()
    }

    /// GETs `url`, or throws. Non-2xx is a failure, as it is on Android.
    public func get(
        _ url: URL,
        timeout: TimeInterval,
        accept: String? = nil
    ) async throws -> Data {
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = "GET"
        request.cachePolicy = .reloadIgnoringLocalCacheData
        if let accept { request.setValue(accept, forHTTPHeaderField: "Accept") }
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw ConfigError.transport(error.localizedDescription)
        }
        if let http = response as? HTTPURLResponse, !(200 ... 299).contains(http.statusCode) {
            throw ConfigError.http(http.statusCode)
        }
        return data
    }

    /// Drops every connection and cached response.
    ///
    /// `clearSslPreferences()` on Android exists because its WebView remembers a
    /// per-host "proceed" decision; nothing here does, so this is only hygiene —
    /// it stops a response fetched under the old setting from being reused.
    public func reset() {
        session.invalidateAndCancel()
        session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)
    }
}

extension HTTPClient: URLSessionDelegate {

    public func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let isServerTrust =
            challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust
        let decision = InsecureTrust.decide(
            host: challenge.protectionSpace.host,
            anchor: anchorURL(),
            allowUnverifiedSSL: allowUnverifiedSSL(),
            isServerTrust: isServerTrust
        )
        switch decision {
        case .trust:
            guard let trust = challenge.protectionSpace.serverTrust else {
                completionHandler(.performDefaultHandling, nil)
                return
            }
            completionHandler(.useCredential, URLCredential(trust: trust))
        case .performDefaultHandling:
            completionHandler(.performDefaultHandling, nil)
        case .reject:
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}
