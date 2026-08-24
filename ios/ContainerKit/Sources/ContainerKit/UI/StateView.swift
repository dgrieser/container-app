import UIKit

/// What is shown instead of a page when there is no page to show.
///
/// The port of `activity_main.xml`'s `stateContainer`: a message and up to two
/// buttons, centred. It stands in for the two cases the Android app has —
/// nothing configured, and the configuration would not load — and keeps the
/// admin menu reachable, which is the only way out of either.
@MainActor
public final class StateView: UIView {

    private let label = UILabel()
    private let primary = UIButton(configuration: .filled())
    private let secondary = UIButton(configuration: .plain())
    private var onPrimary: (() -> Void)?
    private var onSecondary: (() -> Void)?

    public override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .systemBackground

        label.font = .preferredFont(forTextStyle: .body)
        label.adjustsFontForContentSizeCategory = true
        label.textAlignment = .center
        label.numberOfLines = 0
        label.textColor = .secondaryLabel

        primary.addAction(
            UIAction { [weak self] _ in self?.onPrimary?() },
            for: .primaryActionTriggered
        )
        secondary.addAction(
            UIAction { [weak self] _ in self?.onSecondary?() },
            for: .primaryActionTriggered
        )

        let stack = UIStackView(arrangedSubviews: [label, primary, secondary])
        stack.axis = .vertical
        stack.spacing = 16
        stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: layoutMarginsGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: layoutMarginsGuide.trailingAnchor),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not used") }

    public func show(
        message: String,
        primaryTitle: String?,
        primaryAction: (() -> Void)? = nil,
        secondaryTitle: String? = nil,
        secondaryAction: (() -> Void)? = nil
    ) {
        label.text = message
        // A configuration-based button keeps its title in the configuration;
        // setTitle(_:for:) is ignored on one.
        primary.configuration?.title = primaryTitle
        primary.isHidden = primaryTitle == nil
        onPrimary = primaryAction
        secondary.configuration?.title = secondaryTitle
        secondary.isHidden = secondaryTitle == nil
        onSecondary = secondaryAction
        isHidden = false
    }

    public func hide() {
        isHidden = true
    }
}
