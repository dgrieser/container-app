"""Where the generator finds the files it reads and writes.

Everything shared with the Android build lives at the repository root, so the
generator resolves from its own location rather than the working directory: it
is called from a Makefile, from CI and by hand, and each of those has a
different idea of where it is standing.
"""

import pathlib

TOOLS = pathlib.Path(__file__).resolve().parent
IOS = TOOLS.parent
REPO_ROOT = IOS.parent

# Shared with the Android build.
VARIANTS_FILE = REPO_ROOT / "app-variants.yaml"
SCHEMA_FILE = REPO_ROOT / "app-variants.schema.json"
GLYPHS_FILE = REPO_ROOT / "app-icons" / "glyphs.yaml"
TESTDATA = REPO_ROOT / "variants-testdata"

# Written by the generator; gitignored in its entirety.
GENERATED = IOS / "Generated"
VARIANTS_SPEC = GENERATED / "variants.yml"
VERSION_XCCONFIG = GENERATED / "Version.xcconfig"
VARIANT_DIRS = GENERATED / "Variants"

# Hand-written, and read by the generator's own consistency checks.
CONTAINER_KIT = IOS / "ContainerKit" / "Sources" / "ContainerKit"


def variant_dir(variant_id: str) -> pathlib.Path:
    """Everything generated for one variant, i.e. one Xcode target's sources."""
    return VARIANT_DIRS / variant_id
