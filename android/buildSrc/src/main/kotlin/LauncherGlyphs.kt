/**
 * The built-in symbols an app variant can pick for its launcher icon
 * (`icon.glyph` in `app-variants.yaml`).
 *
 * Every entry is the `pathData` of a Material-style icon drawn in a 24x24
 * viewport; [GenerateLauncherIconsTask] scales and centres it inside the
 * 108x108 adaptive-icon canvas. A variant that needs something else points
 * `icon.vector` at its own vector drawable instead.
 */
object LauncherGlyphs {

    private val paths: Map<String, String> = linkedMapOf(
        // The original Container icon: a framed bar chart.
        "container" to "M19,3H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3z" +
            "M9,17H7v-7h2V17zM13,17h-2V7h2V17zM17,17h-2v-4h2V17z",
        // Four rounded bars of rising height, like a level meter.
        "equalizer" to "M4.32,12h1.44a0.96,0.96 0 0,1 0.96,0.96v5.28a0.96,0.96 0 0,1 -0.96,0.96h-1.44" +
            "a0.96,0.96 0 0,1 -0.96,-0.96v-5.28a0.96,0.96 0 0,1 0.96,-0.96z" +
            "M9.12,7.2h1.44a0.96,0.96 0 0,1 0.96,0.96v10.08a0.96,0.96 0 0,1 -0.96,0.96h-1.44" +
            "a0.96,0.96 0 0,1 -0.96,-0.96v-10.08a0.96,0.96 0 0,1 0.96,-0.96z" +
            "M13.92,10.08h1.44a0.96,0.96 0 0,1 0.96,0.96v7.2a0.96,0.96 0 0,1 -0.96,0.96h-1.44" +
            "a0.96,0.96 0 0,1 -0.96,-0.96v-7.2a0.96,0.96 0 0,1 0.96,-0.96z" +
            "M18.72,5.28h1.44a0.96,0.96 0 0,1 0.96,0.96v12a0.96,0.96 0 0,1 -0.96,0.96h-1.44" +
            "a0.96,0.96 0 0,1 -0.96,-0.96v-12a0.96,0.96 0 0,1 0.96,-0.96z",
        "apps" to "M4,8h4V4H4v4zM10,20h4v-4h-4v4zM4,20h4v-4H4v4zM4,14h4v-4H4v4zM10,14h4v-4h-4v4z" +
            "M16,4v4h4V4h-4zM10,8h4V4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z",
        "dashboard" to "M3,13h8V3H3v10zM3,21h8v-6H3v6zM13,21h8V11h-8v10zM13,3v6h8V3h-8z",
        "list" to "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z",
        "menu" to "M3,18h18v-2H3V18zM3,13h18v-2H3V13zM3,6v2h18V6H3z",
        "home" to "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z",
        "monitor" to "M20,3H4c-1.1,0 -2,0.9 -2,2v11c0,1.1 0.9,2 2,2h4v2h8v-2h4c1.1,0 2,-0.9 2,-2V5C22,3.9 21.1,3 20,3z" +
            "M20,16H4V5h16V16z",
        "chat" to "M20,2H4C2.9,2 2,2.9 2,4v16l4,-4h14c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2z",
        "info" to "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z",
        "lock" to "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12" +
            "c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z" +
            "M15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z",
        "bolt" to "M7,2v11h3v9l7,-12h-4l4,-8z",
        "star" to "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z",
        "circle" to "M2,12A10,10 0 1,1 22,12A10,10 0 1,1 2,12Z",
        "square" to "M3,3h18v18H3z",
        "triangle" to "M12,2L22,20H2z",
        "diamond" to "M12,2L22,12 12,22 2,12z"
    )

    /** Every glyph name that may appear as `icon.glyph`, in documentation order. */
    val names: List<String> get() = paths.keys.toList()

    /** The default glyph, used when a variant declares no icon at all. */
    const val DEFAULT = "container"

    fun pathData(name: String): String? = paths[name]
}
