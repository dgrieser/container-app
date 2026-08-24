# Variant parser corpus

`app-variants.yaml` is parsed twice, in two languages: by
[`android/buildSrc/src/main/kotlin/AppVariants.kt`](../android/buildSrc/src/main/kotlin/AppVariants.kt)
for the Gradle build, and by [`ios/tools/variants.py`](../ios/tools/variants.py)
for the Xcode project generator. Kotlin `buildSrc` code cannot be called from a
Python generator that has to run on Linux, so the parser is duplicated — and a
duplicated parser drifts.

Three things stop it. [`app-variants.schema.json`](../app-variants.schema.json)
is the normative source for the key sets, types, enums and defaults. A CI job
diffs the two parsers' fully-defaulted output. And this directory is the third:
every file here is fed to **both** test suites, which assert the same verdict.

- `valid/` — must parse.
- `invalid/` — must be rejected.

Each file opens with an `# expect:` comment naming the rule it exercises. The
rules a schema cannot express live here rather than there: unique ids, unique
applicationIds, at most one `default: true`, reserved ids, `configUrl` required
unless `defaultKioskPath` is absolute, `glyph` xor `vector`, and
`locationReason` only alongside `allowLocation`.

**Adding a rule means adding a file.** A rule implemented in one language and
forgotten in the other then fails that language's suite, which is the whole
point of keeping the cases as data instead of inline strings.
