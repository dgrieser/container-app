import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What a build calls itself: the `versionName` users and release assets see, and
 * the `versionCode` Android compares to decide that one APK is newer than
 * another.
 *
 * Both come from the git tag, which is what the release pipeline is triggered by
 * in the first place — the workflow passes the pushed tag, and a build from a
 * working copy falls back to `git describe`, so a locally built APK still says
 * which commit it came from instead of claiming to be the release.
 */
data class AppVersion(val name: String, val code: Int) {

    companion object {

        /**
         * `v1.2.3`, `1.2`, or either with a trailing `-rc1` / `-12-gabc1234` /
         * `+build3`. A suffix may not start with a dot: `v1.2.100` would
         * otherwise pass as 1.2 with a suffix of `.100`, quietly losing the
         * patch, and `v1.2.3.4` as 1.2.3.
         */
        private val VERSION_PATTERN =
            Regex("""^v?(\d{1,4})(?:\.(\d{1,2}))?(?:\.(\d{1,2}))?(?:[-+][^.].*)?$""")

        /** Highest `versionCode` Google Play accepts, and a sane ceiling anyway. */
        private const val MAX_CODE = 2_100_000_000

        /** What an APK built outside a tagged checkout says. */
        private val UNKNOWN = AppVersion(name = "0.0.0-unknown", code = 1)

        /**
         * [declared] wins — the tag the release workflow was triggered by. Without
         * it the working copy is asked, and if that answers nothing (no git, no
         * tags, a source download) the build still succeeds, marked as unknown.
         */
        fun resolve(declared: String?, repositoryRoot: File): AppVersion {
            val raw = declared?.trim()?.ifEmpty { null } ?: describe(repositoryRoot)
            return parse(raw)
        }

        /**
         * Turns a tag into a version. The name keeps whatever the tag said (minus
         * a leading `v`), so a pre-release or a `git describe` suffix stays
         * visible; the code is derived from the numeric part only:
         * `major * 10000 + minor * 100 + patch`, which keeps ordering as long as
         * minor and patch stay below 100 — enforced here rather than discovered
         * after a release that Android considers older than its predecessor.
         */
        fun parse(raw: String?): AppVersion {
            val tag = raw?.trim()?.ifEmpty { null } ?: return UNKNOWN
            val match = VERSION_PATTERN.matchEntire(tag) ?: error(
                "Version `$tag` is not a version tag: expected v<major>[.<minor>[.<patch>]], " +
                    "optionally followed by -<anything>, e.g. v1.2.3 or v1.2.3-rc1."
            )
            val (major, minor, patch) = match.destructured
            val code = major.toInt() * 10_000 +
                (minor.ifEmpty { "0" }).toInt() * 100 +
                (patch.ifEmpty { "0" }).toInt()
            if (code <= 0) error("Version `$tag` yields versionCode $code; it must be positive.")
            if (code > MAX_CODE) error("Version `$tag` yields versionCode $code, above $MAX_CODE.")
            return AppVersion(name = tag.removePrefix("v"), code = code)
        }

        /**
         * The working copy's own answer: the last tag, how far past it this commit
         * is, and whether anything is uncommitted. Null whenever git cannot say —
         * no repository, no tags, or no git at all.
         */
        private fun describe(repositoryRoot: File): String? = runCatching {
            val process = ProcessBuilder(
                "git", "describe", "--tags", "--always", "--dirty=-dirty"
            )
                .directory(repositoryRoot)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy()
                return null
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            output.takeIf { process.exitValue() == 0 && VERSION_PATTERN.matches(it) }
        }.getOrNull()
    }
}
