import CoreLocation
import Foundation

/// The app's side of `navigator.geolocation`.
///
/// On Android this is a three-part gate: the build allows location, the asking
/// origin is inside the domain lock, and the OS permission is held —
/// `onGeolocationPermissionsShowPrompt` hands over the origin, so the middle
/// check is possible.
///
/// **WKWebView provides no such callback.** It answers a page's request itself,
/// using the app's own CoreLocation authorization, and never says which origin
/// asked. So the middle check is gone: on iOS, an embedded third-party frame can
/// use a permission the anchored site was granted. That is the one security
/// property this port genuinely weakens, and it is a deliberate choice — the
/// alternative is shimming `navigator.geolocation` with injected JavaScript,
/// which reimplements the W3C API, is itself escapable from a script-created
/// frame, and is a maintenance surface the Android app never had.
///
/// The outer gate is intact and is the stronger half anyway: a variant without
/// `allowLocation` carries no `NSLocationWhenInUseUsageDescription`, so it cannot
/// ask at all — iOS terminates an app that tries. That is a closer analogue of
/// "the APK carries no location permission" than a runtime check would be.
///
/// What is left for this type is the part iOS does badly on its own: it prompts
/// **once, ever**, so a denial is permanent until the user visits Settings. The
/// broker notices the denial and offers to take them there.
public final class LocationBroker: NSObject {

    /// Called on the main queue when the page's request cannot succeed because
    /// authorization was refused — at most once per page load.
    public var onAuthorizationDenied: (() -> Void)?

    private let variant: VariantConfig
    private var manager: CLLocationManager?
    private var reportedForThisPage = false

    public init(variant: VariantConfig) {
        self.variant = variant
        super.init()
        // A build that cannot ask for a position has nothing to observe, and
        // constructing a CLLocationManager in it would be the one thing that
        // could make iOS prompt in an app with no usage description.
        guard variant.allowLocation else { return }
        let manager = CLLocationManager()
        manager.delegate = self
        self.manager = manager
    }

    /// Whether the page could get a position if it asked right now.
    public var isAuthorized: Bool {
        guard let manager else { return false }
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: return true
        default: return false
        }
    }

    /// A new page load: explain a refusal again if it comes up.
    ///
    /// Android reports a denial once per page load and then leaves the page to its
    /// own error handling; this keeps that cadence.
    public func resetPerPageWarnings() {
        reportedForThisPage = false
    }
}

extension LocationBroker: CLLocationManagerDelegate {

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .denied, .restricted:
            guard !reportedForThisPage else { return }
            reportedForThisPage = true
            let notify = onAuthorizationDenied
            DispatchQueue.main.async { notify?() }
        default:
            break
        }
    }
}
