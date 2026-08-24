import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the parsing of `app-variants.yaml`. It runs as part of `buildSrc:build`,
 * i.e. on every Gradle invocation, so a broken variants file is reported before
 * an APK with the wrong name, icon or kiosk URL can be produced.
 */
class AppVariantsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the repository's variants file is valid`() {
        val file = File(repoRoot(), "app-variants.yaml")
        val variants = AppVariants.load(file, "de.davidgrieser.container")

        assertTrue("expected at least one variant", variants.isNotEmpty())
        assertEquals(1, variants.count { it.isDefault })
        variants.forEach { variant ->
            assertTrue(
                "${variant.id} needs a glyph or a vector",
                variant.icon.glyph != null || variant.icon.vector != null
            )
            variant.icon.vector?.let {
                val vector = File(repoRoot(), it)
                assertTrue("$it does not exist", vector.isFile)
                // It is copied into the APK as the icon foreground unchanged, so
                // a wrong viewport here would silently ship a misplaced icon.
                val root = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(vector).documentElement
                assertEquals("$it must be a vector drawable", "vector", root.tagName)
                listOf("viewportWidth", "viewportHeight").forEach { attribute ->
                    assertEquals(
                        "$it must declare android:$attribute=\"108\"",
                        "108",
                        root.getAttribute("android:$attribute")
                    )
                }
            }
            variant.icon.glyph?.let {
                assertTrue("unknown glyph $it", LauncherGlyphs.pathData(it) != null)
            }
        }
    }

    @Test
    fun `defaults apply and ids derive the applicationId`() {
        val variants = load(
            """
            variants:
              - id: container
                default: true
                name: Container
                configUrl: https://example.com/kiosk.json
              - id: portal
                name: Portal
                configUrl: https://example.com/portal.json
                requirePin: false
                showMenu: false
                defaultKioskPath: /dashboard
            """
        )

        val container = variants.first()
        assertEquals("de.davidgrieser.container", container.applicationId)
        assertEquals("Container", container.gradleName)
        assertTrue(container.requirePin)
        assertTrue(container.showMenu)
        assertEquals(ScreenModes.DEFAULT, container.screenMode)
        assertFalse(container.allowUnverifiedSsl)
        assertFalse(container.allowExternalNavigation)
        assertEquals("", container.defaultKioskPath)
        assertEquals(LauncherGlyphs.DEFAULT, container.icon.glyph)

        val portal = variants[1]
        assertEquals("de.davidgrieser.container.portal", portal.applicationId)
        assertFalse(portal.requirePin)
        assertFalse(portal.showMenu)
        assertEquals("/dashboard", portal.defaultKioskPath)
    }

    @Test
    fun `a variant pinned to an absolute page needs no configUrl`() {
        val variant = load(
            """
            variants:
              - id: pinned
                name: Pinned
                defaultKioskPath: https://raspberrypi/podcaster
                allowUnverifiedSsl: true
            """
        ).single()

        assertEquals("", variant.configUrl)
        assertEquals("https://raspberrypi/podcaster", variant.defaultKioskPath)
        assertTrue(variant.allowUnverifiedSsl)
    }

    @Test
    fun `leaving the domain lock stays opt-in per variant`() {
        val variants = load(
            """
            variants:
              - id: locked
                name: Locked
                defaultKioskPath: https://locked.example.com/
              - id: outbound
                name: Outbound
                defaultKioskPath: https://outbound.example.com/
                allowExternalNavigation: true
            """
        )

        assertFalse("off-domain links must be refused unless asked for", variants[0].allowExternalNavigation)
        assertTrue(variants[1].allowExternalNavigation)
    }

    @Test
    fun `a variant can keep some of the system bars on screen`() {
        val variants = load(
            """
            variants:
              - id: edgetoedge
                name: Edge
                defaultKioskPath: https://edge.example.com/
              - id: topbar
                name: Top bar
                defaultKioskPath: https://topbar.example.com/
                screenMode: statusBar
            """
        )

        assertEquals("fullscreen", variants[0].screenMode)
        assertEquals("statusBar", variants[1].screenMode)
    }

    /**
     * The build only validates the mode names; the app decides what each one
     * does. A mode added on one side and forgotten on the other would either be
     * rejected by the build or silently fall back to the default at runtime.
     */
    @Test
    fun `the screen modes match the app's ScreenMode enum`() {
        val source = File(
            repoRoot(),
            "app/src/main/java/de/davidgrieser/container/ScreenMode.kt"
        )
        assertTrue("${source.name} is missing", source.isFile)

        val inApp = Regex("""^\s{4}[A-Z_]+\($""", RegexOption.MULTILINE)
            .findAll(source.readText())
            .count()
        val ids = Regex(""""([a-zA-Z]+)",""")
            .findAll(source.readText())
            .map { it.groupValues[1] }
            .filter { ScreenModes.isKnown(it) }
            .toSet()

        assertEquals("every screen mode needs an enum entry", ScreenModes.names.size, inApp)
        assertEquals(ScreenModes.names.toSet(), ids)
    }

    @Test
    fun `misconfigurations fail the build`() {
        assertRejected("unknown key", """
            variants:
              - id: a
                name: A
                configUrl: https://example.com/k.json
                hamburger: false
        """)
        assertRejected("duplicate applicationId", """
            variants:
              - id: a
                name: A
                configUrl: https://example.com/k.json
                applicationId: com.example.same
              - id: b
                name: B
                configUrl: https://example.com/k.json
                applicationId: com.example.same
        """)
        assertRejected("invalid id", """
            variants:
              - id: My-App
                name: A
                configUrl: https://example.com/k.json
        """)
        assertRejected("missing configUrl", """
            variants:
              - id: a
                name: A
        """)
        assertRejected("relative defaultKioskPath without a configUrl", """
            variants:
              - id: a
                name: A
                defaultKioskPath: /dashboard
        """)
        assertRejected("non-boolean allowUnverifiedSsl", """
            variants:
              - id: a
                name: A
                configUrl: https://example.com/k.json
                allowUnverifiedSsl: yes please
        """)
        assertRejected("non-boolean allowExternalNavigation", """
            variants:
              - id: a
                name: A
                configUrl: https://example.com/k.json
                allowExternalNavigation: sometimes
        """)
        assertRejected("unknown screenMode", """
            variants:
              - id: a
                name: A
                configUrl: https://example.com/k.json
                screenMode: cinema
        """)
        assertRejected("unknown glyph", """
            variants:
              - id: a
                name: A
                configUrl: https://example.com/k.json
                icon:
                  glyph: unicorn
        """)
        assertRejected("malformed colour", """
            variants:
              - id: a
                name: A
                configUrl: https://example.com/k.json
                icon:
                  background: blue
        """)
        assertRejected("two default variants", """
            variants:
              - id: a
                name: A
                configUrl: https://example.com/k.json
                default: true
              - id: b
                name: B
                configUrl: https://example.com/k.json
                default: true
        """)
        assertRejected("empty variants list", "variants: []")
    }

    private fun load(yaml: String): List<AppVariant> {
        val file = temp.newFile("app-variants.yaml")
        file.writeText(yaml.trimIndent())
        return AppVariants.load(file, "de.davidgrieser.container")
    }

    private fun assertRejected(what: String, yaml: String) {
        val failed = runCatching { load(yaml) }.isFailure
        assertTrue("$what should have been rejected", failed)
    }

    /** buildSrc is its own build, so the repository root is one level up. */
    private fun repoRoot(): File = File(System.getProperty("user.dir")).let {
        if (it.name == "buildSrc") it.parentFile else it
    }
}
