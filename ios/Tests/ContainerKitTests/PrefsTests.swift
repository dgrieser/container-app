import XCTest

@testable import ContainerKit

/// What the app remembers, and — more importantly — what it falls back to.
///
/// Every getter falls back to the *variant's* value rather than to a type
/// default. `allowUnverifiedSSL` is the one that would bite: reading it with a
/// plain `bool(forKey:)` answers false for "never set", which would quietly
/// disable the switch on a build that ships with it on.
final class PrefsTests: XCTestCase {

    private var defaults: UserDefaults!
    private let suite = "de.davidgrieser.container.tests"

    override func setUp() {
        super.setUp()
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    private func prefs(_ variant: VariantConfig) -> Prefs {
        Prefs(defaults: defaults, variant: variant)
    }

    // MARK: Configuration URL

    func testTheConfigUrlDefaultsToTheVariantS() {
        let prefs = prefs(Fixtures.variant(defaultConfigURL: "https://example.com/kiosk.json"))
        XCTAssertEqual("https://example.com/kiosk.json", prefs.configURL)
    }

    func testClearingTheConfigUrlRestoresTheVariantS() {
        // Documented behaviour: "leaving the admin field empty restores the
        // variant's own URL".
        let prefs = prefs(Fixtures.variant(defaultConfigURL: "https://example.com/kiosk.json"))
        prefs.configURL = "https://elsewhere.example.com/k.json"
        XCTAssertEqual("https://elsewhere.example.com/k.json", prefs.configURL)
        prefs.configURL = "   "
        XCTAssertEqual("https://example.com/kiosk.json", prefs.configURL)
    }

    func testAVariantWithNoConfigFileHasAnEmptyUrl() {
        // Such a build fetches nothing at all; an empty string is the signal.
        let prefs = prefs(Fixtures.variant(defaultConfigURL: ""))
        XCTAssertEqual("", prefs.configURL)
    }

    // MARK: TLS

    func testTheTlsSwitchStartsWhereTheVariantSaysAndCanBeTurnedOff() {
        // The regression this guards: an unset value must mean "ask the variant",
        // not "false".
        let prefs = prefs(Fixtures.variant(allowUnverifiedSSL: true))
        XCTAssertTrue(prefs.allowUnverifiedSSL)
        prefs.allowUnverifiedSSL = false
        XCTAssertFalse(prefs.allowUnverifiedSSL)
    }

    func testTheTlsSwitchStartsOffForAnOrdinaryVariant() {
        let prefs = prefs(Fixtures.variant(allowUnverifiedSSL: false))
        XCTAssertFalse(prefs.allowUnverifiedSSL)
        prefs.allowUnverifiedSSL = true
        XCTAssertTrue(prefs.allowUnverifiedSSL)
    }

    // MARK: Screen mode

    func testTheScreenModeStartsAtTheVariantSAndThenPersists() {
        let prefs = prefs(Fixtures.variant(screenMode: .statusBar))
        XCTAssertEqual(.statusBar, prefs.screenMode)
        prefs.screenMode = .systemBars
        XCTAssertEqual(.systemBars, prefs.screenMode)
    }

    // MARK: Cached configuration

    func testTheCachedConfigurationRoundTrips() {
        let prefs = prefs(Fixtures.variant())
        XCTAssertNil(prefs.loadCachedConfig())

        let config = KioskConfig(apps: [
            Fixtures.app("https://a.example.com/", name: "Alpha", icon: "https://a/i.png"),
            Fixtures.app("https://b.example.com/", name: "Beta"),
        ])
        prefs.saveCachedConfig(config)
        XCTAssertEqual(config, prefs.loadCachedConfig())
    }

    func testTheCacheSurvivesAnAppWithNoIcon() {
        let prefs = prefs(Fixtures.variant())
        prefs.saveCachedConfig(KioskConfig(apps: [Fixtures.app("https://a.example.com/", name: "A")]))
        XCTAssertEqual(nil, prefs.loadCachedConfig()?.apps.first?.iconURL)
    }

    func testAnEmptyCacheIsSavedAndReadBack() {
        // Distinct from "nothing cached": the server really did answer with no apps.
        let prefs = prefs(Fixtures.variant())
        prefs.saveCachedConfig(.empty)
        XCTAssertEqual(KioskConfig.empty, prefs.loadCachedConfig())
    }

    func testNonsenseInTheCacheIsIgnoredRatherThanCrashing() {
        defaults.set("{not json", forKey: "cached_config")
        XCTAssertNil(prefs(Fixtures.variant()).loadCachedConfig())
    }

    // MARK: Selection

    func testTheSelectedAppIsRemembered() {
        let prefs = prefs(Fixtures.variant())
        XCTAssertNil(prefs.selectedAppURL)
        prefs.selectedAppURL = "https://b.example.com/"
        XCTAssertEqual("https://b.example.com/", prefs.selectedAppURL)
    }
}
