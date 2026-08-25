import UIKit

/// The admin settings: which configuration to read, how much system UI stays
/// over the page, whether unverified certificates are accepted, and the PIN.
///
/// The port of `dialog_admin.xml`. The Android dialog scrolls a column of fields
/// inside an alert; here it is a modal sheet with the same column, a navigation
/// bar for Cancel and Save, and a pull-down menu instead of the exposed dropdown.
@MainActor
public final class AdminViewController: UIViewController {

    /// What Save produced. Applied by the caller, so this controller stays a form.
    public struct Settings {
        public let configURL: String
        public let screenMode: ScreenMode
        public let allowUnverifiedSSL: Bool
        /// True when the TLS switch changed, which is what needs the transport reset.
        public let tlsChanged: Bool
    }

    private let variant: VariantConfig
    private let prefs: Prefs
    private let onSave: (Settings) -> Void
    private let onReload: () -> Void
    private let onChangePin: () -> Void

    private let urlField = UITextField()
    private let modeButton = UIButton(configuration: .gray())
    private let modeHint = UILabel()
    private let sslSwitch = UISwitch()
    private var selectedMode: ScreenMode

    public init(
        variant: VariantConfig,
        prefs: Prefs,
        onSave: @escaping (Settings) -> Void,
        onReload: @escaping () -> Void,
        onChangePin: @escaping () -> Void
    ) {
        self.variant = variant
        self.prefs = prefs
        self.onSave = onSave
        self.onReload = onReload
        self.onChangePin = onChangePin
        self.selectedMode = prefs.screenMode
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not used") }

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground
        title = Strings.adminTitle

        navigationItem.leftBarButtonItem = UIBarButtonItem(
            systemItem: .cancel,
            primaryAction: UIAction { [weak self] _ in self?.dismiss(animated: true) }
        )
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: Strings.adminSave,
            primaryAction: UIAction { [weak self] _ in self?.save() }
        )

        buildForm()
    }

    private func buildForm() {
        urlField.text = storedConfigURL
        // The hint says what leaving it empty means, which differs per variant:
        // a build with its own pinned page needs no configuration file at all.
        urlField.placeholder = variant.defaultConfigURL.isEmpty
            ? Strings.adminConfigURLOptional
            : variant.defaultConfigURL
        urlField.keyboardType = .URL
        urlField.autocapitalizationType = .none
        urlField.autocorrectionType = .no
        urlField.clearButtonMode = .whileEditing
        urlField.borderStyle = .roundedRect

        modeButton.showsMenuAsPrimaryAction = true

        modeHint.font = .preferredFont(forTextStyle: .footnote)
        modeHint.adjustsFontForContentSizeCategory = true
        modeHint.textColor = .secondaryLabel
        modeHint.numberOfLines = 0

        sslSwitch.isOn = prefs.allowUnverifiedSSL

        var rows: [UIView] = [
            field(label: Strings.adminConfigURL, control: urlField),
            field(label: Strings.adminScreenMode, control: modeButton, hint: modeHint),
            toggle(
                label: Strings.adminAllowUnverifiedSSL,
                control: sslSwitch,
                hint: Strings.adminAllowUnverifiedSSLHint
            ),
            button(Strings.adminReload) { [weak self] in
                self?.dismiss(animated: true) { self?.onReload() }
            },
        ]
        // A variant with no PIN has nothing to change, exactly as on Android.
        if variant.requirePin {
            rows.append(button(Strings.adminChangePin) { [weak self] in self?.onChangePin() })
        }

        let stack = UIStackView(arrangedSubviews: rows)
        stack.axis = .vertical
        stack.spacing = 24
        stack.translatesAutoresizingMaskIntoConstraints = false

        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        view.addSubview(scroll)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 20),
            stack.bottomAnchor.constraint(
                equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -20),
            stack.leadingAnchor.constraint(
                equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(
                equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -20),
        ])

        refreshModeHint()
    }

    /// What the admin actually stored, rather than what `Prefs` falls back to —
    /// so an untouched field shows empty and its placeholder explains the default.
    private var storedConfigURL: String {
        let effective = prefs.configURL
        return effective == variant.defaultConfigURL ? "" : effective
    }

    private func screenModeMenu() -> UIMenu {
        UIMenu(children: ScreenMode.allCases.map { mode in
            UIAction(
                title: Strings.screenModeLabel(mode),
                state: mode == selectedMode ? .on : .off
            ) { [weak self] _ in
                self?.selectedMode = mode
                self?.refreshModeHint()
            }
        })
    }

    private func refreshModeHint() {
        modeButton.configuration?.title = Strings.screenModeLabel(selectedMode)
        modeHint.text = Strings.screenModeHint(selectedMode)
        // The tick has to move with the selection, and a UIMenu is immutable.
        modeButton.menu = screenModeMenu()
    }

    private func save() {
        let entered = urlField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        // Empty is valid and means "use the variant's own URL"; anything else has
        // to be a URL the app could actually fetch.
        if !entered.isEmpty, !DomainRules.isHTTP(entered) {
            let alert = UIAlertController(
                title: nil, message: Strings.adminURLInvalid, preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: Strings.adminDone, style: .default))
            present(alert, animated: true)
            return
        }
        let settings = Settings(
            configURL: entered,
            screenMode: selectedMode,
            allowUnverifiedSSL: sslSwitch.isOn,
            tlsChanged: sslSwitch.isOn != prefs.allowUnverifiedSSL
        )
        dismiss(animated: true) { [onSave] in
            onSave(settings)
        }
    }

    // MARK: Row builders

    private func field(label text: String, control: UIView, hint: UILabel? = nil) -> UIView {
        let label = UILabel()
        label.text = text
        label.font = .preferredFont(forTextStyle: .headline)
        label.adjustsFontForContentSizeCategory = true

        let rows: [UIView] = [label, control] + (hint.map { [$0 as UIView] } ?? [])
        let stack = UIStackView(arrangedSubviews: rows)
        stack.axis = .vertical
        stack.spacing = 8
        stack.alignment = .fill
        return stack
    }

    private func toggle(label text: String, control: UISwitch, hint: String) -> UIView {
        let label = UILabel()
        label.text = text
        label.font = .preferredFont(forTextStyle: .headline)
        label.adjustsFontForContentSizeCategory = true
        label.numberOfLines = 0

        let row = UIStackView(arrangedSubviews: [label, control])
        row.axis = .horizontal
        row.spacing = 12
        row.alignment = .center

        let hintLabel = UILabel()
        hintLabel.text = hint
        hintLabel.font = .preferredFont(forTextStyle: .footnote)
        hintLabel.adjustsFontForContentSizeCategory = true
        hintLabel.textColor = .secondaryLabel
        hintLabel.numberOfLines = 0

        let stack = UIStackView(arrangedSubviews: [row, hintLabel])
        stack.axis = .vertical
        stack.spacing = 8
        return stack
    }

    private func button(_ title: String, action: @escaping () -> Void) -> UIView {
        var configuration = UIButton.Configuration.tinted()
        configuration.title = title
        let button = UIButton(
            configuration: configuration,
            primaryAction: UIAction { _ in action() }
        )
        return button
    }
}
