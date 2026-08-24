import XCTest

@testable import ContainerKit

/// The TLS bypass, and the two limits on it that are deliberate.
final class InsecureTrustTests: XCTestCase {

    private let anchor = "https://internal.example.com/"

    func testTheSwitchOffLeavesEverythingToTheSystem() {
        XCTAssertEqual(
            .performDefaultHandling,
            InsecureTrust.decide(
                host: "internal.example.com",
                anchor: anchor,
                allowUnverifiedSSL: false,
                isServerTrust: true
            )
        )
    }

    func testTheSwitchOnTrustsTheAnchoredDomain() {
        for host in ["internal.example.com", "cdn.example.com", "example.com"] {
            XCTAssertEqual(
                .trust,
                InsecureTrust.decide(
                    host: host, anchor: anchor, allowUnverifiedSSL: true, isServerTrust: true),
                host
            )
        }
    }

    func testABrokenCertificateElsewhereIsStillRefused() {
        // The bypass follows the domain lock: an unrelated third-party host does
        // not get to present whatever it likes just because the switch is on.
        XCTAssertEqual(
            .reject(host: "tracker.other.com"),
            InsecureTrust.decide(
                host: "tracker.other.com",
                anchor: anchor,
                allowUnverifiedSSL: true,
                isServerTrust: true
            )
        )
    }

    func testOnlyServerTrustChallengesAreAnswered() {
        // A client certificate or an HTTP auth challenge is none of this code's
        // business, however the switch is set.
        XCTAssertEqual(
            .performDefaultHandling,
            InsecureTrust.decide(
                host: "internal.example.com",
                anchor: anchor,
                allowUnverifiedSSL: true,
                isServerTrust: false
            )
        )
    }

    func testABareHostnameOnALocalNetworkIsCoveredToo() {
        // The podcaster variant: a self-signed certificate on a host with no dot
        // in its name is the case the whole setting exists for.
        XCTAssertEqual(
            .trust,
            InsecureTrust.decide(
                host: "raspberrypi",
                anchor: "https://raspberrypi/podcaster",
                allowUnverifiedSSL: true,
                isServerTrust: true
            )
        )
    }

    func testAMissingHostIsRefusedRatherThanTrusted() {
        XCTAssertEqual(
            .reject(host: nil),
            InsecureTrust.decide(
                host: nil, anchor: anchor, allowUnverifiedSSL: true, isServerTrust: true)
        )
    }
}
