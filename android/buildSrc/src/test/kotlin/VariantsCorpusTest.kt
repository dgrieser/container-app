import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Runs the shared corpus in `variants-testdata/` through the Kotlin parser.
 *
 * The iOS generator parses the same file in Python, and `test_variants.py` runs
 * these same cases through it. Keeping the cases as data rather than as inline
 * strings is what makes a rule added on one side and forgotten on the other show
 * up as a failure on that side — see `variants-testdata/README.md`.
 */
class VariantsCorpusTest {

    @Test
    fun `every valid case parses`() {
        val cases = cases("valid")
        cases.forEach { file ->
            runCatching { load(file) }.onFailure {
                fail("${file.name} should have been accepted (${expectation(file)}): ${it.message}")
            }
        }
    }

    @Test
    fun `every invalid case is rejected`() {
        val cases = cases("invalid")
        cases.forEach { file ->
            if (runCatching { load(file) }.isSuccess) {
                fail("${file.name} should have been rejected: ${expectation(file)}")
            }
        }
    }

    private fun load(file: File): List<AppVariant> =
        AppVariants.load(file, "com.example.container", TestPaths.glyphs())

    private fun cases(folder: String): List<File> {
        val dir = File(TestPaths.repoRoot, "variants-testdata/$folder")
        assertTrue("missing ${dir.path}", dir.isDirectory)
        val files = dir.listFiles { f: File -> f.extension == "yaml" }?.sortedBy { it.name }.orEmpty()
        assertTrue("${dir.path} holds no cases", files.isNotEmpty())
        return files
    }

    /** The `# expect:` line every case opens with, so a failure names the rule. */
    private fun expectation(file: File): String =
        file.readLines().firstOrNull { it.startsWith("# expect:") }
            ?.removePrefix("# expect:")?.trim()
            ?: "no `# expect:` comment"
}
