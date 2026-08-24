/**
 * How much of Android's own UI a variant leaves on screen (`screenMode` in
 * `app-variants.yaml`).
 *
 * The build only has to know which names are valid — what each one does lives in
 * the app's `ScreenMode` enum, which uses exactly these ids. A test keeps the
 * two lists in sync, so a mode added on one side cannot be forgotten on the
 * other.
 */
object ScreenModes {

    /** No system bars at all: the page owns the whole display. */
    const val DEFAULT = "fullscreen"

    val names: List<String> = listOf(
        DEFAULT,
        // Status bar visible (clock, battery, notifications), no navigation bar.
        "statusBar",
        // Navigation bar visible, no status bar.
        "navigationBar",
        // Both bars visible, like an ordinary app.
        "systemBars"
    )

    fun isKnown(name: String): Boolean = name in names
}
