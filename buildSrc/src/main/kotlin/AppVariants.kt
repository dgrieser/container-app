import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * How a variant's launcher icon is drawn. Either [glyph] (a name from
 * [LauncherGlyphs]) or [vector] (a vector drawable inside the repository) is
 * set — never both.
 */
data class LauncherIcon(
    val glyph: String?,
    val vector: String?,
    val background: String,
    val tint: String
)

/**
 * One installable flavour of the container app, as declared in
 * `app-variants.yaml`. Every variant becomes its own product flavour, its own
 * `applicationId` and therefore its own APK that installs alongside the others.
 */
data class AppVariant(
    /** Internal name: the Gradle flavour, and the applicationId suffix. */
    val id: String,
    /** Launcher label (`app_name`). */
    val name: String,
    val applicationId: String,
    val versionNameSuffix: String?,
    /**
     * The kiosk.json this variant reads. Empty when the variant ships without
     * one, which [defaultKioskPath] then has to make up for by being an
     * absolute URL.
     */
    val configUrl: String,
    /** Page this variant opens by default; see README for the matching rules. */
    val defaultKioskPath: String,
    /** When false, the admin menu opens without a PIN and none is set up. */
    val requirePin: Boolean,
    /** When false, no hamburger button is shown and the app is single-page. */
    val showMenu: Boolean,
    /**
     * Which system bars stay visible over the page: one of [ScreenModes.names].
     * Only the starting point — the admin menu can change it per device.
     */
    val screenMode: String,
    /** Initial state of the admin's "allow unverified certificates" switch. */
    val allowUnverifiedSsl: Boolean,
    /**
     * When true, a link leaving the anchored domain is handed to the device's
     * default handler (usually the browser) instead of being refused.
     */
    val allowExternalNavigation: Boolean,
    val isDefault: Boolean,
    val icon: LauncherIcon
) {
    /** `container` -> `Container`, for task names like `assembleContainerRelease`. */
    val gradleName: String get() = id.replaceFirstChar(Char::uppercaseChar)
}

/**
 * Reads `app-variants.yaml`. The parser is deliberately strict: unknown or
 * misspelled keys fail the build instead of being silently ignored, because a
 * typo here would otherwise ship an APK with the wrong name, icon or kiosk URL.
 */
object AppVariants {

    private val ID_PATTERN = Regex("[a-z][a-z0-9]*")
    private val COLOR_PATTERN = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")
    private val RESERVED_IDS = setOf("main", "test", "androidTest", "debug", "release")

    private val TOP_LEVEL_KEYS = setOf("applicationId", "variants")
    private val VARIANT_KEYS = setOf(
        "id", "name", "applicationId", "versionNameSuffix", "configUrl",
        "defaultKioskPath", "requirePin", "showMenu", "screenMode",
        "allowUnverifiedSsl", "allowExternalNavigation", "default", "icon"
    )
    private val ICON_KEYS = setOf("glyph", "vector", "background", "tint")

    private const val DEFAULT_BACKGROUND = "#1F6FEB"
    private const val DEFAULT_TINT = "#FFFFFF"

    fun load(file: File, fallbackApplicationId: String): List<AppVariant> {
        if (!file.isFile) {
            error("Missing ${file.name}: it declares the app variants (name, icon, kiosk URL) to build.")
        }
        val root = Yaml().load<Any?>(file.readText())
            ?: error("${file.name} is empty; it must declare at least one entry under `variants:`.")
        val top = root.asMap(file.name, "the document root")
        top.checkKeys(file.name, "the document root", TOP_LEVEL_KEYS)

        val baseApplicationId = top.string("applicationId") ?: fallbackApplicationId
        val rawVariants = top["variants"] as? List<*>
            ?: error("${file.name} must contain a `variants:` list with at least one entry.")
        if (rawVariants.isEmpty()) {
            error("${file.name}: `variants:` is empty; at least one variant is required.")
        }

        val variants = rawVariants.mapIndexed { index, raw ->
            parseVariant(raw, "${file.name} variants[$index]", file.name, baseApplicationId)
        }

        variants.groupBy { it.id }.forEach { (id, group) ->
            if (group.size > 1) error("${file.name}: duplicate variant id `$id`.")
        }
        variants.groupBy { it.applicationId }.forEach { (appId, group) ->
            if (group.size > 1) {
                error(
                    "${file.name}: variants ${group.joinToString { "`${it.id}`" }} share the applicationId " +
                        "`$appId`, so they could not be installed side by side."
                )
            }
        }
        if (variants.count { it.isDefault } > 1) {
            error("${file.name}: only one variant may set `default: true`.")
        }
        return variants
    }

