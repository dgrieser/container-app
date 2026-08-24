import Foundation

/// A single web app the container is allowed to display.
public struct AppEntry: Equatable, Sendable {

    /// Human-readable label shown in the menu.
    public let name: String

    /// Absolute https URL of the app icon, if the configuration gave one.
    public let iconURL: String?

    /// Absolute https URL of the page. Navigation is locked to this URL's
    /// registered domain while it is showing.
    public let url: String

    public init(name: String, iconURL: String?, url: String) {
        self.name = name
        self.iconURL = iconURL
        self.url = url
    }
}

/// The full remotely-controlled configuration.
public struct KioskConfig: Equatable, Sendable {

    public let apps: [AppEntry]

    public init(apps: [AppEntry]) {
        self.apps = apps
    }

    public var isEmpty: Bool { apps.isEmpty }

    public static let empty = KioskConfig(apps: [])
}
