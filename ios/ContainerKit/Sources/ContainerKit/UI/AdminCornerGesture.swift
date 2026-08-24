import UIKit

/// How the admin menu is reached in a variant with no menu button.
///
/// Holding the bottom-right corner for 1.5 s, exactly as on Android. The
/// interesting part is what it must *not* do: the page underneath has to keep
/// working normally, so the recogniser observes touches rather than consuming
/// them (`cancelsTouchesInView = false`), and it only claims a press that starts
/// inside the corner square.
///
/// Android achieves the same by watching `dispatchTouchEvent` without returning
/// true. A `UILongPressGestureRecognizer` with `cancelsTouchesInView` off is the
/// direct equivalent, and it brings the movement tolerance and the multi-touch
/// cancellation for free rather than by hand.
@MainActor
public final class AdminCornerGesture: NSObject, UIGestureRecognizerDelegate {

    /// The corner square, in points. 72 pt where Android uses 72 dp.
    public static let cornerSize: CGFloat = 72
    public static let holdDuration: TimeInterval = 1.5

    private let action: () -> Void
    private weak var host: UIView?
    private let recogniser = UILongPressGestureRecognizer()

    public init(host: UIView, action: @escaping () -> Void) {
        self.host = host
        self.action = action
        super.init()

        recogniser.minimumPressDuration = AdminCornerGesture.holdDuration
        recogniser.numberOfTouchesRequired = 1
        recogniser.cancelsTouchesInView = false
        recogniser.delaysTouchesBegan = false
        recogniser.delaysTouchesEnded = false
        recogniser.delegate = self
        recogniser.addTarget(self, action: #selector(handle))
        host.addGestureRecognizer(recogniser)
    }

    @objc private func handle(_ sender: UILongPressGestureRecognizer) {
        guard sender.state == .began else { return }
        action()
    }

    /// Only a press that begins inside the bottom-right corner counts.
    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let host else { return false }
        let point = gestureRecognizer.location(in: host)
        let size = AdminCornerGesture.cornerSize
        return point.x >= host.bounds.maxX - size && point.y >= host.bounds.maxY - size
    }

    /// The page's own gestures — scrolling, taps, a long press on a link — carry
    /// on regardless.
    public func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
    ) -> Bool {
        true
    }
}
