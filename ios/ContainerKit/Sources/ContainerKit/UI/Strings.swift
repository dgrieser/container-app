import Foundation

/// Every word the app says, in one place.
///
/// The counterpart of `res/values/strings.xml`, and the wording is taken from it
/// unchanged wherever the sentence is still true on iOS. Three are different
/// because the platform is:
///
/// * the full-screen hint no longer promises a navigation bar can be swiped in,
///   because iOS has none;
/// * the two "navigation bar" modes are described in terms of the home
///   indicator, which is what they now control;
/// * a denied location permission points at iOS's Settings rather than
///   Android's, and offers to open it, because iOS asks only once ever.
///
/// Not a String Catalog yet: the app ships in English, as the Android one does,
/// and a catalogue with one language in it is ceremony. The strings are gathered
/// here so that changing that is a mechanical edit.
public enum Strings {

    public static let menuTitle = "Apps"
    public static let menuAdmin = "Admin"
    public static let openMenu = "Open menu"

    // MARK: PIN

    public static let pinSetTitle = "Set a PIN"
    public static let pinSetMessage =
        "Create a PIN to protect the admin menu. You'll need it to change settings."
    public static let pinConfirmTitle = "Confirm PIN"
    public static let pinConfirmMessage = "Re-enter your PIN to confirm."
    public static let pinEnterTitle = "Enter PIN"
    public static let pinEnterMessage = "Enter your PIN to open the admin menu."
    public static let pinChangeTitle = "Change PIN"
    public static let pinCurrentMessage = "Enter your current PIN."
    public static let pinNewMessage = "Enter a new PIN."
    public static let pinPlaceholder = "PIN"
    public static let pinMismatch = "PINs don't match. Try again."
    public static let pinWrong = "Incorrect PIN."
    public static let pinTooShort = "PIN must be at least 4 digits."
    public static let pinChanged = "PIN changed."

    // MARK: Admin

    public static let adminTitle = "Admin"
    public static let adminConfigURL = "Configuration URL"
    public static let adminConfigURLOptional = "Optional — this build opens its own page"
    public static let adminScreenMode = "Screen mode"
    public static let adminAllowUnverifiedSSL = "Allow unverified certificates"
    public static let adminAllowUnverifiedSSLHint =
        "Load pages even when the HTTPS certificate can't be verified (self-signed, "
        + "expired, unknown CA or wrong host name). Only use this on a trusted "
        + "network — the connection is no longer protected against interception."
    public static let adminChangePin = "Change PIN"
    public static let adminReload = "Reload configuration"
    public static let adminSave = "Save"
    public static let adminCancel = "Cancel"
    public static let adminDone = "Done"
    public static let adminSaved = "Settings saved."
    public static let adminURLInvalid = "Please enter a valid http(s) URL."

    // MARK: Screen modes

    public static func screenModeLabel(_ mode: ScreenMode) -> String {
        switch mode {
        case .fullscreen: return "Full screen"
        case .statusBar: return "Full screen, status bar visible"
        case .navigationBar: return "Full screen, home indicator visible"
        case .systemBars: return "Status bar and home indicator visible"
        }
    }

    public static func screenModeHint(_ mode: ScreenMode) -> String {
        switch mode {
        case .fullscreen:
            return "The page uses the whole display. Swipe down from the top edge to "
                + "see the status bar for a moment."
        case .statusBar:
            return "Keeps the top bar — clock, battery and notifications — above the "
                + "page. The home indicator is dimmed."
        case .navigationBar:
            return "Keeps the home indicator in view below the page. The status bar "
                + "stays hidden."
        case .systemBars:
            return "The status bar stays in view and the page sits below it, like an "
                + "ordinary app."
        }
    }

    // MARK: Configuration and errors

    public static let loading = "Loading…"
    public static let configLoadFailed = "Couldn't load configuration."
    public static let configEmpty = "No apps are configured yet."
    public static let configRetry = "Retry"
    public static let configOpenAdmin = "Open admin"
    public static let reloading = "Reloading configuration…"

    public static func navigationBlocked(domain: String) -> String {
        "Navigation outside \(domain) is blocked."
    }

    public static let sslBlockedHost = "this site"

    public static func sslBlocked(host: String) -> String {
        "Blocked: the certificate of \(host) couldn't be verified. Enable unverified "
            + "certificates in the admin menu to load it anyway."
    }

    public static let locationPermissionDenied =
        "Without the location permission this page can't use your position."
    public static let openSettings = "Settings"

    public static let pageFailed = "Couldn't load the page."
    public static let webContentCrashed = "The page stopped responding. Reloading."
}
