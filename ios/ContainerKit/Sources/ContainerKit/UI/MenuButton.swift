import UIKit

/// The small translucent button in the bottom-right corner that opens the menu.
///
/// Deliberately unobtrusive, matching the Android FAB exactly: 36 pt, neutral
/// grey, and it fades to 30% opacity a couple of seconds after the last touch,
/// so it barely registers over the page until you reach for it.
@MainActor
public final class MenuButton: UIButton {

    /// Opacity while the user is interacting, and at rest.
    public static let activeAlpha: CGFloat = 0.75
    public static let idleAlpha: CGFloat = 0.30
    public static let idleDelay: TimeInterval = 2.5

    public static let size: CGFloat = 36

    private var idling: Task<Void, Never>?

    public init(action: @escaping () -> Void) {
        super.init(frame: .zero)

        var configuration = UIButton.Configuration.filled()
        configuration.image = UIImage(systemName: "line.3.horizontal")
        configuration.baseBackgroundColor = UIColor.secondarySystemBackground
        configuration.baseForegroundColor = UIColor.label
        configuration.cornerStyle = .capsule
        configuration.contentInsets = .init(top: 8, leading: 8, bottom: 8, trailing: 8)
        self.configuration = configuration

        addAction(UIAction { _ in action() }, for: .primaryActionTriggered)
        accessibilityLabel = Strings.openMenu
        alpha = MenuButton.activeAlpha
        translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            widthAnchor.constraint(equalToConstant: MenuButton.size),
            heightAnchor.constraint(equalToConstant: MenuButton.size),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not used") }

    /// Brings the button back to full strength and restarts the fade.
    public func wake() {
        idling?.cancel()
        if alpha != MenuButton.activeAlpha {
            UIView.animate(withDuration: 0.15) { self.alpha = MenuButton.activeAlpha }
        }
        idling = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(MenuButton.idleDelay * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            UIView.animate(withDuration: 0.4) { self.alpha = MenuButton.idleAlpha }
        }
    }

    public func stopIdling() {
        idling?.cancel()
        idling = nil
    }
}
