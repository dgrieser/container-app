// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

/**
 * The repository root: `app-variants.yaml` and `app-icons/` are shared with the
 * iOS app, so they live one level above this Gradle build.
 */
val repoRoot: File = rootProject.rootDir.parentFile

/**
 * Prints the fully-resolved variant model as canonical JSON.
 *
 * The iOS generator parses the same `app-variants.yaml` in Python, and
 * `ios/tools/generate.py --dump-json` writes the same document. A CI job diffs
 * the two, which is what catches the parsers disagreeing about a *default* — a
 * kind of drift no key- or enum-level check can see.
 *
 * Deliberately registered here rather than in `:app`, so it runs without the
 * Android SDK: the check belongs on a plain Linux runner.
 */
tasks.register("dumpVariantsJson") {
    group = "verification"
    description = "Prints app-variants.yaml as canonical JSON, for comparison with the iOS generator."

    val variantsFile = File(repoRoot, "app-variants.yaml")
    val glyphsFile = File(repoRoot, LauncherGlyphs.FILE)
    val output = project.objects.fileProperty()
    output.set(project.layout.buildDirectory.file("variants.json"))

    // No up-to-date check on purpose: this is a two-kilobyte dump whose whole job
    // is to be compared with the other parser's, and a task that silently prints
    // nothing because it was cached would be read as "the parsers agree".
    outputs.upToDateWhen { false }

    doLast {
        val variants = AppVariants.load(
            variantsFile,
            "de.davidgrieser.container",
            LauncherGlyphs.loadFile(glyphsFile)
        )
        val json = VariantsJson.dump(variants)
        output.get().asFile.also { it.parentFile.mkdirs() }.writeText(json)
        print(json)
    }
}
