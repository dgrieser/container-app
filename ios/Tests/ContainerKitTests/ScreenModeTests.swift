import UIKit
import XCTest

@testable import ContainerKit

/// The four screen modes, and the appearance each one asks for.
///
/// The table in ``ScreenModePolicy`` is the whole of the hardest mapping in the
/// port, so it is asserted field by field rather than trusted to survive the next
/// tidy-up of the view controller.
final class ScreenModeTests: XCTestCase {

    func testTheModeIdsMatchTheSharedConfiguration() {
        // The same four names the Gradle build accepts, in the same order. A mode
        // renamed on one side and not the other would silently fall back to
        // fullscreen at runtime.
        XCTAssertEqual(
            ["fullscreen", "statusBar", "navigationBar", "systemBars"],
            ScreenMode.allCases.map(\.rawValue)
        )
    }

    func testAnUnknownOrMissingModeFallsBackToFullscreen() {
        // An older stored preference, or a build that predates a rename: the app
        // has to have *a* mode rather than none.
        for id in [nil, "", "   ", "cinema", "STATUSBAR "] as [String?] {
            XCTAssertEqual(ScreenMode.default, ScreenMode(id: id), id ?? "nil")
        }
        XCTAssertEqual(.fullscreen, ScreenMode.default)
    }

    func testAKnownModeResolvesCaseInsensitively() {
        XCTAssertEqual(.statusBar, ScreenMode(id: "statusBar"))
        XCTAssertEqual(.statusBar, ScreenMode(id: "statusbar"))
        XCTAssertEqual(.systemBars, ScreenMode(id: "systemBars"))
    }

    func testWhichBarsEachModeKeeps() {
        XCTAssertEqual([false, true, false, true], ScreenMode.allCases.map(\.showsStatusBar))
        XCTAssertEqual([false, false, true, true], ScreenMode.allCases.map(\.showsHomeIndicator))
    }

    func testTheAppearanceEachModeAsksFor() {
        let light = BarColor(argb: 0xFF_FF_FF_FF)
        let expected: [ScreenMode: (Bool, Bool, Bool, Bool)] = [
            // (statusBarHidden, homeIndicatorAutoHidden, safeAreaTop, safeAreaBottom)
            .fullscreen: (true, true, false, false),
            .statusBar: (false, true, true, false),
            .navigationBar: (true, false, false, true),
            .systemBars: (false, false, true, true),
        ]
        for (mode, want) in expected {
            let policy = ScreenModePolicy(mode: mode, barColor: light)
            XCTAssertEqual(want.0, policy.statusBarHidden, mode.rawValue)
            XCTAssertEqual(want.1, policy.homeIndicatorAutoHidden, mode.rawValue)
            XCTAssertEqual(want.2, policy.respectsSafeAreaTop, mode.rawValue)
            XCTAssertEqual(want.3, policy.respectsSafeAreaBottom, mode.rawValue)
        }
    }

    func testAModeThatDimsTheHomeIndicatorDefersTheBottomEdgeGesture() {
        XCTAssertEqual(
            [.bottom],
            ScreenModePolicy(mode: .fullscreen, barColor: .windowBackground)
                .deferringSystemGestureEdges
        )
        XCTAssertEqual(
            [],
            ScreenModePolicy(mode: .systemBars, barColor: .windowBackground)
                .deferringSystemGestureEdges
        )
    }

    func testTheStatusBarStyleFollowsTheBarColour() {
        XCTAssertEqual(
            .darkContent,
            ScreenModePolicy(mode: .statusBar, barColor: BarColor(argb: 0xFF_F4_F2_ED)).statusBarStyle
        )
        XCTAssertEqual(
            .lightContent,
            ScreenModePolicy(mode: .statusBar, barColor: BarColor(argb: 0xFF_0D_0E_11)).statusBarStyle
        )
    }
}

/// The bar colour, and the luminance threshold shared with Android.
final class BarColorTests: XCTestCase {

    func testTheThresholdIsAndroidS() {
        XCTAssertEqual(0.179, BarColor.darkIconThreshold, accuracy: 0.0001)
    }

    func testChannelsAreUnpackedFromArgb() {
        let color = BarColor(argb: 0xCC_11_22_33)
        XCTAssertEqual(0xCC / 255.0, color.alpha, accuracy: 0.001)
        XCTAssertEqual(0x11 / 255.0, color.red, accuracy: 0.001)
        XCTAssertEqual(0x22 / 255.0, color.green, accuracy: 0.001)
        XCTAssertEqual(0x33 / 255.0, color.blue, accuracy: 0.001)
    }

    func testTheGasolineVariantSColoursPickTheRightIcons() {
        // Read off the site itself: the near-white of its light rendering and the
        // near-black of its dark one. The whole point of the pair is that the bar
        // disappears into the page in both, so the icons have to flip with it.
        XCTAssertTrue(BarColor(argb: 0xFF_F4_F2_ED).needsDarkIcons())
        XCTAssertFalse(BarColor(argb: 0xFF_0D_0E_11).needsDarkIcons())
    }

    func testAMidGreyIsTreatedAsDarkEnoughForLightIcons() {
        // The reason for 0.179 rather than the naive midpoint: mid-greys read
        // darker than half, so white-on-grey beats black-on-grey well below 50%.
        XCTAssertFalse(BarColor(argb: 0xFF_80_80_80).needsDarkIcons())
    }

    func testATranslucentColourIsJudgedAsItWillLook() {
        // Half-transparent black over the white window background is a mid grey,
        // not a black, and the icons have to suit what is actually on screen.
        let translucent = BarColor(argb: 0x80_00_00_00)
        XCTAssertEqual(0xFF_80_80_80, translucent.composited(over: .windowBackground).argb)
    }

    func testAnOpaqueColourIsUnchangedByCompositing() {
        let opaque = BarColor(argb: 0xFF_12_34_56)
        XCTAssertEqual(opaque, opaque.composited(over: .windowBackground))
    }

    func testTheVariantPicksItsColourByTheme() {
        let variant = Fixtures.variant(
            barColorLight: BarColor(argb: 0xFF_F4_F2_ED),
            barColorDark: BarColor(argb: 0xFF_0D_0E_11)
        )
        XCTAssertEqual(0xFF_F4_F2_ED, variant.barColor(dark: false).argb)
        XCTAssertEqual(0xFF_0D_0E_11, variant.barColor(dark: true).argb)
    }
}
