import Foundation

/// Everything the app remembers: the configuration URL, the screen mode, the TLS
/// switch, the last-known-good configuration and the currently selected app.
///
/// The port of `Prefs.kt`, with two differences. The PIN is not here — it is in
/// the keychain, behind ``PinStore``. And every getter falls back to the
/// variant's own value rather than to a type default, which matters most for
/// ``allowUnverifiedSSL``: reading it as a plain `bool(forKey:)` would answer
/// `false` for "never set", quietly disabling the switch on a variant that ships
/// with it on.
public final class Prefs {

    private enum Key: String {
        case configURL = "config_url"
        case allowUnverifiedSSL = "allow_unverified_ssl"
        case screenMode = "screen_mode"
        case selectedAppURL = "selected_app_url"
        case cachedConfig = "cached_config"
    }

    private let defaults: UserDefaults
    private let variant: VariantConfig

    public init(defaults: UserDefaults = .standard, variant: VariantConfig) {
        self.defaults = defaults
        self.variant = variant
    }

    // MARK: Configuration URL

    /// Which `kiosk.json` to read.
    ///
    /// Falls back to the variant's built-in URL, so clearing the admin field
    /// restores the default. Empty when the variant has no configuration file at
    /// all — such a build only ever shows its `defaultKioskPath`.
    public var configURL: String {
        get {
            let stored = defaults.string(forKey: Key.configURL.rawValue)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if let stored, !stored.isEmpty { return stored }
            return variant.defaultConfigURL
        }
        set {
            defaults.set(
                newValue.trimmingCharacters(in: .whitespacesAndNewlines),
                forKey: Key.configURL.rawValue
            )
        }
    }

    // MARK: TLS

    /// When true, pages and the app's own downloads are loaded even if the
    /// server's certificate cannot be verified.
    public var allowUnverifiedSSL: Bool {
        get {
            // `object(forKey:)` first: an absent value has to mean "the variant
            // decides", not `false`.
            guard let stored = defaults.object(forKey: Key.allowUnverifiedSSL.rawValue) as? Bool
            else {
                return variant.allowUnverifiedSSL
            }
            return stored
        }
        set { defaults.set(newValue, forKey: Key.allowUnverifiedSSL.rawValue) }
    }

    // MARK: Screen mode

    /// Which system UI stays over the page. Starts at the variant's `screenMode`
    /// and then stays whatever the admin last picked.
    public var screenMode: ScreenMode {
        get {
            ScreenMode(
                id: defaults.string(forKey: Key.screenMode.rawValue) ?? variant.screenMode.id
            )
        }
        set { defaults.set(newValue.id, forKey: Key.screenMode.rawValue) }
    }

    // MARK: Selected app

    public var selectedAppURL: String? {
        get { defaults.string(forKey: Key.selectedAppURL.rawValue) }
        set { defaults.set(newValue, forKey: Key.selectedAppURL.rawValue) }
    }

    // MARK: Cached configuration

    /// The last configuration that loaded, so the app still works when the
    /// server is briefly unreachable.
    public func saveCachedConfig(_ config: KioskConfig) {
        let apps: [[String: Any]] = config.apps.map { app in
            var entry: [String: Any] = ["name": app.name, "url": app.url]
            if let icon = app.iconURL { entry["icon"] = icon }
            return entry
        }
        guard let data = try? JSONSerialization.data(withJSONObject: ["apps": apps]) else {
            return
        }
        defaults.set(String(data: data, encoding: .utf8), forKey: Key.cachedConfig.rawValue)
    }

    /// Reads the cache back through the same parser a server answer goes
    /// through, so a cached entry can never be looser than a fresh one — a stored
    /// non-http URL would only be refused by the domain lock a moment later.
    public func loadCachedConfig() -> KioskConfig? {
        guard let raw = defaults.string(forKey: Key.cachedConfig.rawValue),
              let config = try? ConfigParser.parse(raw)
        else {
            return nil
        }
        return config
    }
}
