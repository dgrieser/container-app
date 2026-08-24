"""Writes the half of the XcodeGen spec that changes when a variant is added.

`ios/project.yml` is hand-written and holds everything about *how* the app is
built: the deployment target, the warnings, the package reference, and one
`targetTemplates.Variant` carrying every setting a variant target needs. This
file writes `ios/Generated/variants.yml`, which holds only `targets:` and
`schemes:` -- one entry each per variant, each target being the template plus a
couple of attributes. XcodeGen deep-merges the two through `include:`, and both
of those keys are dictionaries keyed by name, so the merge is exactly right.

The split is the point. Adding a variant touches only the generated half, and
changing how the app is built touches only the hand-written half, so neither
edit can clobber the other -- and neither requires touching a `.pbxproj`, which
is the whole reason a generator exists here.

Target name = scheme name = variant id, so `xcodebuild -scheme podcaster` is the
direct analogue of `assemblePodcasterRelease`.
"""

from __future__ import annotations

import yaml

from variants import Variant

HEADER = (
    "# Generated from app-variants.yaml by ios/tools/generate.py — do not edit.\n"
    "#\n"
    "# One target and one scheme per variant. Everything about how they are built\n"
    "# lives in ../project.yml, in the `Variant` target template.\n"
)


def spec(variants: list[Variant]) -> dict:
    targets = {
        variant.id: {
            "templates": ["Variant"],
            "templateAttributes": {
                "variantId": variant.id,
                "bundleId": variant.application_id,
                "productName": variant.name,
            },
        }
        for variant in variants
    }
    schemes = {
        variant.id: {
            "build": {"targets": {variant.id: "all"}},
            "run": {"config": "Debug"},
            # No `test:` block: the tests live in the ContainerKit package and run
            # as `swift test`, so naming them here would reference an Xcode target
            # that does not exist.
            "profile": {"config": "Release"},
            "analyze": {"config": "Debug"},
            "archive": {"config": "Release"},
        }
        for variant in variants
    }
    return {"targets": targets, "schemes": schemes}


def dumps(variants: list[Variant]) -> str:
    return HEADER + yaml.safe_dump(
        spec(variants), sort_keys=False, default_flow_style=False, width=100
    )
