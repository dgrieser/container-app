import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes the manifest entries that only some variants get, to be merged into the
 * main `AndroidManifest.xml` by the Android build.
 *
 * This is what makes a permission-shaped variant setting more than a runtime
 * check: a build that does not opt in carries no such permission at all, so it
 * cannot ask for it, and nothing appears under the app's permissions on the
 * device. Nothing is checked in per variant — like the launcher icons, the file
 * is generated from [`app-variants.yaml`](app-variants.yaml).
 */
@CacheableTask
abstract class GenerateVariantManifestTask : DefaultTask() {

    /** Full permission names, e.g. `android.permission.ACCESS_FINE_LOCATION`. */
    @get:Input
    abstract val permissions: ListProperty<String>

    /** Hardware the variant uses where present but does not require to install. */
    @get:Input
    abstract val optionalFeatures: ListProperty<String>

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val entries = buildList {
            permissions.get().forEach {
                add("""    <uses-permission android:name="$it" />""")
            }
            optionalFeatures.get().forEach {
                add("""    <uses-feature android:name="$it" android:required="false" />""")
            }
        }
        val file = manifestFile.get().asFile
        file.parentFile?.mkdirs()
        file.writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated per variant from app-variants.yaml; do not edit. -->
            |<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            |${entries.joinToString("\n").ifEmpty { "    <!-- Nothing to add for this variant. -->" }}
            |</manifest>
            |
            """.trimMargin()
        )
    }

    companion object {
        /**
         * Both, because from Android 12 the user may grant only the coarse one —
         * asking for the pair is what lets that choice appear at all.
         */
        val LOCATION_PERMISSIONS = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION"
        )

        /** GPS is used where it exists; a device without it can still install. */
        val LOCATION_FEATURES = listOf("android.hardware.location.gps")
    }
}
