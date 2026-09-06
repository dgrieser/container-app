# Container

An **Android and iOS kiosk container app**. It displays a set of web apps that
are controlled centrally by a JSON file hosted on `david-grieser.de`. Users
switch between the allowed web apps from an in-app menu, but cannot navigate the
browser away to any other domain — a variant may opt into letting off-domain
links open *outside* the app, in the device's browser. An admin (PIN-protected)
can point the app at a different configuration URL and change the PIN.

One code base can ship as **several different apps**: name, icon, kiosk
configuration and behaviour come from [`app-variants.yaml`](app-variants.yaml),
and the release pipeline builds one installable APK **and one `.ipa`** per entry.
See [App variants](#app-variants).

Both platforms read that one file, so a new variant or a changed kiosk URL lands
on both at once. Where iOS cannot do what Android does — it has no navigation
bar, and it will not let an app accept an invalid certificate on a plist setting
alone — the difference is written down rather than papered over: see
[Platform differences](#platform-differences).

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
- **External links, if the variant asks for them (opt-in).** A variant with
  `allowExternalNavigation: true` doesn't refuse off-domain links but hands them
  to **Android's default handler** — the browser, or whichever app claims that
  link. The off-domain page still never loads inside the container: it opens
  outside it, and the app stays on its own page. See
  [External links](#external-links).
- **Location, if the variant asks for it (opt-in).** A variant with
  `allowLocation: true` lets its page use `navigator.geolocation` — the device's
  GPS — after the system's own permission prompt. Every other build carries no
  location permission at all, so its pages are refused outright. On Android only
  a page inside the anchored domain can ask; iOS gives the app no way to tell who
  asked, so that second check does not exist there. See [Location](#location).
- **Hamburger menu.** A small, translucent menu button sits at the
  **bottom-right**. It is deliberately unobtrusive: 36 dp, neutral grey, and it
  fades down to 30 % opacity a couple of seconds after the last touch, so it
  barely registers over the page until you reach for it. Tapping it opens a
  bottom sheet to switch between the allowed apps. Variants with
  `showMenu: false` drop the button entirely and show a single page.
- **Screen modes.** How much of the system's own UI stays over the page is a
  setting, not a given: full screen (the default), full screen with the status
  bar, full screen with the navigation bar, or both bars in view. A variant
  declares its starting point with `screenMode`, and the admin menu changes it
  per device. A bar that stays is painted in the variant's own `barColor` — one
  for light mode, one for dark — so the strip reads as part of the page rather
  than a white band above it. iOS has no navigation bar, so there the last two
  modes are about the home indicator instead. See
  [Screen modes](#screen-modes).
- **Pull to refresh.** Sliding down with a finger from the top of the page
  reloads it, the way browser apps do. The gesture only fires when the page is
  already scrolled to the top, so it never interferes with scrolling. In
  full-screen mode, start the swipe just below the very top edge — a swipe from
  the edge itself is taken by the system to reveal the status bar. A screen mode
  that keeps the status bar has no such edge to share. A variant whose page reads
  a downward drag itself — a map, a 3D scene, a canvas — turns the gesture off
  with `pullToRefresh: false`, and the drag stays the page's; the admin menu's
  *Reload configuration* then reloads such a build.
- **Admin menu (PIN-protected).** An **Admin** entry in the menu asks for the
  PIN, then lets you:
  - set the **configuration URL** (which JSON file to read),
  - pick the **screen mode** (which system bars stay over the page),
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
- **Phones and tablets.** No fixed orientation, responsive layout, full-screen
  by default and edge-to-edge either way.
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
    pullToRefresh: false     # the page reads a downward drag itself
    screenMode: statusBar    # keep the clock and battery above the page
    barColor:                # …in a strip that matches the page, per system theme
      light: "#FAFAFA"
      dark: "#101418"
    allowUnverifiedSsl: true # server has a self-signed certificate
    allowExternalNavigation: true  # off-domain links open in the browser
    allowLocation: true      # the page may use GPS, after Android's prompt
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
| `versionNameSuffix` | no | Appended to the version name, e.g. `-portal`. On iOS it cannot go in `CFBundleShortVersionString`, which Apple requires to be one to three integers, so it lives in a custom key and the artifact name — see [Platform differences](#platform-differences). |
| `defaultKioskPath` | no | Which page to open first (see below). Empty = first app in the `kiosk.json`. |
| `requirePin` | no (`true`) | `false` = no PIN setup on first run and the admin menu opens without one. |
| `showMenu` | no (`true`) | `false` = no hamburger button; the app shows a single page and cannot be switched. |
| `pullToRefresh` | no (`true`) | `false` = a pull down from the top of the page no longer reloads it, so a page that reads a downward drag itself keeps the gesture. Fixed per build; the admin menu's *Reload configuration* still reloads the page. |
| `screenMode` | no (`fullscreen`) | Which system bars stay over the page: `fullscreen` (neither), `statusBar` (top bar only), `navigationBar` (bottom bar only), `systemBars` (both). Only the starting point — changeable per device in the admin menu. iOS has no navigation bar, so the last two are reinterpreted around the home indicator. See [Screen modes](#screen-modes). |
| `barColor.light` | no (`#FFFFFF`) | Colour of the bars `screenMode` keeps while the device is in light mode, `#RRGGBB` or `#AARRGGBB`. Fixed per build. |
| `barColor.dark` | no (`#FFFFFF`) | The same for dark mode. |
| `allowUnverifiedSsl` | no (`false`) | `true` = the *Allow unverified certificates* switch starts on, for kiosks against a self-signed or internal-CA server. Still togglable per device in the admin menu. On iOS this also writes a narrow App Transport Security exception for the variant's own host, without which the app is never asked about the certificate at all. |
| `allowExternalNavigation` | no (`false`) | `false` = an off-domain link is refused. `true` = it is opened by Android's default handler (browser or a matching app) instead, outside the container. Fixed per build. |
| `allowLocation` | no (`false`) | `true` = the page may ask for the device's position, and the build declares the location permissions. `false` = no location permission in the APK at all, and no usage description in the iOS build, so `navigator.geolocation` fails. Fixed per build. See [Location](#location). |
| `locationReason` | no | Only with `allowLocation: true`. Why this variant wants the position, in one sentence addressed to the user. Android never shows it — the system writes its own prompt — but **iOS terminates an app that asks without one**, so this is the text that build carries. Omitted = a sentence generated from `name`. |
| `icon.glyph` | no (`container`) | Built-in symbol: `container`, `equalizer`, `apps`, `dashboard`, `list`, `menu`, `home`, `monitor`, `chat`, `info`, `lock`, `bolt`, `star`, `circle`, `square`, `triangle`, `diamond`. |
| `icon.vector` | no | Path (from the repo root) to your own 108×108 vector drawable, used instead of a glyph. The iOS build converts it to SVG and rasterises it; the converter refuses anything it cannot reproduce faithfully rather than dropping it. |
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

**Icons are generated, not checked in.** For every variant the Android build
writes an adaptive icon (plus a layered fallback for API 24/25) from the glyph and
colours above into a generated resource folder — see
[`android/buildSrc/src/main/kotlin`](android/buildSrc/src/main/kotlin) — and the
iOS generator rasterises the same glyph to a 1024×1024 `.appiconset`. Nothing
needs to be drawn by hand to tell two installed builds apart.

The symbols themselves live in [`app-icons/glyphs.yaml`](app-icons/glyphs.yaml),
once: a 24×24 `pathData` *is* SVG path data, which is the reason one file can
feed an Android adaptive icon and an iOS app icon. A variant whose symbol is more
than a glyph points `icon.vector` at a 108×108 vector drawable in
[`app-icons/`](app-icons) instead; that file becomes the Android icon foreground
verbatim, so it brings its own colours and `icon.tint` no longer applies, and the
iOS generator converts it to SVG on the way to a PNG.

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

## Screen modes

Android's status and navigation bars are hidden by default: the page owns the
whole display, cutout included. That is right for a dashboard on a wall and
wrong for a build people hold in their hands and read, so it is a setting.

| `screenMode` | What stays on screen |
|---|---|
| `fullscreen` | Neither bar. The default, and what every build did before this setting existed. |
| `statusBar` | The top bar — clock, battery, notifications. The navigation bar stays hidden. |
| `navigationBar` | The bottom navigation bar (back, home, recents). The status bar stays hidden. |
| `systemBars` | Both bars, with the page between them, like an ordinary app. |

A variant picks where it starts in
[`app-variants.yaml`](app-variants.yaml); Admin → **Screen mode** changes it
per device and applies it immediately, no restart. The `gasoline` variant ships
as `statusBar`, everything else as `fullscreen`.

**On iOS there is no navigation bar**, and the home indicator can be dimmed but
never removed, so the four names keep their meaning as best they can:

| `screenMode` | On iOS |
|---|---|
| `fullscreen` | Status bar hidden, home indicator dimmed. |
| `statusBar` | Status bar in view, home indicator dimmed. |
| `navigationBar` | Status bar hidden, home indicator at full strength. |
| `systemBars` | Both in view, the page below the status bar. |

Two further limits there, both listed in
[Platform differences](#platform-differences): iOS force-hides the status bar on
an iPhone in landscape whatever the mode says, and the swipe that reveals a hidden
one also pulls Control Centre.

Two details are worth knowing:

- **A hidden bar can still be swiped in** from its edge and disappears again on
  its own. That is deliberate: nobody is locked out of the clock or the back
  button. Such a bar floats over the page without changing the layout, so the
  page never jumps.
- **A bar that stays gets its own strip.** The page is padded below the status
  bar and above the navigation bar instead of hiding underneath them, and the
  padding follows the bars when the device is rotated. Full-screen mode keeps
  drawing into the display cutout, as before.

### What colour the bars are

A strip only disappears into the page if it is the colour the page has at that
edge, and that colour is usually not the same by day and by night. So a variant
declares both:

```yaml
  - id: portal
    name: Portal
    defaultKioskPath: https://portal.david-grieser.de/
    screenMode: systemBars
    barColor:
      light: "#FAFAFA"     # what the page shows at its edges in light mode
      dark: "#101418"      # …and in dark mode
```

- Only a bar the mode **keeps** is painted. A hidden one has nothing to paint,
  and a transiently swiped-in bar keeps the system's own backdrop over the page.
- The **icons in the bar** — clock, battery, nav glyphs — are darkened or
  lightened to suit the colour behind them, measured by its luminance, so
  neither a near-white nor a near-black strip swallows them.
- The colour follows the **device's** dark-mode setting, and the page is expected
  to follow it too — that is the pair the two keys are for. Switching the system
  theme re-picks the colour. A site with one fixed palette, which looks the same
  either way, wants the same value in both keys.
- The values worth putting here are the page's **own** edge colours, read off the
  site rather than guessed: the `gasoline` variant carries `#F4F2ED` and
  `#0D0E11`, the top pixel of its light and dark rendering.
- A translucent `#AARRGGBB` colour blends with the window background underneath,
  and is judged for icon contrast as it will actually look.
- Both default to `#FFFFFF`, the window background — which is exactly what a
  visible bar showed through before it could be coloured.

## External links

The domain lock decides what may load *inside* the container, and that never
changes: a page from another domain is never shown in the app's own WebView.
What a variant can choose is what happens to a link pointing there.

By default (`allowExternalNavigation: false`) the navigation is refused and a
short message names the domain the app is locked to — the off-domain link simply
does not work, which is the point of a kiosk.

With `allowExternalNavigation: true` in
[`app-variants.yaml`](app-variants.yaml) the link is instead passed to Android
as a plain `ACTION_VIEW` intent, so **the device's default handler** takes it:
usually the browser, or an installed app that claims that link (a YouTube URL
opening the YouTube app, say). The container keeps showing its own page, and the
link opens in a task of its own, so coming back from the browser lands on the
kiosk page again rather than somewhere in its history.

This applies to taps on links, `target="_blank"` and redirects alike — anything
that would otherwise have been blocked.

Two limits are deliberate:

- **Only `http` and `https` are handed over.** Other schemes (`mailto:`,
  `intent:`, custom app schemes) stay blocked as before, so a page cannot use
  the setting as a way to fire arbitrary intents at whatever else is installed.
  The intent also carries `CATEGORY_BROWSABLE`, so only components that expect
  links from the web can receive it.
- **If nothing can open the URL, it counts as blocked** and the usual message is
  shown, rather than the tap doing nothing at all.

Unlike *Allow unverified certificates*, this is not an admin switch: it is fixed
when the APK is built. Whether a kiosk may send its users off to the browser is a
property of that kiosk, not something to be flipped on a device.

## Location

A page that wants to know where the device is — a map, a "what's nearest"
search — needs both halves: the WebView has to allow the request, and Android
has to have granted the app the permission. A variant declares whether it is in
that business at all:

```yaml
  - id: gasoline
    name: Gasoline
    defaultKioskPath: https://gasoline.david-grieser.de/
    allowLocation: true
```

Without it (the default) the setting is not a mere runtime check: **the APK
carries no location permission**, so there is nothing to grant, nothing shows
under the app's permissions on the device, and `navigator.geolocation` fails
immediately — an answer the page can handle, rather than a request that hangs.
The permissions and the (optional) GPS feature are generated into that variant's
manifest only, next to its launcher icon; see
[`android/buildSrc/src/main/kotlin`](android/buildSrc/src/main/kotlin).

With it, the first request from the page brings up **the system's own permission
prompt**. On Android it asks for the precise and approximate permissions together
so the user can pick either — the coarse one still yields a position — and three
things are checked, in order: the build allows location, the asking origin is
inside the domain lock, and the permission is held.

- **The domain lock applies here too — on Android.** An embedded third-party
  frame cannot borrow the permission the anchored site was granted; such a
  request is refused and the app says which host asked. This mirrors how the TLS
  bypass is limited. **On iOS this check does not exist**, because `WKWebView`
  answers the page itself and never says which origin asked. The build-level gate
  is intact, and it is the stronger half — a variant without `allowLocation`
  carries no usage description, so it cannot ask at all — but a frame inside a
  page on an `allowLocation` build can use the permission the site was granted.
  This is the one security property the iOS app is weaker on, and it is a
  deliberate choice: the alternative is shimming `navigator.geolocation` with
  injected JavaScript, which reimplements the W3C API, is itself escapable from a
  script-created frame, and is a maintenance surface Android never had.
- **iOS asks once, ever.** A denial is permanent until the user visits Settings,
  so the app offers to take them there rather than simply reporting the refusal.
  Android's prompt can come back, so it does not need to.
- **Nothing is remembered by the WebView.** The answer is given afresh each time,
  so revoking the permission in Android's settings takes effect immediately.
  There is no in-app switch to undo a grant, because Android already has one —
  *Settings → Apps → this app → Permissions → Location* — and that is the switch
  the system itself honours.
- **A denied prompt is explained once per page load**, then the page is left to
  its own error handling.

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

`app-variants.yaml`, `app-icons/` and `sample/` sit at the repository root
because both builds read them; each platform's build lives in its own directory.

### Android

Requires the Android SDK (platform 34, build-tools 34.x) and JDK 17.

```bash
cd android

# Point the build at your SDK (or set ANDROID_HOME):
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug             # debug APK of every variant
./gradlew assembleContainerDebug    # just one variant
./gradlew assembleRelease           # signed release (needs keystore.properties, see below)
```

Output: `android/app/build/outputs/apk/<variant>/<build type>/`, e.g.
`android/app/build/outputs/apk/container/release/`.

The variant definitions are parsed and the launcher icons generated by the small
Gradle plugin code in [`android/buildSrc`](android/buildSrc); its unit tests run
as part of every Gradle invocation, so an invalid `app-variants.yaml` is reported
immediately.

### iOS

Requires Xcode 15 and [XcodeGen](https://github.com/yonaskolb/XcodeGen). The
Xcode project is generated from `app-variants.yaml` rather than checked in — in
the same spirit as the launcher icons.

```bash
brew install xcodegen cairo
pip3 install -r ios/tools/requirements.txt

cd ios
make project    # generate everything, then write Container.xcodeproj
make open       # …and open it
make test
```

One scheme per variant, named after its `id`, so `xcodebuild -scheme podcaster`
is the analogue of `assemblePodcasterRelease`. Adding a variant is an edit to
`app-variants.yaml` and a `make project`, and nothing else.

Everything except turning the spec into an `.xcodeproj` and compiling it runs
without a Mac — including drawing the app icons, since a 24×24 `pathData` is SVG
path data. See [`ios/README.md`](ios/README.md) for the whole picture.

## Signing

### Android

Release signing reads from a git-ignored `keystore.properties` in
[`android/`](android), next to `settings.gradle.kts`. Copy the example and fill
in your details (you mentioned you'll add the signing material to the repo
separately):

```bash
cd android
cp keystore.properties.example keystore.properties
# edit keystore.properties, and place your .jks alongside it
```

If `keystore.properties` is absent, release builds are simply left unsigned
(the build still succeeds), so the project builds for anyone without the
signing secrets. `keystore.properties`, `*.jks` and `*.keystore` are
git-ignored.

### iOS

Release builds are **unsigned**, and that is the intended state rather than a
gap: the apps are distributed through an AltStore source, and AltStore installs
one by re-signing it on the device with the installing user's own Apple ID. No
Apple Developer account, no distribution certificate and no provisioning profiles
are involved.

The asymmetry with Android is worth stating plainly, though. An unsigned APK can
be signed by anyone with `apksigner` and installed; an unsigned `.ipa` **cannot
be installed on a stock iPhone** without an Apple-issued identity. AltStore
supplies that identity on the user's own device — there is no offline equivalent.

To sign a *local* build so it runs on your own device, copy
`ios/signing.local.xcconfig.example` to `ios/signing.local.xcconfig` and fill in
your team. It is git-ignored and included only if present, the same pattern as
`keystore.properties`.

## Releasing (CI)

### Android

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

**The tag is the version.** `versionName` is the tag with any leading `v`
removed (plus the variant's own `versionNameSuffix`, so `v1.2.3` gives
`1.2.3-gasoline`), and `versionCode` is derived from it as
`major * 10000 + minor * 100 + patch` — `v0.0.9` → 9, `v0.1.0` → 100, `v1.2.3`
→ 10203. That ordering is what lets a device see a new release as an upgrade of
the last; a tag whose minor or patch would reach 100 and break the ordering
fails the build instead of shipping. Releases up to and including `v0.0.8` all
carried `versionCode 1`, so any tag from here on supersedes them.

A build outside a tagged checkout asks git instead (`cd android && ./gradlew assembleContainerDebug`
on this working copy produces something like `0.0.8-12-gabc1234-dirty`), so a
locally built APK says which commit it came from rather than claiming to be a
release. Pass `-PappVersion=v1.2.3` (or set `APP_VERSION`) to override, which is
exactly what the workflow does with the pushed tag.

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

### iOS, and the AltStore source

`.github/workflows/ios-release_ci.yml` fires on the same tags, takes its matrix
from the same [`app-variants.yaml`](app-variants.yaml), and attaches
`container-app-<variant>-<tag>-ios.ipa` and its dSYMs to the same release.

It then publishes an **AltStore source** to GitHub Pages:

```
https://<owner>.github.io/<repo>/source.json
```

Add that URL in AltStore and each variant appears as an app to install. The
source is a pure function of `app-variants.yaml` plus the release — the names,
bundle ids, tint colours and icons all come from the variants — so adding a
variant adds an app to the source with nothing else to edit. Each release
*appends* its version rather than replacing the list, so anyone on an older build
can still install what they have.

Two things to know:

- **GitHub Pages has to be enabled once, by hand**: Settings → Pages → Source:
  GitHub Actions. A workflow cannot do it, and the publish step fails until it is.
- **No secrets are needed.** If certificate or App Store Connect secrets are
  present the workflow fails loudly rather than quietly changing what it
  produces, because a signed path has to be a deliberate edit.

The version arithmetic is shared, with one exception: `CFBundleVersion` takes
Android's `versionCode` unchanged, so the ordering rule and its ceiling transfer
exactly, but `CFBundleShortVersionString` must be one to three integers — so
`versionNameSuffix` lives in a custom `ContainerVersionName` key and in the
artifact name instead. `v1.2.3` gives `1.2.3` / `10203` on both platforms.

**AltStore PAL** — the EU alternative app *marketplace* — is a different route,
and not what this is: it needs Developer Program membership, the EU Alternative
Terms Addendum, Apple notarization of every build and an
alternative-distribution certificate. See [`ios/README.md`](ios/README.md).

## Platform differences

Both apps are built from one `app-variants.yaml` and behave the same wherever
they can. This is the list of places where they cannot, so that nothing here is a
surprise later.

| Android | iOS |
|---|---|
| Location is gated by the asking origin, so an embedded third-party frame cannot borrow the anchored site's permission. | **No such gate.** `WKWebView` answers the page itself and never says which origin asked. The build-level `allowLocation` gate still holds, and is the stronger half. |
| `allowUnverifiedSsl` is one switch. | Two halves, both required: a narrow ATS exception for the variant's own host (or the app is never asked) *and* the app accepting the certificate. ATS relaxes TLS versions and ciphers; it cannot accept an invalid certificate. |
| A bad certificate on any host inside the domain is waived. | The trust challenge may not be delivered for a cross-origin *subresource*, so a broken certificate on a third-party asset can still fail with the switch on. |
| `clearSslPreferences()` clears remembered per-host decisions. | None are remembered, so there is nothing to clear; the toggle only drops cached responses. |
| `screenMode: navigationBar` and `systemBars` keep the navigation bar. | There is no navigation bar. Both are reinterpreted around the home indicator, which can be dimmed but never hidden. |
| `statusBar` and `systemBars` keep the top bar. | iOS force-hides the status bar on an **iPhone in landscape**, whatever the mode says. This affects `gasoline` in ordinary use. |
| A hidden bar can be swiped in for a moment. | So it can, but the same gesture pulls Control Centre, so in `fullscreen` the clock is genuinely harder to reach. |
| `barColor` paints the system bars. | There is no bar background to set; the app paints its own view behind the safe area. The bar's *contents* still follow the same 0.179 luminance threshold. |
| Back navigates history, then stays in the container. | No back button, and no way for an app to background itself. History is the edge swipe; nothing stands in for the second half. |
| `Toast` messages, which can outlive the app. | An in-app banner, which cannot. |
| No permission purpose strings needed. | iOS terminates an app that asks for location without one — hence `locationReason`. A variant pinned to a bare host name also trips the Local Network prompt. |
| `versionNameSuffix` is part of the version name. | `CFBundleShortVersionString` must be one to three integers, so the suffix lives in `ContainerVersionName` and the artifact name. |
| One keystore signs everything; an `applicationId` needs no registration. | Each bundle id is an App ID to register once signing is introduced. AltStore's on-device re-signing sidesteps it for now. |
| An unsigned APK can be signed by anyone with `apksigner`. | An unsigned `.ipa` needs an Apple-issued identity, which AltStore supplies on the user's own device. There is no offline equivalent. |
| Adaptive icon, with a `<monochrome>` layer available. | One square image the system masks itself. The nearest analogue of the monochrome layer is the iOS 18 tinted appearance. |
| R8 minification and resource shrinking. | No equivalent; Swift only dead-strips. Larger artifacts, no behavioural difference. |
| Lock Task / screen pinning via MDM. | Guided Access, or Single App Mode via a supervised device and an MDM. |

Two of these are worth calling out as *choices* rather than limits. The location
gate could be restored by shimming `navigator.geolocation` with injected
JavaScript; it was judged not worth reimplementing the W3C API for a check that a
script-created frame could still escape. And off-domain *sub-frames* on iOS are
blocked but deliberately not handed to the browser even in a variant with
`allowExternalNavigation: true` — being thrown out of the app because an advert
iframe loaded would be worse than Android's plain refusal.

## Notes on "kiosk"

This app is a **single-purpose, navigation-locked container**. It does not by
itself pin the device (Android Lock Task / screen pinning) — that requires the
device to be provisioned as device-owner or the app added to a lock-task
allowlist by an MDM. If you want the device fully locked to this app, enroll it
with your MDM and add this app to the lock-task allowlist, or use screen
pinning; the app is already structured to behave well in that mode (it stays in
the container on Back instead of exiting).

The iOS counterpart is **Guided Access** (Settings → Accessibility, turned on by
the person holding the device) or, for a fleet, **Single App Mode** — or
Autonomous Single App Mode — pushed by an MDM to a supervised device. Neither is
something the app itself can request, exactly as on Android.

## Project layout

The repository root holds what both platforms read; each platform's build lives
in its own directory.

```
app-variants.yaml        Which apps to build: name, symbol, kiosk config, behaviour
app-icons/               Hand-drawn launcher symbols referenced by `icon.vector`
sample/kiosk.json        Example of the remote configuration file

android/                 The Gradle build
android/buildSrc/src/main/kotlin/
  AppVariants.kt         Parses & validates app-variants.yaml
  AppVersion.kt          Derives versionName / versionCode from the git tag
  ScreenModes.kt         The screen-mode names the build accepts
  LauncherGlyphs.kt      The built-in launcher symbols
  GenerateLauncherIconsTask.kt  Writes each variant's icon resources
  GenerateVariantManifestTask.kt  Writes the manifest entries only some variants get
android/app/src/main/java/de/davidgrieser/container/
  MainActivity.kt        UI: WebView, pull-to-refresh, FAB, menu sheet, admin & PIN dialogs
  ScreenMode.kt          Which system bars each screen mode keeps on screen
  SystemBarColors.kt     What those bars are painted in, light mode and dark
  KioskWebViewClient.kt  Enforces the domain lock (& hand-off to the browser) and the TLS-error policy
  InsecureSsl.kt         Opt-in trust-all TLS for the app's own HTTP calls
  DomainRules.kt         Host / domain matching rules
  ConfigRepository.kt    Fetches & parses the remote JSON (with caching)
  Prefs.kt               Persisted state (PIN hash, config URL, screen mode, cache, selection)
  PinManager.kt          Salted, iterated PIN hashing & verification
  IconLoader.kt          Tiny dependency-free menu-icon loader

ios/                     The Xcode project, generated from app-variants.yaml
ios/project.yml          How the apps are built (hand-written)
ios/tools/               The generator: parses the YAML, writes targets, draws icons
ios/ContainerKit/Sources/ContainerKit/
  ContainerApp.swift     Entry point; each variant's target is one call into it
  Core/DomainRules.swift       Host / domain matching rules
  Core/NavigationPolicy.swift  What happens to a navigation, as a pure function
  Core/ConfigParser.swift      Parses the remote JSON
  Core/KioskPathResolver.swift Which page opens, and what the menu offers
  Core/PinHasher.swift         The Android hashing scheme, ported exactly
  Core/ScreenMode.swift        The four modes, reinterpreted for iOS
  Core/BarColor.swift          Bar colours and the icon-contrast threshold
  Core/VariantConfig.swift     What the generator fills in per variant
  Data/…                       Prefs, keychain, HTTP, config, icons, TLS, location
  UI/KioskViewController.swift The whole screen: web view, button, dialogs
  UI/KioskWebView.swift        Enforces the domain lock and the TLS policy
  UI/ScreenModePolicy.swift    Which bars stay, and what the strip is painted
ios/Tests/ContainerKitTests/   Tests for the pure core

Shared by both builds:
app-variants.schema.json The normative key sets, types, enums and defaults
app-icons/glyphs.yaml    The built-in launcher symbols, as SVG path data
variants-testdata/       Accept/reject cases both parsers are held to
```