    private fun parseVariant(
        raw: Any?,
        where: String,
        fileName: String,
        baseApplicationId: String
    ): AppVariant {
        val map = raw.asMap(fileName, where)
        map.checkKeys(fileName, where, VARIANT_KEYS)

        val id = map.string("id") ?: error("$where: `id` is required (the internal name of the variant).")
        if (!ID_PATTERN.matches(id)) {
            error("$where: id `$id` must match ${ID_PATTERN.pattern} — it becomes a Gradle flavour name.")
        }
        if (id in RESERVED_IDS) error("$where: id `$id` is reserved by the Android build.")

        val name = map.string("name") ?: error("$where: `name` is required (the launcher label).")
        if (name.any { it in "<>&" }) {
            error("$where: name `$name` must not contain `<`, `>` or `&`.")
        }

        val isDefault = map.boolean("default") ?: false
        val applicationId = map.string("applicationId")
            ?: if (isDefault) baseApplicationId else "$baseApplicationId.$id"

        val defaultKioskPath = map.string("defaultKioskPath").orEmpty()

        // A variant needs somewhere to get its page from: either a kiosk.json to
        // read, or a defaultKioskPath that is a complete URL on its own. Only a
        // relative path or an app name has to be resolved against a config file.
        val configUrl = map.string("configUrl")
        if (configUrl == null) {
            if (!isAbsoluteHttpUrl(defaultKioskPath)) {
                error(
                    "$where: `configUrl` is required unless `defaultKioskPath` is an absolute " +
                        "http(s) URL — without either the variant has no page to open."
                )
            }
        } else if (!isAbsoluteHttpUrl(configUrl)) {
            error("$where: configUrl `$configUrl` must be an absolute http(s) URL.")
        }

        val screenMode = map.string("screenMode") ?: ScreenModes.DEFAULT
        if (!ScreenModes.isKnown(screenMode)) {
            error(
                "$where: unknown screenMode `$screenMode`. " +
                    "Available: ${ScreenModes.names.joinToString()}."
            )
        }

        return AppVariant(
            id = id,
            name = name,
            applicationId = applicationId,
            versionNameSuffix = map.string("versionNameSuffix"),
            configUrl = configUrl.orEmpty(),
            defaultKioskPath = defaultKioskPath,
            requirePin = map.boolean("requirePin") ?: true,
            showMenu = map.boolean("showMenu") ?: true,
            screenMode = screenMode,
            allowUnverifiedSsl = map.boolean("allowUnverifiedSsl") ?: false,
            allowExternalNavigation = map.boolean("allowExternalNavigation") ?: false,
            isDefault = isDefault,
            icon = parseIcon(map["icon"], "$where icon", fileName)
        )
    }

    private fun parseIcon(raw: Any?, where: String, fileName: String): LauncherIcon {
        if (raw == null) {
            return LauncherIcon(LauncherGlyphs.DEFAULT, null, DEFAULT_BACKGROUND, DEFAULT_TINT)
        }
        val map = raw.asMap(fileName, where)
        map.checkKeys(fileName, where, ICON_KEYS)

        val glyph = map.string("glyph")
        val vector = map.string("vector")
        if (glyph != null && vector != null) {
            error("$where: set either `glyph` or `vector`, not both.")
        }
        if (glyph != null && LauncherGlyphs.pathData(glyph) == null) {
            error("$where: unknown glyph `$glyph`. Available: ${LauncherGlyphs.names.joinToString()}.")
        }

        val background = map.string("background") ?: DEFAULT_BACKGROUND
        val tint = map.string("tint") ?: DEFAULT_TINT
        listOf("background" to background, "tint" to tint).forEach { (key, value) ->
            if (!COLOR_PATTERN.matches(value)) {
                error("$where: `$key` must be #RRGGBB or #AARRGGBB, was `$value`.")
            }
        }
        return LauncherIcon(
            glyph = if (vector == null) glyph ?: LauncherGlyphs.DEFAULT else null,
            vector = vector,
            background = background,
            tint = tint
        )
    }

    private fun isAbsoluteHttpUrl(value: String): Boolean =
        value.startsWith("https://") || value.startsWith("http://")

    // --- tiny YAML helpers -------------------------------------------------

    private fun Any?.asMap(fileName: String, where: String): Map<*, *> =
        this as? Map<*, *> ?: error("$fileName: expected a mapping at $where, was ${describe(this)}.")

    private fun Map<*, *>.checkKeys(fileName: String, where: String, allowed: Set<String>) {
        val unknown = keys.map { it.toString() }.filterNot { it in allowed }
        if (unknown.isNotEmpty()) {
            error(
                "$fileName: unknown key(s) ${unknown.joinToString { "`$it`" }} at $where. " +
                    "Allowed: ${allowed.sorted().joinToString()}."
            )
        }
    }

    private fun Map<*, *>.string(key: String): String? {
        val value = this[key] ?: return null
        return value.toString().trim().ifEmpty { null }
    }

    private fun Map<*, *>.boolean(key: String): Boolean? = when (val value = this[key]) {
        null -> null
        is Boolean -> value
        else -> error("Expected true/false for `$key`, was `$value`.")
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "empty"
        is List<*> -> "a list"
        else -> "`$value`"
    }
}

/** Renders [value] as a Java string literal for `buildConfigField`. */
fun javaStringLiteral(value: String): String =
    '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"'
