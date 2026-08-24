package de.davidgrieser.container

import androidx.annotation.StringRes
import androidx.core.view.WindowInsetsCompat

/**
 * How much of Android's own UI stays on screen over the page.
 *
 * A build starts out in the mode its variant declares (`screenMode` in
 * `app-variants.yaml`, reaching the app as [BuildConfig.SCREEN_MODE]) and the
 * admin menu can change it per device, so one kiosk can run edge to edge while
 * another keeps the clock and the battery in view.
 *
 * Every [id] here also has to be listed in `ScreenModes` in `buildSrc`, which is
 * what lets the build reject a misspelled mode; a test compares the two lists.
 */
enum class ScreenMode(
    /** Name used in `app-variants.yaml` and for the persisted preference. */
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    /**
     * Bars this mode keeps visible, as a [WindowInsetsCompat.Type] mask. Every
     * system bar outside the mask is hidden (and can still be swiped in
     * transiently).
     */
    val visibleBars: Int
) {
    FULLSCREEN(
        "fullscreen",
        R.string.screen_mode_fullscreen,
        R.string.screen_mode_fullscreen_hint,
        0
    ),
    STATUS_BAR(
        "statusBar",
        R.string.screen_mode_status_bar,
        R.string.screen_mode_status_bar_hint,
        WindowInsetsCompat.Type.statusBars()
    ),
    NAVIGATION_BAR(
        "navigationBar",
        R.string.screen_mode_navigation_bar,
        R.string.screen_mode_navigation_bar_hint,
        WindowInsetsCompat.Type.navigationBars()
    ),
    SYSTEM_BARS(
        "systemBars",
        R.string.screen_mode_system_bars,
        R.string.screen_mode_system_bars_hint,
        WindowInsetsCompat.Type.systemBars()
    );

    /** Everything this mode does not want on screen. */
    val hiddenBars: Int
        get() = WindowInsetsCompat.Type.systemBars() and visibleBars.inv()

    val showsStatusBar: Boolean
        get() = visibleBars and WindowInsetsCompat.Type.statusBars() != 0

    val showsNavigationBar: Boolean
        get() = visibleBars and WindowInsetsCompat.Type.navigationBars() != 0

    companion object {
        /** What a variant gets when it declares no `screenMode`: no bars at all. */
        val DEFAULT = FULLSCREEN

        /**
         * Resolves a stored or built-in mode name. Anything unknown — an older
         * preference, a build that predates a renamed mode — falls back to
         * [DEFAULT] rather than leaving the app without a mode.
         */
        fun fromId(id: String?): ScreenMode {
            val wanted = id?.trim().orEmpty()
            return entries.firstOrNull { it.id.equals(wanted, ignoreCase = true) } ?: DEFAULT
        }
    }
}
