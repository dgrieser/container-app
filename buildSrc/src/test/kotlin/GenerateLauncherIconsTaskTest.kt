import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks that the generated launcher-icon resources are well-formed XML and
 * carry the variant's colours — the Android build would otherwise be the first
 * place a typo in the generated markup shows up.
 */
class GenerateLauncherIconsTaskTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val expectedFiles = listOf(
        "drawable/ic_launcher_background.xml",
        "drawable/ic_launcher_background_round.xml",
        "drawable/ic_launcher_foreground.xml",
        "mipmap-anydpi/ic_launcher.xml",
        "mipmap-anydpi/ic_launcher_round.xml",
        "mipmap-anydpi-v26/ic_launcher.xml",
        "mipmap-anydpi-v26/ic_launcher_round.xml"
    )

    @Test
    fun `generates well-formed resources for every glyph`() {
        LauncherGlyphs.names.forEach { glyph ->
            val output = generate(glyph = glyph, background = "#123456", tint = "#FEDCBA")
            expectedFiles.forEach { relative ->
                val file = File(output, relative)
                assertTrue("$glyph: missing $relative", file.isFile)
                parse(file)
            }
            val foreground = File(output, "drawable/ic_launcher_foreground.xml").readText()
            assertTrue("$glyph: tint missing", foreground.contains("#FEDCBA"))
            assertTrue(
                "$glyph: path data missing",
                foreground.contains(LauncherGlyphs.pathData(glyph)!!)
            )
            assertTrue(
                "$glyph: background colour missing",
                File(output, "drawable/ic_launcher_background.xml").readText().contains("#123456")
            )
        }
    }

    @Test
    fun `a custom vector is used verbatim as the foreground`() {
        val custom = temp.newFile("custom.xml")
        val markup = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="108dp"
                android:height="108dp"
                android:viewportWidth="108"
                android:viewportHeight="108">
                <path android:fillColor="#FFFFFF" android:pathData="M10,10h88v88h-88z" />
            </vector>
        """.trimIndent()
        custom.writeText(markup)

        val output = generate(vector = custom)
        val foreground = File(output, "drawable/ic_launcher_foreground.xml")
        parse(foreground)
        assertTrue(foreground.readText().contains("M10,10h88v88h-88z"))
    }

    @Test
    fun `stale resources are dropped on regeneration`() {
        val output = temp.newFolder("res")
        val leftover = File(output, "drawable/ic_launcher_stale.xml")
        leftover.parentFile.mkdirs()
        leftover.writeText("<vector />")

        generate(glyph = "home", outputDir = output)

        assertTrue("stale file survived", !leftover.exists())
    }

    private fun generate(
        glyph: String? = null,
        vector: File? = null,
        background: String = "#1F6FEB",
        tint: String = "#FFFFFF",
        outputDir: File = temp.newFolder()
    ): File {
        val project = ProjectBuilder.builder().withProjectDir(temp.newFolder()).build()
        val task = project.tasks.register<GenerateLauncherIconsTask>("generateIcons") {
            this.background.set(background)
            this.tint.set(tint)
            glyph?.let { glyphPathData.set(LauncherGlyphs.pathData(it)) }
            vector?.let { vectorFile.set(it) }
            this.outputDir.set(outputDir)
        }
        task.get().generate()
        return outputDir
    }

    private fun parse(file: File) {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
    }
}
