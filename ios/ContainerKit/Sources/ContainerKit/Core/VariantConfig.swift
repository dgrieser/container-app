import Foundation

/// One installable flavour of the container app, as declared in
/// `app-variants.yaml`.
///
/// This is the iOS counterpart of Android's generated `BuildConfig` fields. The
/// values are written per target by `ios/tools/generate.py` into
/// `ios/Generated/Variants/<id>/VariantConfig.swift`, so a field added here and
/// forgotten there — or the other way round — fails to compile rather than
/// reading as a wrong default at runtime.
///
/// It is a value passed in rather than a set of globals so tests can build a
/// fixture variant, and so the UI tests can be pointed at one.
public struct VariantConfig: Equatable, Sendable {

    /// Internal name: the Xcode target and scheme, and the bundle-id suffix.
    public let id: String

    /// The launcher label (`CFBundleDisplayName`).
    public let displayName: String

    public let bundleIdentifier: String

    /// The `kiosk.json` this variant reads. Empty when it ships without one,
    /// which ``defaultKioskPath`` then makes up for by being an absolute URL.
    public let defaultConfigURL: String

    /// Page this variant opens by default; see the README for the matching rules.
    public let defaultKioskPath: String

    /// When false, the admin menu opens without a PIN and none is set up.
    public let requirePin: Bool

    /// When false, no menu button is shown and the app is single-page.
    public let showMenu: Bool

    /// Where this build starts; the admin menu can change it per device.
    public let screenMode: ScreenMode

    /// Colour of the bars ``screenMode`` keeps, per system theme.
    public let barColorLight: BarColor
    public let barColorDark: BarColor

    /// Initial state of the admin's "allow unverified certificates" switch.
    public let allowUnverifiedSSL: Bool

    /// When true, a link leaving the anchored domain is handed to the system
    /// instead of being refused.
    public let allowExternalNavigation: Bool

    /// When true, the page may ask for the device's position and the target
    /// carries `NSLocationWhenInUseUsageDescription`. A variant without it
    /// carries no usage description at all, so a request can only fail.
    public let allowLocation: Bool

    /// The full version name including the variant's `versionNameSuffix`, e.g.
    /// `1.2.3-gasoline`. `CFBundleShortVersionString` cannot hold it — Apple
    /// requires one to three integers there — so it lives here and in
    /// `ContainerVersionName`.
    public let versionName: String

    public init(
        id: String,
        displayName: String,
        bundleIdentifier: String,
        defaultConfigURL: String,
        defaultKioskPath: String,
        requirePin: Bool,
        showMenu: Bool,
        screenMode: ScreenMode,
        barColorLight: BarColor,
        barColorDark: BarColor,
        allowUnverifiedSSL: Bool,
        allowExternalNavigation: Bool,
        allowLocation: Bool,
        versionName: String
    ) {
        self.id = id
        self.displayName = displayName
        self.bundleIdentifier = bundleIdentifier
        self.defaultConfigURL = defaultConfigURL
        self.defaultKioskPath = defaultKioskPath
        self.requirePin = requirePin
        self.showMenu = showMenu
        self.screenMode = screenMode
        self.barColorLight = barColorLight
        self.barColorDark = barColorDark
        self.allowUnverifiedSSL = allowUnverifiedSSL
        self.allowExternalNavigation = allowExternalNavigation
        self.allowLocation = allowLocation
        self.versionName = versionName
    }

    /// The bar colour in force for the theme the device is currently in.
    public func barColor(dark: Bool) -> BarColor {
        dark ? barColorDark : barColorLight
    }
}
