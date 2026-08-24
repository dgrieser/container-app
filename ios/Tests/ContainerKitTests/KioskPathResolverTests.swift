import XCTest

@testable import ContainerKit

/// Which page opens on launch. A wrong answer here is the most visible bug the
/// app could have, and the rules are documented in the README, so they are pinned.
final class KioskPathResolverTests: XCTestCase {

    private let config = KioskConfig(apps: [
        Fixtures.app("https://a.example.com/dashboard", name: "Alpha"),
        Fixtures.app("https://b.example.com/reports", name: "Beta"),
    ])

    // MARK: defaultKioskPath

    func testNoDefaultMeansTheFirstApp() {
        let selection = KioskPathResolver.resolve(
            config: config, variant: Fixtures.variant(), lastSelectedURL: nil)
        XCTAssertEqual("https://a.example.com/dashboard", selection.selected?.url)
    }

    func testAnAbsoluteUrlMatchesTheConfiguredApp() {
        let variant = Fixtures.variant(defaultKioskPath: "https://b.example.com/reports")
        XCTAssertEqual("Beta", KioskPathResolver.defaultApp(in: config, variant: variant)?.name)
    }

    func testAnAbsoluteUrlMatchesByPrefixWhenItIsNotExact() {
        let variant = Fixtures.variant(defaultKioskPath: "https://b.example.com/")
        XCTAssertEqual("Beta", KioskPathResolver.defaultApp(in: config, variant: variant)?.name)
    }

    func testAnAbsoluteUrlTheConfigDoesNotListIsStillHonoured() {
        // This is what lets a variant be pinned to a page the shared kiosk.json
        // does not even mention -- and what lets one work with no kiosk.json at all.
        let variant = Fixtures.variant(
            displayName: "Pinned", defaultKioskPath: "https://c.example.com/only")
        let resolved = KioskPathResolver.defaultApp(in: config, variant: variant)
        XCTAssertEqual("https://c.example.com/only", resolved?.url)
        XCTAssertEqual("Pinned", resolved?.name)

        let alone = KioskPathResolver.defaultApp(in: .empty, variant: variant)
        XCTAssertEqual("https://c.example.com/only", alone?.url)
    }

    func testAPathMatchesTheAppWithThatPath() {
        let variant = Fixtures.variant(defaultKioskPath: "/reports")
        XCTAssertEqual("Beta", KioskPathResolver.defaultApp(in: config, variant: variant)?.name)
    }

    func testAPathWithoutALeadingSlashStillMatches() {
        let variant = Fixtures.variant(defaultKioskPath: "reports")
        XCTAssertEqual("Beta", KioskPathResolver.defaultApp(in: config, variant: variant)?.name)
    }

    func testAPathPrefersAnExactMatchOverAPrefix() {
        let deeper = KioskConfig(apps: [
            Fixtures.app("https://a.example.com/reports/2024", name: "Deep"),
            Fixtures.app("https://b.example.com/reports", name: "Exact"),
        ])
        let variant = Fixtures.variant(defaultKioskPath: "/reports")
        XCTAssertEqual("Exact", KioskPathResolver.defaultApp(in: deeper, variant: variant)?.name)
    }

    func testAnAppNameMatchesCaseInsensitively() {
        let variant = Fixtures.variant(defaultKioskPath: "beta")
        XCTAssertEqual("Beta", KioskPathResolver.defaultApp(in: config, variant: variant)?.name)
    }

    func testSomethingThatMatchesNothingFallsBackToTheFirstApp() {
        let variant = Fixtures.variant(defaultKioskPath: "/nowhere")
        XCTAssertNil(KioskPathResolver.defaultApp(in: config, variant: variant))
        let selection = KioskPathResolver.resolve(
            config: config, variant: variant, lastSelectedURL: nil)
        XCTAssertEqual("Alpha", selection.selected?.name)
    }

    // MARK: The menu

    func testAPinnedPageJoinsTheMenu() {
        // Otherwise switching away from it would be a one-way trip.
        let variant = Fixtures.variant(defaultKioskPath: "https://c.example.com/only")
        let selection = KioskPathResolver.resolve(
            config: config, variant: variant, lastSelectedURL: nil)
        XCTAssertEqual(3, selection.apps.count)
        XCTAssertTrue(selection.apps.contains { $0.url == "https://c.example.com/only" })
    }

    func testAPageAlreadyInTheMenuIsNotAddedTwice() {
        let variant = Fixtures.variant(defaultKioskPath: "https://b.example.com/reports")
        let selection = KioskPathResolver.resolve(
            config: config, variant: variant, lastSelectedURL: nil)
        XCTAssertEqual(2, selection.apps.count)
    }

    func testTheLastSelectionIsRestored() {
        let selection = KioskPathResolver.resolve(
            config: config,
            variant: Fixtures.variant(),
            lastSelectedURL: "https://b.example.com/reports"
        )
        XCTAssertEqual("Beta", selection.selected?.name)
    }

    func testASelectionThatIsNoLongerConfiguredIsIgnored() {
        // The kiosk.json changed under the app: fall back rather than load a page
        // the server no longer offers.
        let selection = KioskPathResolver.resolve(
            config: config,
            variant: Fixtures.variant(),
            lastSelectedURL: "https://gone.example.com/"
        )
        XCTAssertEqual("Alpha", selection.selected?.name)
    }

    func testAMenulessVariantIgnoresTheLastSelection() {
        // It could not have been chosen by the user, so honouring it would open a
        // page the build is not supposed to show.
        let variant = Fixtures.variant(
            defaultKioskPath: "https://a.example.com/dashboard", showMenu: false)
        let selection = KioskPathResolver.resolve(
            config: config, variant: variant, lastSelectedURL: "https://b.example.com/reports")
        XCTAssertEqual("Alpha", selection.selected?.name)
    }

    func testAnEmptyConfigWithNoPinnedPageSelectsNothing() {
        let selection = KioskPathResolver.resolve(
            config: .empty, variant: Fixtures.variant(), lastSelectedURL: nil)
        XCTAssertNil(selection.selected)
        XCTAssertTrue(selection.apps.isEmpty)
    }
}
