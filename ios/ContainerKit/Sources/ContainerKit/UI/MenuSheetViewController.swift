import UIKit

/// The bottom sheet that switches between the allowed apps.
///
/// The port of `sheet_menu.xml` plus `item_app.xml`: a titled list of apps, each
/// with its icon, a tick on the one showing, then an **Admin** row below a
/// separator. `UISheetPresentationController` gives the sheet itself, so there is
/// no layout to hand-build.
@MainActor
public final class MenuSheetViewController: UITableViewController {

    private enum Section: Int, CaseIterable {
        case apps, admin
    }

    private let apps: [AppEntry]
    private let currentURL: String?
    private let icons: IconLoader
    private let onSelect: (AppEntry) -> Void
    private let onAdmin: () -> Void

    public init(
        apps: [AppEntry],
        currentURL: String?,
        icons: IconLoader,
        onSelect: @escaping (AppEntry) -> Void,
        onAdmin: @escaping () -> Void
    ) {
        self.apps = apps
        self.currentURL = currentURL
        self.icons = icons
        self.onSelect = onSelect
        self.onAdmin = onAdmin
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not used") }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = Strings.menuTitle
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "row")
    }

    public override func numberOfSections(in tableView: UITableView) -> Int {
        Section.allCases.count
    }

    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        Section(rawValue: section) == .apps ? apps.count : 1
    }

    public override func tableView(
        _ tableView: UITableView, cellForRowAt indexPath: IndexPath
    ) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "row", for: indexPath)
        var content = UIListContentConfiguration.cell()

        guard Section(rawValue: indexPath.section) == .apps else {
            content.text = Strings.menuAdmin
            content.image = UIImage(systemName: "gearshape")
            cell.contentConfiguration = content
            cell.accessoryType = .disclosureIndicator
            return cell
        }

        let app = apps[indexPath.row]
        content.text = app.name
        content.secondaryText = DomainRules.host(of: app.url)
        content.image = UIImage(systemName: "square.on.square")
        content.imageProperties.maximumSize = CGSize(width: 40, height: 40)
        content.imageProperties.cornerRadius = 8
        cell.contentConfiguration = content
        cell.accessoryType = app.url == currentURL ? .checkmark : .none

        // The icon arrives whenever it arrives; until then the placeholder stands
        // in, which is what the configuration format promises for a missing one.
        if let iconURL = app.iconURL {
            Task { [weak self, weak cell] in
                guard let self else { return }
                guard let image = await self.icons.load(iconURL) else { return }
                // The cell may have been reused for another app by now.
                guard let cell, tableView.indexPath(for: cell) == indexPath else { return }
                var updated = cell.contentConfiguration as? UIListContentConfiguration ?? content
                updated.image = image
                cell.contentConfiguration = updated
            }
        }
        return cell
    }

    public override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let isAdmin = Section(rawValue: indexPath.section) == .admin
        let app = isAdmin ? nil : apps[indexPath.row]
        dismiss(animated: true) { [onSelect, onAdmin] in
            if let app { onSelect(app) } else { onAdmin() }
        }
    }
}
