"""Writes the AltStore source that distributes these apps.

An AltStore source is a JSON file at a stable URL listing apps and pointing at
`.ipa` downloads; a user adds the URL in AltStore and installs from it. AltStore
re-signs each app on the device with the user's own Apple ID, which is why the
artifact CI produces is *unsigned* -- that is the intended input here, not a
fallback for want of a certificate. It is also the one route that needs no Apple
Developer account, no distribution certificate and no provisioning profile.

The source is a pure function of `app-variants.yaml` plus the release: the names,
bundle ids and tint colours come from the variants, the icons are the ones the
generator already renders, and the download URLs are the release assets. Adding a
variant therefore adds an app to the source with nothing else to edit.

`versions` is a list, newest first, so AltStore can still offer an older build;
`update` merges a new release into an existing source rather than replacing it.

Not covered here: AltStore **PAL**, the EU alternative *marketplace*. That is a
different route -- Developer Program membership, the EU Alternative Terms
Addendum, Apple notarization of every build, an alternative-distribution
certificate and a `marketplaceID` per app -- none of which can be produced
without an Apple account. The shape below leaves room for it.
"""

from __future__ import annotations

import json

from variants import Variant
from version import AppVersion

#: Bumped only if the source's own identity changes; AltStore keys subscriptions
#: on the URL, and the identifier is what it uses to recognise the same source.
SOURCE_IDENTIFIER = "de.davidgrieser.container.source"

#: The oldest iOS the apps are built for; shown by AltStore before installing.
MIN_OS_VERSION = "15.0"


def _tint(color: str) -> str:
    """AltStore wants `RRGGBB` with no `#`, and no alpha."""
    digits = color.lstrip("#")
    return (digits[2:] if len(digits) == 8 else digits).upper()


def app_entry(
    variant: Variant,
    version: AppVersion,
    *,
    repository_url: str,
    assets_url: str,
    download_url: str,
    size: int,
    date: str,
    developer_name: str,
    release_notes: str = "",
) -> dict:
    return {
        "name": variant.name,
        "bundleIdentifier": variant.application_id,
        "developerName": developer_name,
        "subtitle": _subtitle(variant),
        "localizedDescription": _description(variant),
        "iconURL": f"{assets_url}/icons/{variant.id}.png",
        "tintColor": _tint(variant.icon.background),
        "category": "utilities",
        "screenshots": [],
        "versions": [
            {
                "version": version.short,
                "buildVersion": str(version.code),
                "date": date,
                "localizedDescription": release_notes,
                "downloadURL": download_url,
                "size": size,
                "minOSVersion": MIN_OS_VERSION,
            }
        ],
        "appPermissions": _permissions(variant),
    }


def _subtitle(variant: Variant) -> str:
    if not variant.show_menu:
        return f"{variant.name}, as an app."
    return "A kiosk container for a remotely configured set of web apps."


def _description(variant: Variant) -> str:
    lines = [
        f"{variant.name} shows a web app inside a navigation-locked container: "
        f"links that would leave the page's own domain "
        + (
            "open in your browser instead of inside the app."
            if variant.allow_external_navigation
            else "do not work, which is the point of a kiosk."
        )
    ]
    if variant.show_menu:
        lines.append(
            "The list of apps it offers is read from a configuration file on the "
            "server, so it changes without an app update."
        )
    if variant.require_pin:
        lines.append("A PIN-protected admin menu holds the settings.")
    if variant.allow_location:
        lines.append(variant.location_purpose)
    return "\n\n".join(lines)


def _permissions(variant: Variant) -> dict:
    privacy = []
    if variant.allow_location:
        privacy.append(
            {"name": "NSLocationWhenInUseUsageDescription", "usageDescription": variant.location_purpose}
        )
    return {"entitlements": [], "privacy": privacy}


def source(
    apps: list[dict],
    *,
    repository_url: str,
    website: str | None = None,
) -> dict:
    return {
        "name": "Container Apps",
        "identifier": SOURCE_IDENTIFIER,
        "subtitle": "Kiosk container apps built from one code base.",
        "description": (
            "Single-purpose, navigation-locked containers for web apps, built from "
            "one code base: github.com/dgrieser/container-app."
        ),
        "website": website or repository_url,
        "apps": apps,
        "news": [],
    }


def merge(previous: dict | None, current: dict) -> dict:
    """Folds a new release into an existing source, keeping older versions.

    AltStore offers whatever `versions` lists, so a release appends rather than
    replaces: someone on an older build can still install what they already have.
    A rebuild of the same version replaces that entry instead of duplicating it.
    """
    if not previous:
        return current

    previous_by_id = {app.get("bundleIdentifier"): app for app in previous.get("apps", [])}
    for app in current["apps"]:
        old = previous_by_id.get(app["bundleIdentifier"])
        if not old:
            continue
        new_version = app["versions"][0]
        kept = [
            version
            for version in old.get("versions", [])
            if version.get("version") != new_version.get("version")
            or version.get("buildVersion") != new_version.get("buildVersion")
        ]
        app["versions"] = [new_version, *kept]
    return current


def dumps(document: dict) -> str:
    return json.dumps(document, indent=2, ensure_ascii=False) + "\n"
