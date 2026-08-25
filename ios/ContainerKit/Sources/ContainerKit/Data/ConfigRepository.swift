import Foundation

/// Loads the remotely-controlled configuration from the admin-configured URL.
///
/// The port of `ConfigRepository.kt`. The last configuration that parsed is
/// cached through ``Prefs``, so the app keeps working when the network or the
/// configuration host is briefly unreachable.
public final class ConfigRepository {

    private let prefs: Prefs
    private let http: HTTPClient

    public init(prefs: Prefs, http: HTTPClient) {
        self.prefs = prefs
        self.http = http
    }

    public func load() async -> Result<KioskConfig, Error> {
        let configured = prefs.configURL
        guard !configured.isEmpty else {
            // A variant pinned to an absolute defaultKioskPath needs no
            // configuration file: there is nothing to fetch, and so nothing that
            // could be reported as having failed.
            return .success(.empty)
        }
        guard let url = URL(string: configured) else {
            return .failure(ConfigError.malformed)
        }
        do {
            let data = try await http.get(url, timeout: HTTPClient.configTimeout,
                                          accept: "application/json")
            guard let body = String(data: data, encoding: .utf8) else {
                throw ConfigError.malformed
            }
            let config = try ConfigParser.parse(body)
            prefs.saveCachedConfig(config)
            return .success(config)
        } catch {
            return .failure(error)
        }
    }

    /// The last configuration that loaded, if any.
    public func cached() -> KioskConfig? { prefs.loadCachedConfig() }
}
