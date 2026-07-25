import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
                assertTrue("$it does not exist", File(repoRoot(), it).isFile)
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
        assertEquals("", container.defaultKioskPath)
        assertEquals(LauncherGlyphs.DEFAULT, container.icon.glyph)

        val portal = variants[1]
        assertEquals("de.davidgrieser.container.portal", portal.applicationId)
        assertFalse(portal.requirePin)
        assertFalse(portal.showMenu)
        assertEquals("/dashboard", portal.defaultKioskPath)
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
