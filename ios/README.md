# The iOS app

The same container app as [`android/`](../android), built from the same
[`app-variants.yaml`](../app-variants.yaml). This file covers what is specific to
building it; what the app *does* is in the [main README](../README.md), because
almost all of it is shared behaviour driven by shared configuration.

## What is generated and what is not

Nothing about a variant is written by hand. `ios/tools/generate.py` reads
`app-variants.yaml` and writes everything under `ios/Generated/`, which is
git-ignored in its entirety:

```
Generated/
  variants.yml                    XcodeGen targets + schemes, one per variant
  Version.xcconfig                MARKETING_VERSION / CURRENT_PROJECT_VERSION
  Variants/<id>/
    main.swift                    the whole app target: one call into ContainerKit
    VariantConfig.swift           this variant's values, typed
    Info.plist                    the keys iOS itself reads
    Variant.xcconfig              its version name, chaining in the shared settings
    Assets.xcassets/              its app icon, rendered from the glyph and colours
```

`Container.xcodeproj` is generated too, and also not checked in — in the same
spirit as the launcher icons on the Android side: it is derived from files that
are. The cost is one `make project` after pulling a change; the benefit is never
resolving a `project.pbxproj` merge conflict.

Hand-written, and where to make a change:

| File | What belongs in it |
|---|---|
| [`project.yml`](project.yml) | **How** the apps are built: deployment target, the package reference, and the one `Variant` target template every variant is made from. |
| [`Config/Base.xcconfig`](Config/Base.xcconfig) | Build settings shared by every target. |
| [`ContainerKit/`](ContainerKit) | All the runtime code. |
| [`Tests/ContainerKitTests/`](Tests/ContainerKitTests) | The tests for it. |
| [`tools/`](tools) | The generator, and its own tests. |

## Building

```bash
brew install xcodegen cairo
pip3 install -r tools/requirements.txt

cd ios
make project        # generate everything, then write Container.xcodeproj
make open           # …and open it
make test           # the generator's tests, then ContainerKit's in a simulator
```

There is one scheme per variant, named after its `id`, so
`xcodebuild -scheme podcaster` is the direct analogue of
`./gradlew assemblePodcasterRelease`.

`APP_VERSION` overrides the version exactly as `-PappVersion` does on the Android
side: `make project APP_VERSION=v1.2.3`. Without it the working copy is asked
(`git describe`), so a locally built app says which commit it came from rather
than claiming to be a release.

### When you have to regenerate

- **Adding or changing a variant** — yes: `make project`.
- **Changing `project.yml` or `Base.xcconfig`** — yes.
- **Adding, renaming or deleting a Swift file** — **no.** The runtime code is a
  local SwiftPM package, and SwiftPM globs its sources at build time where
  XcodeGen globs at generation time. That one fact is what makes a generated
  project bearable day to day.

### Without a Mac

Everything except turning the spec into an `.xcodeproj` and compiling it runs on
Linux, which is deliberate: `make generate` writes all the per-variant files, and
`make icons` draws the app icons — `pathData` is SVG path data, so nothing about
an icon needs Xcode. `python3 -m unittest discover -s tests -t .` inside `tools/`
runs 80-odd tests over the parser, the version arithmetic, the plists, the icons
and the generated Swift.

`generate.py --skip-icons` (or `make project SKIP_ICONS=1`) produces a complete,
openable project where CairoSVG is not installed; Xcode then warns about the
missing image. It leaves any icons *already* on disk alone, which is how CI
works: they are drawn on a Linux runner, handed to the Mac as an artifact, and
compiled there.

## Adding a variant

Edit [`app-variants.yaml`](../app-variants.yaml) and run `make project`. That is
the whole procedure — a target, a scheme, a bundle identifier, an `Info.plist`
and an app icon all follow from the entry.

Two things do **not** follow from it, and are worth knowing before you ship one:

- Each bundle identifier is an **App ID** that has to exist in Apple's developer
  portal before the app can be signed, and each App Store release needs its own
  record. On Android the `applicationId` is free. AltStore's on-device re-signing
  sidesteps this entirely, which is the main reason it is the distribution route
  this repository uses.
- A variant with `allowLocation: true` should also set `locationReason`. iOS
  terminates an app that asks for a position without a purpose string; without the
  key a sentence is generated from the variant's name, which works but reads like
  it was generated.

## How one YAML file becomes three apps

`project.yml` holds one `targetTemplates.Variant`, and
`Generated/variants.yml` holds only `targets:` and `schemes:` — one entry each,
each target being that template plus three attributes. XcodeGen deep-merges the
two through `include:`, and both keys are dictionaries keyed by name, so the merge
is exactly right.

