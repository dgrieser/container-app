import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * The built-in symbols an app variant can pick for its launcher icon
 * (`icon.glyph` in `app-variants.yaml`).
 *
 * The symbols themselves live in `app-icons/glyphs.yaml` at the repository root
 * rather than in this file, because the iOS generator draws the same icons from
 * the same strings — a 24x24 `pathData` is SVG path data unchanged. Duplicating
 * seventeen long path strings across two languages was the most drift-prone part
 * of having two platforms, so there is one copy and both sides read it.
 *
 * [GenerateLauncherIconsTask] scales and centres a glyph inside the 108x108
 * adaptive-icon canvas. A variant that needs something else points `icon.vector`
 * at its own vector drawable instead.
 */
class LauncherGlyphs private constructor(private val paths: Map<String, String>) {

    /** Every glyph name that may appear as `icon.glyph`, in documentation order. */
    val names: List<String> get() = paths.keys.toList()

    fun pathData(name: String): String? = paths[name]

    companion object {
        /** The default glyph, used when a variant declares no icon at all. */
        const val DEFAULT = "container"

        /** Where the symbols live, relative to the repository root. */
        const val FILE = "app-icons/glyphs.yaml"

        /** Only the characters an SVG / vector-drawable path may be made of. */
        private val PATH_DATA = Regex("[MmLlHhVvCcSsQqTtAaZz0-9.,\\- ]+")

        /** Reads the symbols shared by both platforms out of a checkout. */
        fun load(repositoryRoot: File): LauncherGlyphs = loadFile(File(repositoryRoot, FILE))

        fun loadFile(file: File): LauncherGlyphs {
            if (!file.isFile) {
                error("Missing ${file.path}: it holds the built-in launcher symbols.")
            }
            val root = Yaml().load<Any?>(file.readText())
                ?: error("${file.name} is empty; it must declare at least one glyph.")
            val map = root as? Map<*, *>
                ?: error("${file.name}: expected a mapping of glyph name to path data.")
            if (map.isEmpty()) error("${file.name} declares no glyphs.")

            val paths = linkedMapOf<String, String>()
            map.forEach { (rawName, rawData) ->
                val name = rawName.toString()
                val data = rawData?.toString()?.trim().orEmpty()
                // A glyph that is not path data would otherwise reach the
                // generated drawable verbatim and fail at aapt time, or worse,
                // render as nothing at all.
                if (data.isEmpty() || !PATH_DATA.matches(data)) {
                    error("${file.name}: glyph `$name` is not path data (`$data`).")
                }
                paths[name] = data
            }
            if (DEFAULT !in paths) {
                error("${file.name}: the default glyph `$DEFAULT` is missing.")
            }
            return LauncherGlyphs(paths)
        }
    }
}
