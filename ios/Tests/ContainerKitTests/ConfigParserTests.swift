import XCTest

@testable import ContainerKit

/// The remote configuration is the one input the app does not control, so the
/// parser has to be forgiving about the file and strict about the URLs in it.
final class ConfigParserTests: XCTestCase {

    func testTheDocumentedShapeParses() throws {
        let config = try ConfigParser.parse("""
        {"apps": [
          {"name": "Portal", "icon": "https://example.com/p.png", "url": "https://portal.example.com/"}
        ]}
        """)
        XCTAssertEqual(1, config.apps.count)
        XCTAssertEqual("Portal", config.apps[0].name)
        XCTAssertEqual("https://example.com/p.png", config.apps[0].iconURL)
        XCTAssertEqual("https://portal.example.com/", config.apps[0].url)
    }

    func testABareArrayIsAcceptedToo() throws {
        // Documented as accepted "for convenience", so it has to keep working.
        let config = try ConfigParser.parse("""
        [{"name": "A", "url": "https://a.example.com/"}]
        """)
        XCTAssertEqual(["A"], config.apps.map(\.name))
    }

    func testAMissingNameFallsBackToTheHost() throws {
        let config = try ConfigParser.parse("""
        {"apps": [{"url": "https://www.portal.example.com/x"}]}
        """)
        // `www.` is stripped, as everywhere else.
        XCTAssertEqual("portal.example.com", config.apps[0].name)
    }

    func testAnEntryWithoutAUsablePageIsDropped() throws {
        let config = try ConfigParser.parse("""
        {"apps": [
          {"name": "No URL"},
          {"name": "Scheme", "url": "myapp://open"},
          {"name": "Mail", "url": "mailto:a@example.com"},
          {"name": "Blank", "url": "   "},
          {"name": "Fine", "url": "https://fine.example.com/"}
        ]}
        """)
        // One bad entry on the server must not take the whole kiosk down.
        XCTAssertEqual(["Fine"], config.apps.map(\.name))
    }

    func testAJsonNullIconBecomesNoIcon() throws {
        // `"null"` really does turn up: it is what a JSON null becomes once it has
        // been through org.json's optString, so a file written against the Android
        // app can contain it literally.
        let config = try ConfigParser.parse("""
        {"apps": [
          {"name": "A", "icon": null, "url": "https://a.example.com/"},
          {"name": "B", "icon": "null", "url": "https://b.example.com/"},
          {"name": "C", "icon": "", "url": "https://c.example.com/"}
        ]}
        """)
        XCTAssertEqual([nil, nil, nil], config.apps.map(\.iconURL))
    }

    func testNamesAndUrlsAreTrimmed() throws {
        let config = try ConfigParser.parse("""
        {"apps": [{"name": "  Padded  ", "url": "  https://a.example.com/  "}]}
        """)
        XCTAssertEqual("Padded", config.apps[0].name)
        XCTAssertEqual("https://a.example.com/", config.apps[0].url)
    }

    func testAnEmptyOrAppLessDocumentIsNotAnError() throws {
        // "No apps are configured yet" is a state the app shows, not a failure it
        // reports: an empty list is a valid answer from the server.
        XCTAssertTrue(try ConfigParser.parse("{}").isEmpty)
        XCTAssertTrue(try ConfigParser.parse("{\"apps\": []}").isEmpty)
        XCTAssertTrue(try ConfigParser.parse("[]").isEmpty)
    }

    func testSomethingThatIsNotJsonThrows() {
        for body in ["", "   ", "not json", "<html>nope</html>", "42"] {
            XCTAssertThrowsError(try ConfigParser.parse(body), body)
        }
    }

    func testANumericNameIsCoercedRatherThanDropped() throws {
        // org.json's optString turns a number into its text; a config with
        // `"name": 2024` should read the same on both platforms.
        let config = try ConfigParser.parse("""
        {"apps": [{"name": 2024, "url": "https://a.example.com/"}]}
        """)
        XCTAssertEqual("2024", config.apps[0].name)
    }
}
