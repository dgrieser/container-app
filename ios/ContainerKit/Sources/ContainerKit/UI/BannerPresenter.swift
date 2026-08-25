import UIKit

/// What a `Toast` becomes.
///
/// iOS has nothing like one, so this is a small label that slides in over the
/// page and leaves on its own. Two details are load-bearing:
///
/// * `isUserInteractionEnabled = false`, always. A banner that swallows taps
///   would silently break whatever is under it, and the failure would look like
///   the page being broken rather than the banner being in the way.
/// * An identical message already showing is not shown again. The Android code
///   guards its own repeats with flags per message (`sslErrorReported`); here the
///   presenter coalesces, which covers the cases nobody thought to flag.
///
/// Unlike a `Toast` it cannot outlive the app, so a message shown as the app goes
/// to the background is simply lost. Nothing here is important enough for that
/// to matter.
@MainActor
public final class BannerPresenter {

    public enum Duration {
        case short, long

        var seconds: TimeInterval {
            switch self {
            case .short: return 2.5
            case .long: return 4.5
            }
        }
    }

    private weak var host: UIView?
    private var current: (message: String, view: UIView)?
    private var dismissal: Task<Void, Never>?

    public init(host: UIView) {
        self.host = host
    }

    /// Shows `message`, optionally with one action the user can tap.
    public func show(
        _ message: String,
        duration: Duration = .short,
        actionTitle: String? = nil,
        action: (() -> Void)? = nil
    ) {
        guard let host else { return }
        // Already saying this: leave it be rather than restarting the animation.
        if current?.message == message { return }
        dismiss()

        let banner = BannerView(
            message: message,
            actionTitle: actionTitle,
            action: { [weak self] in
                action?()
                self?.dismiss()
            }
        )
        banner.alpha = 0
        banner.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(banner)
        NSLayoutConstraint.activate([
            banner.leadingAnchor.constraint(
                greaterThanOrEqualTo: host.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            banner.trailingAnchor.constraint(
                lessThanOrEqualTo: host.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            banner.centerXAnchor.constraint(equalTo: host.centerXAnchor),
            // Above the menu button, which sits in the bottom-right corner.
            banner.bottomAnchor.constraint(
                equalTo: host.safeAreaLayoutGuide.bottomAnchor, constant: -72),
        ])

        current = (message, banner)
        UIView.animate(withDuration: 0.2) { banner.alpha = 1 }

        dismissal = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(duration.seconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.dismiss()
        }
    }

    public func dismiss() {
        dismissal?.cancel()
        dismissal = nil
        guard let (_, view) = current else { return }
        current = nil
        UIView.animate(withDuration: 0.2, animations: { view.alpha = 0 }) { _ in
            view.removeFromSuperview()
        }
    }
}

/// The banner itself: a rounded, blurred strip with a label and at most one button.
private final class BannerView: UIVisualEffectView {

    init(message: String, actionTitle: String?, action: @escaping () -> Void) {
        super.init(effect: UIBlurEffect(style: .systemThickMaterial))

        // The page keeps working while this is on screen; see the class comment.
        isUserInteractionEnabled = actionTitle != nil

        layer.cornerRadius = 14
        layer.cornerCurve = .continuous
        clipsToBounds = true

        let label = UILabel()
        label.text = message
        label.font = .preferredFont(forTextStyle: .subheadline)
        label.adjustsFontForContentSizeCategory = true
        label.numberOfLines = 0
        label.textColor = .label

        let row = UIStackView(arrangedSubviews: [label])
        row.axis = .horizontal
        row.spacing = 12
        row.alignment = .center
        row.translatesAutoresizingMaskIntoConstraints = false

        if let actionTitle {
            var configuration = UIButton.Configuration.plain()
            configuration.title = actionTitle
            configuration.contentInsets = .zero
            let button = UIButton(
                configuration: configuration,
                primaryAction: UIAction { _ in action() }
            )
            button.setContentCompressionResistancePriority(.required, for: .horizontal)
            row.addArrangedSubview(button)
        }

        contentView.addSubview(row)
        NSLayoutConstraint.activate([
            row.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
            row.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -12),
            row.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
            row.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
            widthAnchor.constraint(lessThanOrEqualToConstant: 420),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not used") }
}