The split is the point: adding a variant touches only the generated half,
changing how the app is built touches only the hand-written one, and neither
requires editing a `.pbxproj`.

Per-variant configuration reaches Swift as a generated `VariantConfig.swift`
rather than as `Info.plist` lookups, so a missing field is a compile error rather
than a runtime nil, and the values arrive typed — a screen mode as an enum case,
a colour already parsed to `0xAARRGGBB`. Since that compile only happens on a
Mac, `tools/tests/test_swift_contract.py` reads `VariantConfig.swift` and
`ScreenMode.swift` and asserts the same things on Linux.

## Keeping the two platforms in step

`app-variants.yaml` is parsed twice, in two languages: Kotlin for Gradle, Python
for this generator. Kotlin `buildSrc` code cannot be called from a script that
has to run on Linux, so the parser is duplicated — and a duplicated parser
drifts. Three things stop it, and all three run in CI:

1. [`app-variants.schema.json`](../app-variants.schema.json) is the normative
   source for the key sets, the types, the enums and every default. The Python
   parser is *driven by* it; `SchemaSyncTest` asserts the Kotlin one agrees with
   it.
2. [`variants-testdata/`](../variants-testdata) holds the accept/reject corpus as
   data, so both test suites run the same cases.
3. A CI job diffs `./gradlew dumpVariantsJson` against
   `generate.py --dump-json`. That is the only check that catches the two parsers
   applying different *defaults*.

The glyph path strings live once, in
[`app-icons/glyphs.yaml`](../app-icons/glyphs.yaml), and both sides read them.

## Signing

Release builds are **unsigned**, and that is the intended state rather than a
gap: the apps are distributed through an AltStore source, and AltStore installs
one by re-signing it on the device with the installing user's own Apple ID. No
Apple Developer account, no distribution certificate and no provisioning
profiles are involved.

Be clear-eyed about the asymmetry with Android, though. An unsigned APK can be
signed by anyone with `apksigner` and installed; an unsigned `.ipa` **cannot be
installed on a stock iPhone** without an Apple-issued identity. AltStore supplies
that identity on the user's own device. Outside AltStore the artifact is good for
proving the build links for device, for re-signing by someone who does have a
certificate, and for inspection (`plutil -p`, `otool`).

To sign a **local** build so it runs on your own device, copy
[`signing.local.xcconfig.example`](signing.local.xcconfig.example) to
`signing.local.xcconfig` and fill in your team. It is git-ignored, and
`Base.xcconfig` includes it only if it exists — the same pattern as
`android/keystore.properties`.

The release workflow detects certificate or App Store Connect secrets and **fails
loudly** if it finds any, rather than quietly changing what it produces. Wiring
up a signed path is a deliberate edit to
[`.github/workflows/ios-release_ci.yml`](../.github/workflows/ios-release_ci.yml).

## Releasing

Push a tag. [`ios-release_ci.yml`](../.github/workflows/ios-release_ci.yml)
builds one unsigned `.ipa` per variant, attaches it and its dSYMs to the release,
and publishes the AltStore source to GitHub Pages at
`https://<owner>.github.io/<repo>/source.json`.

The source *appends* each release's version rather than replacing the list, so
someone on an older build can still install what they have.

**GitHub Pages has to be enabled once, by hand**: Settings → Pages → Source:
GitHub Actions. A workflow cannot do it, and the publish step fails until it is.

### AltStore PAL

This is an AltStore *source* — the mechanism where AltStore re-signs apps on the
device. **AltStore PAL**, the EU alternative app *marketplace*, is a different
route: it needs Apple Developer Program membership, the EU Alternative Terms
Addendum, Apple **notarization** of every build, an alternative-distribution
certificate and a `marketplaceID` per app. None of that can be produced without
an Apple account, and the notarization step needs macOS Apple tooling. The
generator's shape leaves room for it;
[`tools/altstore.py`](tools/altstore.py) is where it would go.

## The tests

`Tests/ContainerKitTests/` is an **Xcode** unit-test target, not a SwiftPM one:
`ContainerKit` uses UIKit and WebKit, so `swift test` cannot build it on any
host. They are logic tests with no host application, which is all the pure core
needs — and it is why the keychain is not covered there, since a keychain item
needs an app bundle with entitlements to live in.

Every scheme runs them, because they cover `ContainerKit`, which every variant is
built out of.

## Deployment target

iOS 15.0. `WKWebView` geolocation and `UILaunchScreen` both want 15, and nothing
in the app needs more. Raising it is a one-line change in `project.yml` and
`Package.swift`.
