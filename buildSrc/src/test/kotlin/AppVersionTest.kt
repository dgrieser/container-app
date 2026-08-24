import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the version a release APK carries. A wrong `versionCode` is only
 * noticed once a device refuses to treat a new release as an upgrade, by which
 * time the APK is published — so the rules are pinned here.
 */
class AppVersionTest {

    @Test
    fun `a tag becomes a name and an ordered code`() {
        assertEquals(AppVersion("1.2.3", 10_203), AppVersion.parse("v1.2.3"))
        assertEquals(AppVersion("1.2.3", 10_203), AppVersion.parse("1.2.3"))
        // The tags this repository actually uses, and their successors.
        assertEquals(8, AppVersion.parse("v0.0.8").code)
        assertEquals(9, AppVersion.parse("v0.0.9").code)
        assertEquals(100, AppVersion.parse("v0.1.0").code)
        assertEquals(10_000, AppVersion.parse("v1.0").code)
    }

    @Test
    fun `every release supersedes the one before it`() {
        val released = listOf(
            "v0.0.8", "v0.0.9", "v0.0.10", "v0.1.0", "v0.1.1", "v1.0", "v1.0.1", "v2.0.0"
        ).map { AppVersion.parse(it).code }

        released.zipWithNext { earlier, later ->
            assertTrue("$earlier should sort before $later", earlier < later)
        }
        // Every one of them is also newer than the versionCode 1 that all
        // releases up to now shipped with, so those upgrade cleanly.
        assertTrue(released.all { it > 1 })
    }

    @Test
    fun `a pre-release or a git describe suffix stays in the name only`() {
        assertEquals(AppVersion("1.2.3-rc1", 10_203), AppVersion.parse("v1.2.3-rc1"))
        assertEquals(
            AppVersion("0.0.8-12-gabc1234", 8),
            AppVersion.parse("v0.0.8-12-gabc1234")
        )
        assertEquals(
            AppVersion("0.0.8-12-gabc1234-dirty", 8),
            AppVersion.parse("v0.0.8-12-gabc1234-dirty")
        )
    }

    @Test
    fun `an unknown version still builds`() {
        assertEquals(1, AppVersion.parse(null).code)
        assertEquals(1, AppVersion.parse("   ").code)
        assertTrue(AppVersion.parse(null).name.contains("unknown"))
    }

    @Test
    fun `a version that would break ordering fails the build`() {
        // 100 in a place worth 100 of the next one up would collide with the
        // component above it: 1.100.0 and 2.0.0 are both 20000.
        listOf("v1.100.0", "v1.2.100", "v99999.0.0", "release-1", "v1.2.3.4").forEach {
            val failed = runCatching { AppVersion.parse(it) }.isFailure
            assertTrue("`$it` should have been rejected", failed)
        }
    }
}
