import UIKit

/// Turns a ``ScreenMode`` and a theme into the appearance the view controller
/// asks for.
///
/// This is the hardest mapping in the port, so it is a value rather than a
/// scattering of `if` statements inside the view controller: the table below is
/// the whole of it, and `ScreenModePolicyTests` asserts it field by field.
///
/// What Android does and iOS cannot:
///
/// * There is **no navigation bar** on iOS. The two modes that name one are
///   reinterpreted around the home indicator, which can be dimmed
///   (`prefersHomeIndicatorAutoHidden`) but never removed — the gesture keeps
///   working either way, so nobody is locked out of leaving the app.
/// * There is **no status-bar background** to set. The app paints its own view
///   behind the top safe area instead; only the bar's *content* is the system's,
///   and that comes from the same 0.179 luminance threshold Android uses.
/// * On an **iPhone in landscape** iOS force-hides the status bar whatever this
///   asks for, so `statusBar` and `systemBars` lose their strip there. That
///   affects the `gasoline` variant in ordinary use.
/// * A hidden bar can still be swiped in on both platforms, but on iOS the same
///   gesture pulls Control Centre, so in `fullscreen` the clock is genuinely
///   harder to reach than the Android README promises.
public struct ScreenModePolicy: Equatable {

    public let statusBarHidden: Bool
    public let statusBarStyle: UIStatusBarStyle
    public let homeIndicatorAutoHidden: Bool
    /// Edges where a system gesture waits for a second swipe, so a page whose
    /// own content lives near the edge is not fighting the system for it.
    public let deferringSystemGestureEdges: UIRectEdge
    /// Which safe-area edges the page is kept clear of. A bar this mode keeps
    /// gets its own strip and the page sits beside it, rather than underneath.
    public let respectsSafeAreaTop: Bool
    public let respectsSafeAreaBottom: Bool

    public init(mode: ScreenMode, barColor: BarColor) {
        statusBarHidden = !mode.showsStatusBar
        // Dark glyphs over a light strip, light ones over a dark strip, measured
        // as the colour will actually look once composited.
        statusBarStyle = barColor.needsDarkIcons() ? .darkContent : .lightContent
        homeIndicatorAutoHidden = !mode.showsHomeIndicator
        respectsSafeAreaTop = mode.showsStatusBar
        respectsSafeAreaBottom = mode.showsHomeIndicator
        // Full-screen modes own the whole display, cutout included, so the
        // system's own edge gestures give the page first refusal.
        deferringSystemGestureEdges = mode.showsHomeIndicator ? [] : [.bottom]
    }
}
