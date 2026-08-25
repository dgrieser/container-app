"""Writes each target's Info.plist: the keys iOS itself reads.

The counterpart of `GenerateVariantManifestTask` on the Android side, and it
exists for the same reason: some entries belong only to some variants, and
generating them per target is what keeps a build that has no business asking for
a permission from declaring one.

Behaviour never comes from here. The app reads its configuration from the
generated `VariantConfig.swift`, so there is one representation of each value;
the plist carries what the *system* needs, plus `ContainerVariantId` and
`ContainerVersionName` so a built `.app` can be identified without running it
(`plutil -p Container.app/Info.plist`, the `aapt dump badging` analogue).

The location and transport-security keys are the two that differ per variant, and
both are worth reading the comments on below.
"""

from __future__ import annotations

import plistlib
import urllib.parse

from variants import Variant

#: iOS force-hides the status bar on a compact-height screen, so a phone in
#: landscape loses it whatever this says. Portrait-upside-down is left to the
#: iPad, matching what a hand-held phone actually does.
PHONE_ORIENTATIONS = [
    "UIInterfaceOrientationPortrait",
    "UIInterfaceOrientationLandscapeLeft",
    "UIInterfaceOrientationLandscapeRight",
]
PAD_ORIENTATIONS = PHONE_ORIENTATIONS + ["UIInterfaceOrientationPortraitUpsideDown"]


def _is_bare_hostname(url: str) -> bool:
    """True for a host with no dot in it, i.e. a name only a local network resolves.

    `https://raspberrypi` is the case that matters: iOS treats it as local
    networking, which needs both an ATS allowance and the Local Network privacy
    prompt. Android needed neither.
    """
    host = urllib.parse.urlsplit(url).hostname or ""
    return bool(host) and "." not in host


def _app_transport_security(variant: Variant) -> dict:
    """ATS for one variant, and never `NSAllowsArbitraryLoads`.

    This is the single most misunderstood part of the port, so it is spelled out:
    ATS relaxes TLS *version and cipher* requirements and can permit cleartext.
    It **cannot** accept an invalid certificate. So `allowUnverifiedSsl` is not
    implemented here at all -- it is implemented in the app, by the trust
    challenge in the navigation delegate and the URLSession delegate, mirroring
    `KioskWebViewClient.onReceivedSslError` and `InsecureSsl.kt`.

    What this does is stop ATS from refusing such a connection *before* the
    delegate is ever consulted. A missing exception makes the delegate
    unreachable; a missing delegate makes the exception useless. Both halves are
    required, and neither is a substitute for the other.

    `NSAllowsArbitraryLoads: true` is never emitted. Android blocks cleartext
    outright (`usesCleartextTraffic="false"` plus its network security config),
    and "the switch relaxes certificate verification, not the requirement to use
    TLS" is a property worth keeping identical on both platforms.
    """
    security: dict = {
        "NSAllowsArbitraryLoads": False,
        "NSAllowsArbitraryLoadsInWebContent": False,
    }
    if not variant.allow_unverified_ssl:
        return security

    # An internal server with a self-signed certificate often also speaks older
    # TLS than ATS accepts by default, so the variant that opted into trusting it
    # gets a matching exception -- scoped to its own host where there is one to
    # scope to.
    pinned = variant.default_kiosk_path or variant.config_url
    host = urllib.parse.urlsplit(pinned).hostname if pinned else None
    if host:
        security["NSExceptionDomains"] = {
            host: {
                "NSIncludesSubdomains": True,
                "NSExceptionRequiresForwardSecrecy": False,
                "NSExceptionMinimumTLSVersion": "TLSv1.0",
            }
        }
    if pinned and _is_bare_hostname(pinned):
        security["NSAllowsLocalNetworking"] = True
    return security


def info_plist(variant: Variant) -> dict:
    plist: dict = {
        "CFBundleDisplayName": variant.name,
        "CFBundleName": variant.name,
        "CFBundleIdentifier": "$(PRODUCT_BUNDLE_IDENTIFIER)",
        "CFBundleExecutable": "$(EXECUTABLE_NAME)",
        "CFBundlePackageType": "$(PRODUCT_BUNDLE_PACKAGE_TYPE)",
        "CFBundleShortVersionString": "$(MARKETING_VERSION)",
        "CFBundleVersion": "$(CURRENT_PROJECT_VERSION)",
        # Not read by iOS: how a built .app says which variant and which tag it is.
        "ContainerVariantId": variant.id,
        "ContainerVersionName": "$(CONTAINER_VERSION_NAME)",
        "UILaunchScreen": {},
        # Without this key UIKit takes the pre-scene path, never calls the scene
        # delegate, and the app shows a black screen. One scene: a kiosk shows one
        # page, and multiple windows would each need their own domain lock.
        "UIApplicationSceneManifest": {"UIApplicationSupportsMultipleScenes": False},
        "UISupportedInterfaceOrientations": PHONE_ORIENTATIONS,
        "UISupportedInterfaceOrientations~ipad": PAD_ORIENTATIONS,
        # The view controller decides, because the screen mode is a per-device
        # setting the admin menu changes without restarting.
        "UIViewControllerBasedStatusBarAppearance": True,
        "UIStatusBarHidden": not variant.screen_mode_shows_status_bar,
        "NSAppTransportSecurity": _app_transport_security(variant),
    }

    if variant.allow_location:
        # The iOS analogue of the per-variant manifest permission: a build that
        # does not declare this cannot ask for a position at all -- iOS terminates
        # an app that tries -- which is as close as iOS gets to Android's "the APK
        # carries no location permission".
        plist["NSLocationWhenInUseUsageDescription"] = variant.location_purpose

    if variant.allow_unverified_ssl and _is_bare_hostname(
        variant.default_kiosk_path or variant.config_url
    ):
        plist["NSLocalNetworkUsageDescription"] = (
            f"{variant.name} connects to a server on your local network."
        )

    return plist


def dumps(variant: Variant) -> bytes:
    return plistlib.dumps(info_plist(variant), sort_keys=True)
