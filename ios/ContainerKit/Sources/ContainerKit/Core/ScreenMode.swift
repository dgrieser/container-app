import Foundation

/// How much of the system's own UI stays on screen over the page.
///
/// A build starts out in the mode its variant declares (`screenMode` in
/// `app-variants.yaml`) and the admin menu can change it per device, so one
/// kiosk can run edge to edge while another keeps the clock in view.
///
/// The four names are shared with Android, where they mean what they say: the
/// status bar and the navigation bar, kept or hidden independently. iOS has no
/// navigation bar, and its home indicator can be dimmed but never removed, so
/// the two names that mention it are reinterpreted around the home indicator
/// instead — see ``showsHomeIndicator``. Keeping the names rather than inventing
/// iOS-only ones is deliberate: one `app-variants.yaml` describes both apps, and
/// a variant that reads well on a wall reads well on a wall on either platform.
///
/// Every `rawValue` here also has to be listed in `ScreenModes` in
/// `android/buildSrc`, which is what lets the build reject a misspelled mode.
/// `ios/tools/tests/test_swift_contract.py` compares the two lists.
public enum ScreenMode: String, CaseIterable, Sendable {

    /// Neither bar. The page owns the whole display, cutout included.
    case fullscreen

    /// The status bar stays: clock, battery, notifications.
    case statusBar

    /// On Android the navigation bar stays. On iOS there is none, so this keeps
    /// the home indicator at full strength while the status bar stays hidden —
    /// the mode for a page that is scrolled and tapped but not read for long.
    case navigationBar

    /// Both, with the page between them, like an ordinary app.
    case systemBars

    /// What a variant gets when it declares no `screenMode`: no bars at all.
    public static let `default` = ScreenMode.fullscreen

    /// Resolves a stored or built-in mode name.
    ///
    /// Anything unknown — an older preference, a build that predates a renamed
    /// mode — falls back to ``default`` rather than leaving the app without a
    /// mode, exactly as the Android enum does.
    public init(id: String?) {
        let wanted = (id ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        self = ScreenMode.allCases.first { $0.rawValue.lowercased() == wanted } ?? .default
    }

    /// The name used in `app-variants.yaml` and for the persisted preference.
    public var id: String { rawValue }

    /// Whether the status bar stays on screen, and so whether it gets painted.
    public var showsStatusBar: Bool {
        switch self {
        case .statusBar, .systemBars: return true
        case .fullscreen, .navigationBar: return false
        }
    }

    /// Whether the home indicator stays at full strength.
    ///
    /// `false` asks iOS to dim it, which is the closest thing to hiding the
    /// navigation bar: the gesture keeps working either way, so nobody is locked
    /// out of leaving the app.
    public var showsHomeIndicator: Bool {
        switch self {
        case .navigationBar, .systemBars: return true
        case .fullscreen, .statusBar: return false
        }
    }
}
