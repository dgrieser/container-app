import Foundation

/// Which page a variant opens, and what the menu offers.
///
/// This is `MainActivity.defaultApp` and the interesting half of
/// `MainActivity.render`, lifted out so the rules are testable without a web
/// view. They are fiddly, they matter (a wrong answer shows the wrong page on
/// launch), and they have to match Android exactly.
public enum KioskPathResolver {

    /// The page the app should open, and the list the menu should show.
    public struct Selection: Equatable {
        /// Everything the menu offers, which may be one entry longer than the
        /// configuration: see ``resolve(config:variant:lastSelectedURL:)``.
        public let apps: [AppEntry]
        /// The app to load now, or nil when there is nothing to show at all.
        public let selected: AppEntry?
    }

    /// Resolves this variant's `defaultKioskPath` against the loaded config.
    ///
    /// In order: an absolute http(s) URL — matched against the configured apps
    /// and otherwise honoured as-is, so a variant can be pinned to a page the
    /// shared `kiosk.json` does not even list; then a path such as `/dashboard`,
    /// matched against each configured app's path, exact before prefix; then the
    /// `name` of one of the configured apps.
    public static func defaultApp(
        in config: KioskConfig,
        variant: VariantConfig
    ) -> AppEntry? {
        let wanted = variant.defaultKioskPath.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !wanted.isEmpty else { return nil }

        if DomainRules.isHTTP(wanted) {
            return config.apps.first { $0.url == wanted }
                ?? config.apps.first { $0.url.hasPrefix(wanted) }
                // Not in the list, and that is allowed: this is how a variant
                // with no configuration file at all still has a page.
                ?? AppEntry(name: variant.displayName, iconURL: nil, url: wanted)
        }

        let path = wanted.hasPrefix("/") ? wanted : "/\(wanted)"
        return config.apps.first { self.path(of: $0.url) == path }
            ?? config.apps.first { self.path(of: $0.url).hasPrefix(path) }
            ?? config.apps.first { $0.name.caseInsensitiveCompare(wanted) == .orderedSame }
    }

    /// What to show once a configuration has been loaded (or recovered from cache).
    ///
    /// - Parameter lastSelectedURL: the app the user was last on, which is
    ///   honoured only where they could have chosen it.
    public static func resolve(
        config: KioskConfig,
        variant: VariantConfig,
        lastSelectedURL: String?
    ) -> Selection {
        guard let fallback = defaultApp(in: config, variant: variant) ?? config.apps.first else {
            return Selection(apps: config.apps, selected: nil)
        }

        // A page pinned by defaultKioskPath is not necessarily one of the listed
        // apps — and for a variant without a configuration file there is no list
        // at all — so it goes into the menu. Otherwise switching away from it
        // would be a one-way trip.
        var apps = config.apps
        if !apps.contains(where: { $0.url == fallback.url }) {
            apps.append(fallback)
        }

        // With the menu hidden the user cannot switch anyway, so such a variant
        // always opens its configured page rather than the last selection.
        guard variant.showMenu else {
            return Selection(apps: apps, selected: fallback)
        }
        let previous = apps.first { $0.url == lastSelectedURL }
        return Selection(apps: apps, selected: previous ?? fallback)
    }

    /// The path component of a URL, with an empty path counting as `/`.
    private static func path(of url: String) -> String {
        let path = URLComponents(string: url)?.path ?? ""
        return path.isEmpty ? "/" : path
    }
}
