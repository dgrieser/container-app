import XCTest

@testable import ContainerKit

/// The domain lock. Everything else in the app is a convenience; this is the
/// property the container exists to have, so it is tested exhaustively.
final class DomainRulesTests: XCTestCase {

    func testTheSameHostIsAllowed() {
        XCTAssertTrue(DomainRules.isAllowed(
            anchor: "https://portal.example.com/",
            candidate: "https://portal.example.com/dashboard?a=1#b"
        ))
    }

    func testSubdomainsAreAllowedInBothDirections() {
        // An app on a subdomain may link to its parent, and vice versa: `www.`,
        // `cdn.` and `portal.` all have to work from one another.
        XCTAssertTrue(DomainRules.isAllowed(
            anchor: "https://portal.example.com/", candidate: "https://example.com/"))
        XCTAssertTrue(DomainRules.isAllowed(
            anchor: "https://example.com/", candidate: "https://cdn.example.com/"))
        XCTAssertTrue(DomainRules.isAllowed(
            anchor: "https://a.b.example.com/", candidate: "https://example.com/"))
    }

    func testAnotherDomainIsRefused() {
        for candidate in [
            "https://other.com/",
            "https://example.com.evil.com/",
            "https://notexample.com/",
            "https://evil.com/?next=https://example.com/",
        ] {
            XCTAssertFalse(
                DomainRules.isAllowed(anchor: "https://example.com/", candidate: candidate),
                candidate
            )
        }
    }

    func testWwwIsIgnoredOnBothSides() {
        XCTAssertTrue(DomainRules.isAllowed(
            anchor: "https://www.example.com/", candidate: "https://example.com/"))
        XCTAssertTrue(DomainRules.isAllowed(
            anchor: "https://example.com/", candidate: "https://www.example.com/"))
    }

    func testTheHostIsComparedCaseInsensitively() {
        XCTAssertTrue(DomainRules.isAllowed(
            anchor: "https://Example.COM/", candidate: "https://example.com/"))
    }

    func testOnlyHttpSchemesAreEverAllowed() {
        // A page must not be able to reach another app through a link, whatever
        // the domain says.
        for candidate in [
            "mailto:someone@example.com",
            "tel:+491234",
            "javascript:alert(1)",
            "intent://example.com#Intent;scheme=https;end",
            "myapp://example.com/",
            "file:///etc/passwd",
            "data:text/html,<h1>hi</h1>",
        ] {
            XCTAssertFalse(
                DomainRules.isAllowed(anchor: "https://example.com/", candidate: candidate),
                candidate
            )
            XCTAssertFalse(DomainRules.isHTTP(candidate), candidate)
        }
    }

    func testHttpAndHttpsBothCount() {
        // The lock is about the domain; cleartext is refused a layer lower, by
        // App Transport Security.
        XCTAssertTrue(DomainRules.isHTTP("http://example.com/"))
        XCTAssertTrue(DomainRules.isHTTP("https://example.com/"))
        XCTAssertTrue(DomainRules.isHTTP("HTTPS://example.com/"))
    }

    func testNothingIsAllowedWithoutAnAnchor() {
        // Before the first app is selected there is no anchor, and a navigation
        // that arrives then must not slip through.
        XCTAssertFalse(DomainRules.isAllowed(anchor: nil, candidate: "https://example.com/"))
        XCTAssertFalse(DomainRules.isAllowed(anchor: "", candidate: "https://example.com/"))
        XCTAssertFalse(DomainRules.isAllowed(anchor: "https://example.com/", candidate: nil))
    }

    func testHostExtraction() {
        XCTAssertEqual("example.com", DomainRules.host(of: "https://www.example.com/a/b?c=1"))
        XCTAssertEqual("raspberrypi", DomainRules.host(of: "https://raspberrypi/podcaster"))
        XCTAssertEqual("example.com", DomainRules.host(of: "https://example.com:8443/"))
        XCTAssertNil(DomainRules.host(of: nil))
        XCTAssertNil(DomainRules.host(of: "   "))
        XCTAssertNil(DomainRules.host(of: "not a url"))
    }

    func testABareHostnameOnALocalNetworkWorks() {
        // The podcaster variant is pinned to https://raspberrypi, which has no dot
        // in it; a host parser that insisted on one would lock that build out of
        // its own page.
        XCTAssertTrue(DomainRules.isAllowed(
            anchor: "https://raspberrypi/podcaster",
            candidate: "https://raspberrypi/podcaster/episode/1"
        ))
        XCTAssertFalse(DomainRules.isAllowed(
            anchor: "https://raspberrypi/podcaster", candidate: "https://elsewhere/"))
    }
}
