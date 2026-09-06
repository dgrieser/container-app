import Foundation

@testable import ContainerKit

/// A variant to test against, with every switch reachable.
///
/// `VariantConfig` is a value passed in rather than a set of globals precisely so
/// this can exist: the real ones are generated per target, and a test that had to
/// use them could only ever exercise the three this repository happens to ship.
enum Fixtures {

    static func variant(
        id: String = "test",
        displayName: String = "Test",
        bundleIdentifier: String = "com.example.container.test",
        defaultConfigURL: String = "https://example.com/kiosk.json",
        defaultKioskPath: String = "",
        requirePin: Bool = true,
        showMenu: Bool = true,
        pullToRefresh: Bool = true,
        screenMode: ScreenMode = .fullscreen,
        barColorLight: BarColor = .windowBackground,
        barColorDark: BarColor = .windowBackground,
        allowUnverifiedSSL: Bool = false,
        allowExternalNavigation: Bool = false,
        allowLocation: Bool = false,
        versionName: String = "1.2.3"
    ) -> VariantConfig {
        VariantConfig(
            id: id,
            displayName: displayName,
            bundleIdentifier: bundleIdentifier,
            defaultConfigURL: defaultConfigURL,
            defaultKioskPath: defaultKioskPath,
            requirePin: requirePin,
            showMenu: showMenu,
            pullToRefresh: pullToRefresh,
            screenMode: screenMode,
            barColorLight: barColorLight,
            barColorDark: barColorDark,
            allowUnverifiedSSL: allowUnverifiedSSL,
            allowExternalNavigation: allowExternalNavigation,
            allowLocation: allowLocation,
            versionName: versionName
        )
    }

    static func app(_ url: String, name: String = "", icon: String? = nil) -> AppEntry {
        AppEntry(name: name.isEmpty ? url : name, iconURL: icon, url: url)
    }
}
