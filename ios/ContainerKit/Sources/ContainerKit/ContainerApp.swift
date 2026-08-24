import UIKit

/// The app's entry point.
///
/// Each variant's target is one generated file that calls this:
///
/// ```swift
/// ContainerApp.main(variant: Variant.current)
/// ```
///
/// Everything else lives in this package, which is what lets a new source file
/// arrive without regenerating the Xcode project.
@MainActor
public enum ContainerApp {

    /// Runs the app. Never returns, as `UIApplicationMain` never does.
    public static func main(variant: VariantConfig) -> Never {
        ContainerAppDelegate.variant = variant
        _ = UIApplicationMain(
            CommandLine.argc,
            CommandLine.unsafeArgv,
            nil,
            NSStringFromClass(ContainerAppDelegate.self)
        )
        // UIApplicationMain does not return; this is only here to satisfy `Never`.
        fatalError("UIApplicationMain returned")
    }
}

/// The application delegate.
///
/// `variant` is a static because `UIApplicationMain` constructs the delegate
/// itself and offers nowhere to pass anything in. It is written exactly once,
/// before the run loop starts.
public final class ContainerAppDelegate: UIResponder, UIApplicationDelegate {

    static var variant: VariantConfig?

    public func application(
        _ application: UIApplication,
        configurationForConnecting session: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: session.role)
        configuration.delegateClass = ContainerSceneDelegate.self
        return configuration
    }
}

/// Builds the one window and the one view controller.
public final class ContainerSceneDelegate: UIResponder, UIWindowSceneDelegate {

    public var window: UIWindow?

    public func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene,
              let variant = ContainerAppDelegate.variant
        else {
            return
        }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = KioskViewController(variant: variant)
        window.makeKeyAndVisible()
        self.window = window
    }
}
