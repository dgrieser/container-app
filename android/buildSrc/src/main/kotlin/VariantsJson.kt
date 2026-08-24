/**
 * The fully-resolved variant model as canonical JSON.
 *
 * `app-variants.yaml` is parsed twice — here in Kotlin for Gradle, and in Python
 * for the Xcode project generator — so a CI job dumps both and diffs them. That
 * is the only check that catches the two parsers applying *different defaults*
 * rather than merely allowing different keys, which is why the output has to be
 * canonical: keys sorted, two-space indent, no trailing whitespace, one
 * representation per value. `ios/tools/variants.py --dump-json` writes byte-identical
 * output for the same input.
 *
 * Nothing Android-specific belongs here. `gradleName` is derived from `id` by the
 * Gradle build and has no counterpart on the other side, so it stays out.
 */
object VariantsJson {

    fun dump(variants: List<AppVariant>): String =
        buildString {
            append("[\n")
            variants.forEachIndexed { index, variant ->
                append(objectOf(fieldsOf(variant), indent = 2))
                append(if (index == variants.lastIndex) "\n" else ",\n")
            }
            append("]\n")
        }

    private fun fieldsOf(variant: AppVariant): Map<String, Any?> = mapOf(
        "allowExternalNavigation" to variant.allowExternalNavigation,
        "allowLocation" to variant.allowLocation,
        "allowUnverifiedSsl" to variant.allowUnverifiedSsl,
        "applicationId" to variant.applicationId,
        "barColor" to mapOf(
            "dark" to variant.barColor.dark,
            "light" to variant.barColor.light
        ),
        "configUrl" to variant.configUrl,
        "default" to variant.isDefault,
        "defaultKioskPath" to variant.defaultKioskPath,
        "icon" to mapOf(
            "background" to variant.icon.background,
            "glyph" to variant.icon.glyph,
            "tint" to variant.icon.tint,
            "vector" to variant.icon.vector
        ),
        "id" to variant.id,
        "locationReason" to variant.locationReason,
        "name" to variant.name,
        "requirePin" to variant.requirePin,
        "screenMode" to variant.screenMode,
        "showMenu" to variant.showMenu,
        "versionNameSuffix" to variant.versionNameSuffix
    )

    private fun objectOf(fields: Map<String, Any?>, indent: Int): String {
        val pad = " ".repeat(indent)
        val inner = " ".repeat(indent + 2)
        return fields.toSortedMap().entries.joinToString(
            separator = ",\n",
            prefix = "$pad{\n",
            postfix = "\n$pad}"
        ) { (key, value) ->
            inner + string(key) + ": " + when (value) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    objectOf(value as Map<String, Any?>, indent + 2).trimStart()
                }
                else -> scalar(value)
            }
        }
    }

    private fun scalar(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        else -> string(value.toString())
    }

    private fun string(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
