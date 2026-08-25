"""Writes each target's generated Swift: its `VariantConfig` and its entry point.

This is the iOS counterpart of Android's `buildConfigField` block. A generated
Swift file rather than Info.plist lookups, for two reasons: a typo becomes a
compile error instead of a runtime nil, and the values arrive typed -- a screen
mode as an enum case, a colour as a parsed integer -- so the runtime has no
"unparseable, fall back to something" path to get wrong.

Two consequences worth stating, because they are improvements over the Android
side rather than translations of it:

* `screenMode` is emitted as `.statusBar`, not `"statusBar"`. On Android the
  build's list of mode names and the app's enum are kept in step by a regex test,
  because Gradle cannot type-check across that boundary. Here the Swift compiler
  does it: a mode dropped from `ScreenMode.swift` fails to compile.
* Colours are parsed here, by code that has already validated the `#RRGGBB`
  format, so `BarColor` takes a number and the runtime has no parse to fail.

Since that compile only happens on a Mac, `tests/test_swift_contract.py` also
checks the emitted field list against `VariantConfig.swift` by reading it, so the
mismatch is caught on Linux too.
"""

from __future__ import annotations

import re

from variants import Variant
from version import AppVersion

HEADER = "// Generated from app-variants.yaml by ios/tools/generate.py — do not edit.\n"

#: The order the fields are emitted in, which is the order `VariantConfig.init`
#: declares them. `test_swift_contract.py` asserts the two agree.
FIELD_ORDER = (
    "id",
    "displayName",
    "bundleIdentifier",
    "defaultConfigURL",
    "defaultKioskPath",
    "requirePin",
    "showMenu",
    "screenMode",
    "barColorLight",
    "barColorDark",
    "allowUnverifiedSSL",
    "allowExternalNavigation",
    "allowLocation",
    "versionName",
)


def swift_string(value: str) -> str:
    """A Swift string literal. The values are validated, but `\\` and `"` still escape."""
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def swift_argb(color: str) -> str:
    """`#F4F2ED` or `#CCF4F2ED` as `0xFF_F4_F2_ED`, grouped for readability."""
    if not re.fullmatch(r"#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})", color):
        raise ValueError(f"colour `{color}` should have been rejected by the parser")
    digits = color[1:].upper()
    if len(digits) == 6:
        digits = "FF" + digits
    return "0x" + "_".join(digits[i:i + 2] for i in range(0, 8, 2))


def variant_config(variant: Variant, version: AppVersion) -> str:
    values = {
        "id": swift_string(variant.id),
        "displayName": swift_string(variant.name),
        "bundleIdentifier": swift_string(variant.application_id),
        "defaultConfigURL": swift_string(variant.config_url),
        "defaultKioskPath": swift_string(variant.default_kiosk_path),
        "requirePin": "true" if variant.require_pin else "false",
        "showMenu": "true" if variant.show_menu else "false",
        "screenMode": f".{variant.screen_mode}",
        "barColorLight": f"BarColor(argb: {swift_argb(variant.bar_color.light)})",
        "barColorDark": f"BarColor(argb: {swift_argb(variant.bar_color.dark)})",
        "allowUnverifiedSSL": "true" if variant.allow_unverified_ssl else "false",
        "allowExternalNavigation": "true" if variant.allow_external_navigation else "false",
        "allowLocation": "true" if variant.allow_location else "false",
        "versionName": swift_string(version.version_name(variant.version_name_suffix)),
    }
    assert set(values) == set(FIELD_ORDER), "FIELD_ORDER is out of step with the values"

    arguments = ",\n".join(f"        {key}: {values[key]}" for key in FIELD_ORDER)
    return (
        f"{HEADER}"
        f"//\n"
        f"// The `{variant.id}` variant. Everything here comes from its entry in\n"
        f"// app-variants.yaml; change it there, then run `make project`.\n"
        f"\n"
        f"import ContainerKit\n"
        f"\n"
        f"enum Variant {{\n"
        f"    static let current = VariantConfig(\n"
        f"{arguments}\n"
        f"    )\n"
        f"}}\n"
    )


def main_swift(variant: Variant) -> str:
    """The whole app target: one call into the shared package.

    Everything else lives in `ContainerKit`, so adding a source file needs no
    project regeneration -- SwiftPM globs at build time where XcodeGen globs at
    generation time.
    """
    return (
        f"{HEADER}"
        f"\n"
        f"import ContainerKit\n"
        f"\n"
        f"ContainerApp.main(variant: Variant.current)\n"
    )
