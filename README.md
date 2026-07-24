# Container

An Android **kiosk container app**. It displays a set of web apps that are
controlled centrally by a JSON file hosted on `david-grieser.de`. Users switch
between the allowed web apps from an in-app menu, but cannot navigate the
browser away to any other domain. An admin (PIN-protected) can point the app at
a different configuration URL and change the PIN.

## Features

- **Remotely controlled app list.** On launch the app fetches a JSON file and
  shows the web apps it lists. Change the JSON on the server → every device
  updates on next launch / reload. No app update needed.
- **Domain lock.** Each web app is anchored to the registered domain of its
  configured URL. Links, redirects and `target="_blank"` that would leave that
  domain are blocked — the page "will not work" for off-domain links, exactly as
  requested. Subdomains of the same registered domain are allowed (so `www.`,
  `cdn.`, `portal.` etc. work).
- **Hamburger menu.** A round menu button sits at the **bottom-right**. Tapping
  it opens a bottom sheet to switch between the allowed apps.
- **Admin menu (PIN-protected).** An **Admin** entry in the menu asks for the
  PIN, then lets you:
  - set the **configuration URL** (which JSON file to read),
  - **reload** the configuration,
  - **change the PIN**.
- **First-run PIN setup.** On first launch the app requires you to create a PIN
  before continuing.
- **Phones and tablets.** No fixed orientation, responsive layout, immersive
  full-screen.
- **Offline resilience.** The last successfully loaded configuration is cached,
  so the app still works if the server is temporarily unreachable.

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

The default URL can be changed at build time (`DEFAULT_CONFIG_URL` in
`app/build.gradle.kts`) or at runtime from the admin menu.

## Building

Requires the Android SDK (platform 34, build-tools 34.x) and JDK 17.

```bash
# Point the build at your SDK (or set ANDROID_HOME):
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug     # debug APK
./gradlew assembleRelease   # signed release (needs keystore.properties, see below)
```

Output: `app/build/outputs/apk/`.

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

`.github/workflows/android-release_ci.yml` builds, signs and publishes a
release APK automatically **when you push a git tag** (e.g. `v1.0`). It builds
an unsigned release APK with Gradle, signs it with
[`ilharp/sign-android-release`](https://github.com/ilharp/sign-android-release),
and attaches `container-app-<tag>-signed.apk` to the GitHub release for that
tag. CI does not use `keystore.properties`; it signs from the repository
secrets below instead.

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
app/src/main/java/de/davidgrieser/container/
  MainActivity.kt        UI: WebView, hamburger FAB, menu sheet, admin & PIN dialogs
  KioskWebViewClient.kt  Enforces the domain lock
  DomainRules.kt         Host / domain matching rules
  ConfigRepository.kt    Fetches & parses the remote JSON (with caching)
  Prefs.kt               Persisted state (PIN hash, config URL, cache, selection)
  PinManager.kt          Salted, iterated PIN hashing & verification
  IconLoader.kt          Tiny dependency-free menu-icon loader
```
