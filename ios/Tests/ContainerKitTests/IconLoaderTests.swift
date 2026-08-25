import UIKit
import XCTest

@testable import ContainerKit

/// The menu icons: small, optional, and never allowed to be held at full size.
final class IconLoaderTests: XCTestCase {

    func testAnOversizedIconIsShrunk() throws {
        let image = try XCTUnwrap(IconLoader.downscale(png(size: 512)))
        // `UIImage(data:)` decodes lazily, so an oversized icon would otherwise be
        // kept at full resolution for as long as the menu is open.
        XCTAssertEqual(IconLoader.maximumDimension, max(image.size.width, image.size.height))
    }

    func testASmallIconIsLeftAlone() throws {
        let image = try XCTUnwrap(IconLoader.downscale(png(size: 64)))
        XCTAssertEqual(64, max(image.size.width, image.size.height))
    }

    func testANonRectangularIconKeepsItsAspectRatio() throws {
        let image = try XCTUnwrap(IconLoader.downscale(png(width: 800, height: 400)))
        XCTAssertEqual(IconLoader.maximumDimension, image.size.width)
        XCTAssertEqual(IconLoader.maximumDimension / 2, image.size.height, accuracy: 1)
    }

    func testSomethingThatIsNotAnImageIsRefused() {
        // A 404 page or an HTML error document, which is what a mistyped icon URL
        // usually returns. The menu shows the placeholder instead.
        XCTAssertNil(IconLoader.downscale(Data("<html>not found</html>".utf8)))
        XCTAssertNil(IconLoader.downscale(Data()))
    }

    private func png(size: CGFloat) -> Data {
        png(width: size, height: size)
    }

    private func png(width: CGFloat, height: CGFloat) -> Data {
        let renderer = UIGraphicsImageRenderer(
            size: CGSize(width: width, height: height),
            format: {
                let format = UIGraphicsImageRendererFormat.default()
                format.scale = 1
                return format
            }()
        )
        return renderer.pngData { context in
            UIColor.systemBlue.setFill()
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        }
    }
}
