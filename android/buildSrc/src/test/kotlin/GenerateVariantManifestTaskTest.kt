import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks the generated per-variant manifest, whose whole point is that a build
 * declares nothing it cannot use: a variant without `allowLocation` must come
 * out carrying no location permission, and the markup has to be well-formed —
 * the manifest merger would otherwise be the first to notice.
 */
class GenerateVariantManifestTaskTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a variant that opted in declares the location permissions`() {
        val manifest = generate(
            permissions = GenerateVariantManifestTask.LOCATION_PERMISSIONS,
            optionalFeatures = GenerateVariantManifestTask.LOCATION_FEATURES
        )

        val text = manifest.readText()
        GenerateVariantManifestTask.LOCATION_PERMISSIONS.forEach {
            assertTrue("$it missing", text.contains("""<uses-permission android:name="$it" />"""))
        }
        // Not required, so a device without GPS can still install the APK.
        assertTrue(
            "GPS must be declared as an optional feature",
            text.contains(
                """<uses-feature android:name="android.hardware.location.gps" """ +
                    """android:required="false" />"""
            )
        )
        assertEquals(2, permissionsOf(manifest).size)
    }

    @Test
    fun `a variant that did not opt in declares nothing`() {
        val manifest = generate()

        assertTrue(permissionsOf(manifest).isEmpty())
        assertTrue(
            "an empty manifest must still be a manifest",
            manifest.readText().contains("<manifest")
        )
    }

    private fun generate(
        permissions: List<String> = emptyList(),
        optionalFeatures: List<String> = emptyList()
    ): File {
        val output = File(temp.newFolder(), "generated/AndroidManifest.xml")
        val project = ProjectBuilder.builder().withProjectDir(temp.newFolder()).build()
        val task = project.tasks.register<GenerateVariantManifestTask>("generateManifest") {
            this.permissions.set(permissions)
            this.optionalFeatures.set(optionalFeatures)
            manifestFile.set(output)
        }
        task.get().generate()
        return output
    }

    /** Parses the result, which also proves the generated markup is well-formed. */
    private fun permissionsOf(manifest: File): List<String> {
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val nodes = root.getElementsByTagName("uses-permission")
        return (0 until nodes.length).map { index ->
            nodes.item(index).attributes
                .getNamedItem("android:name")
                .nodeValue
        }
    }
}
