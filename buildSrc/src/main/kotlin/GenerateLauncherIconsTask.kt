import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Writes the launcher icon of one app variant into a generated resource
 * directory, so every APK carries its own symbol without any checked-in
 * binaries. Everything produced is a vector drawable:
 *
 * - `mipmap-anydpi-v26/ic_launcher(.round).xml` — adaptive icon (API 26+)
 * - `mipmap-anydpi/ic_launcher(.round).xml` — layered fallback (API 24/25)
 * - `drawable/ic_launcher_background(_round).xml` — the variant's colour
 * - `drawable/ic_launcher_foreground.xml` — the glyph, or the variant's own vector
 */
@CacheableTask
abstract class GenerateLauncherIconsTask : DefaultTask() {

    @get:Input
    abstract val background: Property<String>

    @get:Input
    abstract val tint: Property<String>

    /** `pathData` of a [LauncherGlyphs] entry; unset when [vectorFile] is used. */
    @get:Input
    @get:Optional
    abstract val glyphPathData: Property<String>

    /** A repository-provided 108x108 vector drawable used as the foreground. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val vectorFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = outputDir.get().asFile
        root.deleteRecursively()

        write(root, "drawable/ic_launcher_background.xml", squareBackground(background.get()))
        write(root, "drawable/ic_launcher_background_round.xml", roundBackground(background.get()))
        write(root, "drawable/ic_launcher_foreground.xml", foreground())

        // API 26+: the launcher masks and animates the two layers itself.
        write(root, "mipmap-anydpi-v26/ic_launcher.xml", adaptiveIcon())
        write(root, "mipmap-anydpi-v26/ic_launcher_round.xml", adaptiveIcon())
        // API 24/25: no adaptive icons, so ship pre-composed square / round icons.
        write(root, "mipmap-anydpi/ic_launcher.xml", legacyIcon("ic_launcher_background"))
        write(root, "mipmap-anydpi/ic_launcher_round.xml", legacyIcon("ic_launcher_background_round"))
    }

    private fun foreground(): String {
        val file = vectorFile.orNull?.asFile
        if (file != null) return file.readText()

        val pathData = glyphPathData.orNull
            ?: error("Neither `glyphPathData` nor `vectorFile` was set for ${path}.")
        // Scale the 24dp glyph to 52.8dp and centre it, keeping it inside the
        // 66dp safe zone that adaptive-icon masks are guaranteed not to clip.
        return """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="108dp"
                android:height="108dp"
                android:viewportWidth="108"
                android:viewportHeight="108">
                <group
                    android:scaleX="$GLYPH_SCALE"
                    android:scaleY="$GLYPH_SCALE"
                    android:translateX="$GLYPH_OFFSET"
                    android:translateY="$GLYPH_OFFSET">
                    <path
                        android:fillColor="${tint.get()}"
                        android:pathData="$pathData" />
                </group>
            </vector>
        """.trimIndent()
    }

    private fun squareBackground(color: String) = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp"
            android:height="108dp"
            android:viewportWidth="108"
            android:viewportHeight="108">
            <path
                android:fillColor="$color"
                android:pathData="M0,0h108v108h-108z" />
        </vector>
    """.trimIndent()

    private fun roundBackground(color: String) = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp"
            android:height="108dp"
            android:viewportWidth="108"
            android:viewportHeight="108">
            <path
                android:fillColor="$color"
                android:pathData="M0,54A54,54 0 1,1 108,54A54,54 0 1,1 0,54Z" />
        </vector>
    """.trimIndent()

    private fun adaptiveIcon() = """
        <?xml version="1.0" encoding="utf-8"?>
        <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
            <background android:drawable="@drawable/ic_launcher_background" />
            <foreground android:drawable="@drawable/ic_launcher_foreground" />
            <monochrome android:drawable="@drawable/ic_launcher_foreground" />
        </adaptive-icon>
    """.trimIndent()

    private fun legacyIcon(backgroundDrawable: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <layer-list xmlns:android="http://schemas.android.com/apk/res/android">
            <item android:drawable="@drawable/$backgroundDrawable" />
            <item android:drawable="@drawable/ic_launcher_foreground" />
        </layer-list>
    """.trimIndent()

    private fun write(root: File, relativePath: String, content: String) {
        val target = File(root, relativePath)
        target.parentFile.mkdirs()
        target.writeText(content.trimEnd() + "\n")
    }

    private companion object {
        const val GLYPH_SCALE = "2.2"
        const val GLYPH_OFFSET = "27.6"
    }
}
