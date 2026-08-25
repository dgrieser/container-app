import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Keeps this parser and `app-variants.schema.json` saying the same thing.
 *
 * The schema is the normative source for the key sets, the value types, the
 * enums and the defaults, because the iOS generator parses `app-variants.yaml`
 * in Python and drives itself from the schema directly. Kotlin keeps its own
 * hand-written parser — no new runtime dependency, and the error messages stay
 * as specific as they are — so this test is what stops the two descriptions of
 * the same file from drifting apart.
 *
 * YAML is a superset of JSON, so snakeyaml (already a buildSrc dependency) reads
 * the schema with nothing extra added to the build.
 */
class SchemaSyncTest {

    @Test
    fun `the schema declares the keys this parser allows`() {
        assertEquals("document root", AppVariants.TOP_LEVEL_KEYS, keysOf(schema()))
        assertEquals("variant", AppVariants.VARIANT_KEYS, keysOf(variantSchema()))
        assertEquals("icon", AppVariants.ICON_KEYS, keysOf(propertyOf(variantSchema(), "icon")))
        assertEquals(
            "barColor",
            AppVariants.BAR_COLOR_KEYS,
            keysOf(propertyOf(variantSchema(), "barColor"))
        )
    }

    @Test
    fun `every mapping in the schema is closed`() {
        // `additionalProperties: false` *is* the "a typo cannot silently ship an
        // APK with the wrong kiosk URL" rule. A mapping that forgot it would let
        // the Python parser accept what this one rejects.
        listOf(
            "document root" to schema(),
            "variant" to variantSchema(),
            "icon" to propertyOf(variantSchema(), "icon"),
            "barColor" to propertyOf(variantSchema(), "barColor")
        ).forEach { (where, node) ->
            assertEquals("$where must set additionalProperties: false", false, node["additionalProperties"])
        }
    }

    @Test
    fun `the schema declares the screen modes the build accepts`() {
        val declared = propertyOf(variantSchema(), "screenMode")["enum"] as? List<*>
        assertEquals(ScreenModes.names, declared?.map { it.toString() })
    }

    @Test
    fun `the schema declares this parser's defaults`() {
        val variant = variantSchema()
        assertEquals(ScreenModes.DEFAULT, defaultOf(variant, "screenMode"))
        assertEquals(AppVariants.DEFAULT_REQUIRE_PIN, defaultOf(variant, "requirePin"))
        assertEquals(AppVariants.DEFAULT_SHOW_MENU, defaultOf(variant, "showMenu"))
        assertEquals(
            AppVariants.DEFAULT_ALLOW_UNVERIFIED_SSL,
            defaultOf(variant, "allowUnverifiedSsl")
        )
        assertEquals(
            AppVariants.DEFAULT_ALLOW_EXTERNAL_NAVIGATION,
            defaultOf(variant, "allowExternalNavigation")
        )
        assertEquals(AppVariants.DEFAULT_ALLOW_LOCATION, defaultOf(variant, "allowLocation"))
        assertEquals(AppVariants.DEFAULT_IS_DEFAULT, defaultOf(variant, "default"))

        val icon = propertyOf(variant, "icon")
        assertEquals(LauncherGlyphs.DEFAULT, defaultOf(icon, "glyph"))
        assertEquals(AppVariants.DEFAULT_BACKGROUND, defaultOf(icon, "background"))
        assertEquals(AppVariants.DEFAULT_TINT, defaultOf(icon, "tint"))

        val barColor = propertyOf(variant, "barColor")
        assertEquals(AppVariants.DEFAULT_BAR_COLOR, defaultOf(barColor, "light"))
        assertEquals(AppVariants.DEFAULT_BAR_COLOR, defaultOf(barColor, "dark"))
    }

    @Test
    fun `the schema declares this parser's patterns`() {
        assertEquals(
            "^${AppVariants.ID_PATTERN.pattern}$",
            propertyOf(variantSchema(), "id")["pattern"]
        )
        assertEquals(
            "^${AppVariants.COLOR_PATTERN.pattern}$",
            (definitionOf("color"))["pattern"]
        )
    }

    /**
     * The glyph names are deliberately not repeated in the schema: they live once
     * in `app-icons/glyphs.yaml`, and both parsers validate against that file. A
     * future edit that "helpfully" adds an enum here would create exactly the
     * duplication moving the paths out of Kotlin removed.
     */
    @Test
    fun `the schema does not duplicate the glyph names`() {
        val glyph = propertyOf(variantSchema(), "icon")
        assertTrue(
            "icon.glyph must not carry an enum; app-icons/glyphs.yaml is the list",
            (propertyOf(glyph, "glyph"))["enum"] == null
        )
        assertTrue("expected glyphs to load", TestPaths.glyphs().names.isNotEmpty())
    }

    // --- reading the schema ------------------------------------------------

    private fun schema(): Map<*, *> {
        val file = File(TestPaths.repoRoot, "app-variants.schema.json")
        assertTrue("missing ${file.path}", file.isFile)
        return Yaml().load<Any?>(file.readText()) as Map<*, *>
    }

    private fun variantSchema(): Map<*, *> = definitionOf("variant")

    private fun definitionOf(name: String): Map<*, *> =
        (schema()["\$defs"] as Map<*, *>)[name] as Map<*, *>

    private fun propertiesOf(node: Map<*, *>): Map<*, *> = node["properties"] as Map<*, *>

    private fun keysOf(node: Map<*, *>): Set<String> =
        propertiesOf(node).keys.map { it.toString() }.toSet()

    private fun propertyOf(node: Map<*, *>, key: String): Map<*, *> {
        val property = propertiesOf(node)[key] as? Map<*, *>
            ?: throw AssertionError("the schema declares no `$key`")
        // A `$ref`'d property (the colours) carries its default alongside the ref,
        // so merge the definition in rather than losing its pattern.
        val ref = property["\$ref"]?.toString() ?: return property
        return definitionOf(ref.substringAfterLast('/')) + property
    }

    private fun defaultOf(node: Map<*, *>, key: String): Any? = propertyOf(node, key)["default"]
}
