package de.davidgrieser.container

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * What the system bars a [ScreenMode] keeps on screen are painted in.
 *
 * A variant declares one colour per system theme (`barColor.light` and
 * `barColor.dark` in `app-variants.yaml`), because a strip that matches the page
 * in daylight rarely matches it at night. Both default to the window background,
 * which is what those bars showed through before they could be coloured.
 *
 * The choice follows the device's dark-mode setting. Switching it recreates the
 * activity — `uiMode` is not among the configuration changes the activity
 * handles itself — so the other colour is picked up on the way back in.
 */
object SystemBarColors {

    /**
     * Relative luminance above which a bar needs dark icons drawn on it: the
     * point where black on the colour reads better than white, rather than the
     * naive midpoint, which calls mid-greys darker than they look.
     */
    private const val DARK_ICON_THRESHOLD = 0.179

    /** The colour in force for the theme the device is currently in. */
    fun current(context: Context): Int {
        val declared = if (isNightMode(context)) {
            BuildConfig.BAR_COLOR_DARK
        } else {
            BuildConfig.BAR_COLOR_LIGHT
        }
        // The build has already rejected anything unparseable; falling back to
        // transparent merely leaves the window background showing, as it did
        // before bars could be coloured.
        return runCatching { Color.parseColor(declared) }.getOrDefault(Color.TRANSPARENT)
    }

    /**
     * Whether a bar painted [barColor] is light enough to need dark icons on it.
     * A translucent colour is judged as it will actually look — blended over the
     * window background it is drawn on.
     */
    fun needsDarkIcons(context: Context, barColor: Int): Boolean {
        val onWindow = ColorUtils.compositeColors(
            barColor,
            ContextCompat.getColor(context, R.color.background)
        )
        return ColorUtils.calculateLuminance(onWindow) > DARK_ICON_THRESHOLD
    }

    private fun isNightMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
