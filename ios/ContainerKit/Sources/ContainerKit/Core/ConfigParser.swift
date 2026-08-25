import Foundation

/// Turns the body of a `kiosk.json` into apps, or into nothing.
///
/// Split out from the fetch so it can be tested without a network: the parsing
/// is where the surprises are, and it has to accept exactly what the Android
/// build accepts, down to the coercions `org.json` performs silently.
///
/// The shape is
/// ```json
/// { "apps": [ { "name": "Portal", "icon": "https://…", "url": "https://…" } ] }
/// ```
/// and a bare top-level array is also accepted, as it is on Android.
public enum ConfigParser {

    /// Parses a configuration body. Throws only when the document is not JSON at
    /// all; anything unusable *inside* it is skipped, so one malformed entry on
    /// the server does not take the whole kiosk down.
    public static func parse(_ body: String) throws -> KioskConfig {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = trimmed.data(using: .utf8) else {
            throw ConfigError.malformed
        }
        let document = try JSONSerialization.jsonObject(with: data, options: [])

        let raw: [Any]
        if let array = document as? [Any] {
            raw = array
        } else if let object = document as? [String: Any] {
            raw = object["apps"] as? [Any] ?? []
        } else {
            throw ConfigError.malformed
        }

        return KioskConfig(apps: raw.compactMap(entry(from:)))
    }

    private static func entry(from raw: Any) -> AppEntry? {
        guard let object = raw as? [String: Any] else { return nil }

        let url = string(object["url"])
        // An entry without a usable page is not an app. Non-http URLs are
        // dropped rather than shown and then refused by the domain lock.
        guard DomainRules.isHTTP(url) else { return nil }

        // A missing name falls back to the host, so the menu never shows a blank
        // row — the same fallback the Android parser makes.
        let name = string(object["name"]).isEmpty
            ? (DomainRules.host(of: url) ?? url)
            : string(object["name"])

        let icon = string(object["icon"])
        // `"null"` really does turn up: it is what a JSON null becomes once it
        // has been through `optString`, so a config written against the Android
        // app can contain it.
        let iconURL = (icon.isEmpty || icon == "null") ? nil : icon

        return AppEntry(name: name, iconURL: iconURL, url: url)
    }

    /// Reads a value the way `org.json.optString` does: numbers and booleans
    /// become their text, a missing value becomes empty, and the result is
    /// trimmed.
    private static func string(_ value: Any?) -> String {
        switch value {
        case let text as String: return text.trimmingCharacters(in: .whitespacesAndNewlines)
        case let number as NSNumber: return number.stringValue
        default: return ""
        }
    }
}

public enum ConfigError: Error, Equatable {
    /// The body is not JSON, or not a shape that could hold apps.
    case malformed
    /// The server answered, but not with success.
    case http(Int)
    /// The request never completed.
    case transport(String)
}
