import java.io.File

/**
 * Where the tests find the files the build reads.
 *
 * buildSrc is its own Gradle build, so `user.dir` is either the root of the
 * Android build or `buildSrc` inside it, depending on how the tests were
 * started; and since the Gradle build moved into `android/`, the files shared
 * with the iOS app sit one level above that again.
 */
object TestPaths {

    /** The root of the Android Gradle build, i.e. `android/`. */
    val gradleRoot: File
        get() = File(System.getProperty("user.dir")).let {
            if (it.name == "buildSrc") it.parentFile else it
        }

    /** The repository root: `app-variants.yaml`, `app-icons/`, the schema. */
    val repoRoot: File get() = gradleRoot.parentFile

    /** The built-in launcher symbols, as the build itself loads them. */
    fun glyphs(): LauncherGlyphs = LauncherGlyphs.load(repoRoot)
}
