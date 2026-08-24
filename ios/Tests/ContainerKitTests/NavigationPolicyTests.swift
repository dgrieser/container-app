import XCTest

@testable import ContainerKit

/// What happens to a navigation, as a table.
///
/// The policy is a separate pure type precisely so this test can exist:
/// `WKNavigationAction` has no usable initialiser, so the delegate itself cannot
/// be driven from a unit test.
final class NavigationPolicyTests: XCTestCase {

    private let anchor = "https://portal.example.com/"

    func testInDomainIsAllowedWhicheverWayTheVariantIsBuilt() {
        for allowExternal in [true, false] {
            XCTAssertEqual(
                .allow,
                NavigationPolicy.decide(
                    anchor: anchor,
                    candidate: "https://cdn.example.com/asset",
                    allowExternalNavigation: allowExternal
                )
            )
        }
    }

    func testOffDomainIsRefusedByDefault() {
        XCTAssertEqual(
            .blocked,
            NavigationPolicy.decide(
                anchor: anchor, candidate: "https://other.com/", allowExternalNavigation: false)
        )
    }

    func testOffDomainLeavesForTheBrowserWhenTheVariantOptsIn() {
        XCTAssertEqual(
            .handOffExternally,
            NavigationPolicy.decide(
                anchor: anchor, candidate: "https://other.com/", allowExternalNavigation: true)
        )
    }

    func testOnlyHttpIsEverHandedOver() {
        // The deliberate limit: a page must not be able to use the setting to fire
        // arbitrary schemes at whatever else is installed.
        for candidate in ["mailto:a@b.com", "myapp://x", "tel:+49", "intent://x#Intent;end"] {
            XCTAssertEqual(
                .blocked,
                NavigationPolicy.decide(
                    anchor: anchor, candidate: candidate, allowExternalNavigation: true),
                candidate
            )
        }
    }

    func testNothingIsAllowedWithoutAnAnchor() {
        XCTAssertEqual(
            .blocked,
            NavigationPolicy.decide(
                anchor: nil, candidate: "https://example.com/", allowExternalNavigation: false)
        )
        // …but an opted-in variant would still send it out rather than dropping it
        // on the floor, which is what Android does too.
        XCTAssertEqual(
            .handOffExternally,
            NavigationPolicy.decide(
                anchor: nil, candidate: "https://example.com/", allowExternalNavigation: true)
        )
    }

    func testANilCandidateIsBlocked() {
        XCTAssertEqual(
            .blocked,
            NavigationPolicy.decide(
                anchor: anchor, candidate: nil, allowExternalNavigation: true)
        )
    }
}
