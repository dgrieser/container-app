#!/usr/bin/env python3
"""Generates everything about the iOS project that comes from app-variants.yaml.

    python3 ios/tools/generate.py                     # everything
    python3 ios/tools/generate.py --skip-icons        # no CairoSVG needed
    python3 ios/tools/generate.py --only-icons        # just the .appiconset s
    python3 ios/tools/generate.py --dump-json         # for the parity diff
    python3 ios/tools/generate.py --app-version v1.2.3

Writes, all of it under `ios/Generated/` and all of it gitignored:

    variants.yml                  XcodeGen targets + schemes, one per variant
    Version.xcconfig              MARKETING_VERSION / CURRENT_PROJECT_VERSION
    Variants/<id>/main.swift      the whole app target: one call into ContainerKit
    Variants/<id>/VariantConfig.swift   this variant's values, typed
    Variants/<id>/Info.plist      the keys iOS itself reads
    Variants/<id>/Assets.xcassets/AppIcon.appiconset/

Nothing here needs a Mac or Xcode, deliberately: the icons rasterise on Linux,
the spec is YAML, and the whole lot is validated by `ios/tools/tests/`. Only
turning the spec into a `.xcodeproj` (`xcodegen generate`) and compiling it need
macOS, which is why CI renders the icons on its Linux leg and hands them over.
"""

from __future__ import annotations

import argparse
import pathlib
import shutil
import sys

import glyphs as glyphs_module
import icons
import paths
import plists
import swiftgen
import variants as variants_module
import version as version_module
import xcodegen_spec


def _write(file: pathlib.Path, content: str | bytes) -> None:
    file.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, bytes):
        file.write_bytes(content)
    else:
        file.write_text(content, encoding="utf-8")


def generate(
    *,
    app_version: str | None = None,
    skip_icons: bool = False,
    only_icons: bool = False,
    clean: bool = True,
) -> list[variants_module.Variant]:
    glyphs = glyphs_module.load(paths.GLYPHS_FILE)
    declared = variants_module.load(paths.VARIANTS_FILE, glyphs=glyphs)
    resolved = version_module.resolve(app_version, paths.REPO_ROOT)

    if clean and not only_icons and paths.GENERATED.exists():
        # The generated tree is the only place a removed variant could linger, and
        # a stale target directory is a target XcodeGen would happily keep
        # building.
        shutil.rmtree(paths.GENERATED)

    if not only_icons:
        _write(paths.VARIANTS_SPEC, xcodegen_spec.dumps(declared))
        _write(paths.VERSION_XCCONFIG, version_module.xcconfig(resolved))

    for variant in declared:
        target = paths.variant_dir(variant.id)
        if not only_icons:
            _write(target / "main.swift", swiftgen.main_swift(variant))
            _write(
                target / "VariantConfig.swift",
                swiftgen.variant_config(variant, resolved),
            )
            _write(target / "Info.plist", plists.dumps(variant))
            _write(
                target / "Variant.xcconfig",
                _variant_xcconfig(variant, resolved),
            )
        if not skip_icons:
            target.mkdir(parents=True, exist_ok=True)
            icons.write(variant, glyphs, paths.REPO_ROOT, target)

    return declared


def _variant_xcconfig(
    variant: variants_module.Variant, resolved: version_module.AppVersion
) -> str:
    """The per-variant settings Info.plist substitutes into itself.

    Only what the plist needs by name. Everything else a target needs comes from
    the `Variant` template in `ios/project.yml`, so a build setting is changed in
    one hand-written place rather than in three generated ones.
    """
    return (
        "// Generated from app-variants.yaml by ios/tools/generate.py — do not edit.\n"
        "//\n"
        "// A target-level config file replaces the project-level one rather than\n"
        "// adding to it, so the shared settings are chained in from here.\n"
        '#include "../../../Config/Base.xcconfig"\n'
        '#include "../../Version.xcconfig"\n'
        "\n"
        f"CONTAINER_VERSION_NAME = "
        f"{resolved.version_name(variant.version_name_suffix)}\n"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--app-version",
        help="The tag this build is (e.g. v1.2.3). Without it, git describe is asked.",
    )
    parser.add_argument(
        "--skip-icons",
        action="store_true",
        help="Do not draw the app icons. The project still opens and builds; Xcode "
             "warns about the missing image. Use it where CairoSVG is not installed.",
    )
    parser.add_argument(
        "--only-icons",
        action="store_true",
        help="Draw only the app icons, leaving everything else in place. This is "
             "how CI renders them on a Linux runner for the macOS one to use.",
    )
    parser.add_argument(
        "--dump-json",
        action="store_true",
        help="Print the fully-resolved variant model as canonical JSON and exit. "
             "Gradle's dumpVariantsJson prints the same document; CI diffs them, "
             "which is what catches the two parsers disagreeing about a default.",
    )
    args = parser.parse_args(argv)

    if args.skip_icons and args.only_icons:
        parser.error("--skip-icons and --only-icons contradict each other")

    try:
        if args.dump_json:
            declared = variants_module.load(paths.VARIANTS_FILE)
            print(variants_module.dump_json(declared), end="")
            return 0

        declared = generate(
            app_version=args.app_version,
            skip_icons=args.skip_icons,
            only_icons=args.only_icons,
        )
    except (
        variants_module.VariantError,
        glyphs_module.GlyphError,
        version_module.VersionError,
        icons.IconError,
    ) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    what = "app icons" if args.only_icons else "targets"
    print(
        f"Generated {len(declared)} {what} in "
        f"{paths.GENERATED.relative_to(paths.REPO_ROOT)}: "
        f"{', '.join(v.id for v in declared)}"
    )
    if not args.only_icons:
        print("Next: cd ios && make project   (or: xcodegen generate --spec project.yml)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
