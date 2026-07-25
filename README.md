# Container

An Android **kiosk container app**. It displays a set of web apps that are
controlled centrally by a JSON file hosted on `david-grieser.de`. Users switch
between the allowed web apps from an in-app menu, but cannot navigate the
browser away to any other domain. An admin (PIN-protected) can point the app at
a different configuration URL and change the PIN.

One code base can ship as **several different apps**: name, icon, kiosk
configuration and behaviour come from [`app-variants.yaml`](app-variants.yaml),
and the release pipeline builds one installable APK per entry. See
[App variants](#app-variants).

## Features

- **Several apps from one container.** [`app-variants.yaml`](app-variants.yaml)
  declares each app's name, launcher symbol, configuration URL, default page,
  its TLS handling, and whether it has a PIN or a menu at all. Every variant gets
  its own `applicationId`, so they install side by side on the same device.
  A variant pinned to one absolute URL can skip the configuration file entirely.
- **Remotely controlled app list.** On launch the app fetches a JSON file and
  shows the web apps it lists. Change the JSON on the server → every device
  updates on next launch / reload. No app update needed.
- **Domain lock.** Each web app is anchored to the registered domain of its
  configured URL. Links, redirects and `target="_blank"` that would leave that
  domain are blocked — the page "will not work" for off-domain links, exactly as
  requested. Subdomains of the same registered domain are allowed (so `www.`,
  `cdn.`, `portal.` etc. work).
- **Hamburger menu.** A small, translucent menu button sits at the
  **bottom-right**. It is deliberately unobtrusive: 36 dp, neutral grey, and it
  fades down to 30 % opacity a couple of seconds after the last touch, so it
  barely registers over the page until you reach for it. Tapping it opens a
  bottom sheet to switch between the allowed apps. Variants with
  `showMenu: false` drop the button entirely and show a single page.
- **Pull to refresh.** Sliding down with a finger from the top of the page
  reloads it, the way browser apps do. The gesture only fires when the page is
  already scrolled to the top, so it never interferes with scrolling. Because
  the app runs full-screen, start the swipe just below the very top edge —
  a swipe from the edge itself is taken by the system to reveal the status bar.
- **Admin menu (PIN-protected).** An **Admin** entry in the menu asks for the
  PIN, then lets you:
  - set the **configuration URL** (which JSON file to read),
  - allow **unverified certificates** (see below),
  - **reload** the configuration,
  - **change the PIN**.
- **Unverified HTTPS certificates (opt-in).** Off by default: a page whose
  certificate can't be verified is refused, as in any browser. Turning
  *Allow unverified certificates* on in the admin menu makes the app load
  pages served with a self-signed, expired, unknown-CA or wrong-host
  certificate — useful for internal servers whose CA isn't installed on the
  device. A variant can ship with it already on
  (`allowUnverifiedSsl: true`). See
  [Unverified certificates](#unverified-certificates).
- **First-run PIN setup.** On first launch the app requires you to create a PIN
  before continuing — unless the variant sets `requirePin: false`, in which case
  no PIN is asked for and the admin menu opens straight away.
- **Phones and tablets.** No fixed orientation, responsive layout, immersive
  full-screen.
- **Offline resilience.** The last successfully loaded configuration is cached,
  so the app still works if the server is temporarily unreachable.

## App variants

[`app-variants.yaml`](app-variants.yaml) decides which apps are built out of
this container. Each entry under `variants:` becomes a Gradle product flavour
with its own `applicationId`, launcher name and launcher symbol, so the APKs
install **in parallel** on one device — same container app, different content
and behaviour:

```yaml
applicationId: de.davidgrieser.container   # base for generated ids

variants:
  - id: container            # internal name → flavour + applicationId suffix
    default: true            # keeps the bare applicationId, IDE default
    name: Container          # launcher label
    configUrl: https://david-grieser.de/kiosk.json
    icon:
      glyph: container
      background: "#1F6FEB"

  - id: portal
    name: Portal
    defaultKioskPath: https://portal.david-grieser.de/   # pinned, so no configUrl
    requirePin: false        # no PIN at all
    showMenu: false          # single page, no hamburger button
    allowUnverifiedSsl: true # server has a self-signed certificate
    versionNameSuffix: "-portal"
    icon:
      glyph: home
      background: "#0E7C66"
```

| Key | Required | Meaning |
|---|---|---|
| `id` | yes | Internal name, `[a-z][a-z0-9]*`. Becomes the Gradle flavour (`assembleContainerRelease`) and the applicationId suffix. |
| `name` | yes | Launcher label (`app_name`). |
| `configUrl` | yes\* | The `kiosk.json` this variant loads by default. \*Optional when `defaultKioskPath` is an absolute http(s) URL — that variant then reads no configuration file at all. |
| `applicationId` | no | Full override. Default: `<base>.<id>`, or the bare base id for the `default: true` variant. |
| `default` | no | One variant may set it: keeps the bare applicationId and is the flavour Android Studio preselects. |
| `versionNameSuffix` | no | Appended to the version name, e.g. `-portal`. |
| `defaultKioskPath` | no | Which page to open first (see below). Empty = first app in the `kiosk.json`. |
| `requirePin` | no (`true`) | `false` = no PIN setup on first run and the admin menu opens without one. |
| `showMenu` | no (`true`) | `false` = no hamburger button; the app shows a single page and cannot be switched. |
| `allowUnverifiedSsl` | no (`false`) | `true` = the *Allow unverified certificates* switch starts on, for kiosks against a self-signed or internal-CA server. Still togglable per device in the admin menu. |
| `icon.glyph` | no (`container`) | Built-in symbol: `container`, `equalizer`, `apps`, `dashboard`, `list`, `menu`, `home`, `monitor`, `chat`, `info`, `lock`, `bolt`, `star`, `circle`, `square`, `triangle`, `diamond`. |
| `icon.vector` | no | Path (from the repo root) to your own 108×108 vector drawable, used instead of a glyph. |
| `icon.background` | no (`#1F6FEB`) | Icon background colour, `#RRGGBB` or `#AARRGGBB`. |
| `icon.tint` | no (`#FFFFFF`) | Glyph colour. |

Unknown or misspelled keys **fail the build** rather than being ignored, so a
typo cannot silently ship an APK with the wrong name or kiosk URL.

`defaultKioskPath` is resolved against the loaded configuration, in this order:

1. an absolute `https://` URL — matched against the configured apps, and
   otherwise opened as-is (so a variant can be pinned to a page the shared
   `kiosk.json` does not even list);
2. a path such as `/dashboard` — matched against the path of each configured
   app's URL (exact first, then prefix);
3. the `name` of one of the configured apps.

If nothing matches, the first app in the `kiosk.json` is shown.

Because case 1 needs nothing from the configuration file, `configUrl` may be
left out when `defaultKioskPath` is an absolute URL: such a variant fetches no
JSON, opens that one page, and anchors the domain lock to it. Anything else — a
path, an app name, or no `defaultKioskPath` at all — has to be resolved against
the app list, so a `configUrl` is then required and the build fails without one.
An admin can still point such a variant at a configuration URL later; clearing
that field again returns it to its pinned page.

**Icons are generated, not checked in.** For every variant the build writes an
adaptive icon (plus a layered fallback for API 24/25) from the glyph and colours
above into a generated resource folder — see
[`buildSrc/src/main/kotlin`](buildSrc/src/main/kotlin). Nothing needs to be
drawn by hand to tell two installed builds apart. A variant whose symbol is more
than a glyph points `icon.vector` at a 108×108 vector drawable in
[`app-icons/`](app-icons) instead; that file becomes the icon foreground
verbatim, so it brings its own colours and `icon.tint` no longer applies.

**Reaching the admin menu without a menu button.** A variant with
`showMenu: false` has no visible entry point, so **holding the bottom-right
corner for 1.5 s** opens the admin menu (asking for the PIN first, unless
`requirePin: false`). The gesture is watched without consuming touch events, so
the page underneath keeps working normally.

## Configuration file format

Host a JSON file (default URL: `https://david-grieser.de/kiosk.json`) shaped
like [`sample/kiosk.json`](sample/kiosk.json):

```json
{
  "apps": [
    {
      "name": "Portal",
      "icon": "https://david-grieser.de/kiosk/icons/portal.png",
      "url": "https://portal.david-grieser.de/"
    }
  ]
}
```

Field notes:

- `name` — label shown in the menu. Optional; falls back to the URL host.
- `icon` — absolute **https** URL of a small image (PNG/JPG/WebP). Optional;
  a placeholder is shown if missing or unreachable.
- `url` — absolute **https** URL of the page to display. Required. This URL's
  registered domain becomes the navigation boundary for that app.

A bare top-level array (`[ {…}, {…} ]`) is also accepted.

The default URL is per variant (`configUrl` in
[`app-variants.yaml`](app-variants.yaml)) and can be changed at runtime from the
admin menu; leaving the admin field empty restores the variant's own URL.
Variants pinned to an absolute `defaultKioskPath` need no configuration file at
all — see [App variants](#app-variants).

## Unverified certificates

Admin → **Allow unverified certificates** (a switch in the admin dialog,
persisted per device). It starts **off**, unless the variant declares
`allowUnverifiedSsl: true` in [`app-variants.yaml`](app-variants.yaml) — a build
for a kiosk against a self-signed server can then be installed without anybody
having to flip the switch on each device, and flipping it stays possible. When
it is on:

- the **WebView** proceeds through TLS errors instead of cancelling the load —
  self-signed certificates, expired ones, certificates from a CA the device
  doesn't know, and certificates issued for a different host name;
- the **configuration fetch** and the **menu icon downloads** likewise skip
  certificate and host-name verification.

Two limits are deliberate:

- The bypass follows the domain lock: TLS errors are only waived for URLs
  inside the currently selected app's registered domain. A broken certificate
  on some unrelated third-party host is still refused, and the app shows a
  short message saying which host was blocked.
- Cleartext `http://` is still blocked by the network security config. The
  switch relaxes *certificate verification*, not the requirement to use TLS.

Toggling the switch clears the WebView's remembered per-host decisions, so the
new setting applies to hosts that were already visited.

> **Security note.** With verification off the connection is encrypted but no
> longer authenticated: anything on the network path can present its own
> certificate and read or modify the traffic. Only enable this for kiosks on a
> network you control. The better fix, where possible, is to install the
> internal CA on the device (or via MDM) and leave the switch off.

## Building

Requires the Android SDK (platform 34, build-tools 34.x) and JDK 17.

```bash
# Point the build at your SDK (or set ANDROID_HOME):
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug             # debug APK of every variant
./gradlew assembleContainerDebug    # just one variant
./gradlew assembleRelease           # signed release (needs keystore.properties, see below)
```

Output: `app/build/outputs/apk/<variant>/<build type>/`, e.g.
`app/build/outputs/apk/container/release/`.

The variant definitions are parsed and the launcher icons generated by the small
Gradle plugin code in [`buildSrc`](buildSrc); its unit tests run as part of every
Gradle invocation, so an invalid `app-variants.yaml` is reported immediately.

## Signing

Release signing reads from a git-ignored `keystore.properties` in the project
root. Copy the example and fill in your details (you mentioned you'll add the
signing material to the repo separately):

```bash
cp keystore.properties.example keystore.properties
# edit keystore.properties, and place your .jks alongside it
```

If `keystore.properties` is absent, release builds are simply left unsigned
(the build still succeeds), so the project builds for anyone without the
signing secrets. `keystore.properties`, `*.jks` and `*.keystore` are
git-ignored.

## Releasing (CI)

`.github/workflows/android-release_ci.yml` builds, signs and publishes the
release APKs automatically **when you push a git tag** (e.g. `v1.0`). It reads
[`app-variants.yaml`](app-variants.yaml) into a build matrix (via
`.github/scripts/list_variants.py`) and then, **once per variant and in
parallel**, builds an unsigned release APK with Gradle, signs it with
[`ilharp/sign-android-release`](https://github.com/ilharp/sign-android-release),
and attaches `container-app-<variant>-<tag>-signed.apk` to the GitHub release
for that tag. So a tag on the file above produces
`container-app-container-v1.0-signed.apk` and
`container-app-portal-v1.0-signed.apk`, which can be installed next to each
other. CI does not use `keystore.properties`; it signs from the repository
secrets below instead.

All variants are signed with the same key, which is what you want: the
`applicationId` is what keeps them apart, and a shared key means updates keep
working for each of them.

Set these under **Settings → Secrets and variables → Actions**:

| Secret | What it is / how to produce it |
|---|---|
| `SIGNINGKEY_BASE64` | The keystore file, base64-encoded: `base64 -w0 keystore.jks` (macOS: `base64 -i keystore.jks`) |
| `KEY_ALIAS` | The key alias inside the keystore (the `-alias` used with `keytool -genkeypair`) |
| `KEY_STORE_PASSWORD` | The keystore password |
| `KEY_PASSWORD` | The key password (often the same as the store password) |

These are exactly the inputs of the `ilharp/sign-android-release` action. If
you have no keystore yet, create one with:

```bash
keytool -genkeypair -v -keystore keystore.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias <your-alias>
```

Keep the `.jks` and its passwords safe — once an app is published, updates
must be signed with the **same** key.

## Notes on "kiosk"

This app is a **single-purpose, navigation-locked container**. It does not by
itself pin the device (Android Lock Task / screen pinning) — that requires the
device to be provisioned as device-owner or the app added to a lock-task
allowlist by an MDM. If you want the device fully locked to this app, enroll it
with your MDM and add this app to the lock-task allowlist, or use screen
pinning; the app is already structured to behave well in that mode (it stays in
the container on Back instead of exiting).

## Project layout

```
app-variants.yaml        Which apps to build: name, symbol, kiosk config, behaviour
app-icons/               Hand-drawn launcher symbols referenced by `icon.vector`
buildSrc/src/main/kotlin/
  AppVariants.kt         Parses & validates app-variants.yaml
  LauncherGlyphs.kt      The built-in launcher symbols
  GenerateLauncherIconsTask.kt  Writes each variant's icon resources
app/src/main/java/de/davidgrieser/container/
  MainActivity.kt        UI: WebView, pull-to-refresh, FAB, menu sheet, admin & PIN dialogs
  KioskWebViewClient.kt  Enforces the domain lock & TLS-error policy
  InsecureSsl.kt         Opt-in trust-all TLS for the app's own HTTP calls
  DomainRules.kt         Host / domain matching rules
  ConfigRepository.kt    Fetches & parses the remote JSON (with caching)
  Prefs.kt               Persisted state (PIN hash, config URL, cache, selection)
  PinManager.kt          Salted, iterated PIN hashing & verification
  IconLoader.kt          Tiny dependency-free menu-icon loader
```
