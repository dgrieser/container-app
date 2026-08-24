import Foundation

/// What the status bar a ``ScreenMode`` keeps on screen is painted in.
///
/// A variant declares one colour per system theme (`barColor.light` and
/// `barColor.dark` in `app-variants.yaml`), because a strip that matches the
/// page in daylight rarely matches it at night. Both default to white, which is
/// the window background.
///
/// iOS has no status-bar background to set, so the app paints its own view
/// behind the top safe area; only the *content* of the bar — the clock and the
/// battery — is chosen by the system, from ``needsDarkIcons``.
public struct BarColor: Equatable, Sendable {

    /// Relative luminance above which a bar needs dark icons drawn on it: the
    /// point where black on the colour reads better than white, rather than the
    /// naive midpoint, which calls mid-greys darker than they look. The same
    /// threshold as `SystemBarColors` on Android.
    public static let darkIconThreshold = 0.179

    /// `0xAARRGGBB`, already validated and parsed by the generator, so there is
    /// no unparseable case to fall back from at runtime.
    public let argb: UInt32

    public init(argb: UInt32) {
        self.argb = argb
    }

    public var alpha: Double { Double((argb >> 24) & 0xFF) / 255.0 }
    public var red: Double { Double((argb >> 16) & 0xFF) / 255.0 }
    public var green: Double { Double((argb >> 8) & 0xFF) / 255.0 }
    public var blue: Double { Double(argb & 0xFF) / 255.0 }

    /// This colour composited over `background`, so a translucent bar is judged
    /// as it will actually look rather than as it was written.
    public func composited(over background: BarColor) -> BarColor {
        let a = alpha
        guard a < 1 else { return self }
        func mix(_ top: Double, _ bottom: Double) -> UInt32 {
            UInt32((top * a + bottom * (1 - a)) * 255.0 + 0.5)
        }
        let composited = (UInt32(0xFF) << 24)
            | (mix(red, background.red) << 16)
            | (mix(green, background.green) << 8)
            | mix(blue, background.blue)
        return BarColor(argb: composited)
    }

    /// WCAG relative luminance.
    public var luminance: Double {
        func linear(_ channel: Double) -> Double {
            channel <= 0.03928 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }

    /// Whether a bar painted this colour is light enough to need dark icons.
    public func needsDarkIcons(over background: BarColor = .windowBackground) -> Bool {
        composited(over: background).luminance > BarColor.darkIconThreshold
    }

    /// The window background, i.e. what a visible bar showed through before it
    /// could be coloured.
    public static let windowBackground = BarColor(argb: 0xFF_FF_FF_FF)
}
