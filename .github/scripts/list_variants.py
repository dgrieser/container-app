#!/usr/bin/env python3
"""Print the variants declared in app-variants.yaml as a JSON build matrix.

Used by the release workflow to build one APK per variant. Only the fields the
workflow needs are emitted; the Gradle build (android/buildSrc/src/main/kotlin)
remains the authority on validating the rest of the file.
"""

import json
import pathlib
import re
import sys

import yaml

ID_PATTERN = re.compile(r"^[a-z][a-z0-9]*$")
REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]


def main() -> int:
    config_file = REPO_ROOT / "app-variants.yaml"
    document = yaml.safe_load(config_file.read_text(encoding="utf-8")) or {}
    variants = document.get("variants") or []
    if not variants:
        print(f"{config_file.name}: no variants declared", file=sys.stderr)
        return 1

    matrix = []
    for index, variant in enumerate(variants):
        variant_id = str(variant.get("id", "")).strip()
        if not ID_PATTERN.match(variant_id):
            print(
                f"{config_file.name}: variants[{index}] has an invalid id {variant_id!r}",
                file=sys.stderr,
            )
            return 1
        matrix.append(
            {
                "id": variant_id,
                # Gradle capitalises flavour names in task names:
                # assembleContainerRelease, assemblePortalRelease, …
                "gradleName": variant_id[0].upper() + variant_id[1:],
                "name": str(variant.get("name", variant_id)),
            }
        )

    print(json.dumps(matrix))
    return 0


if __name__ == "__main__":
    sys.exit(main())
