"""What an iOS build calls itself, from the same git tag the APK uses.

A line-for-line port of `android/buildSrc/src/main/kotlin/AppVersion.kt`,
including the regex and its awkward corners, so a tag produces the same numbers
on both platforms and neither can be released with a version the other would
have rejected.

One thing does not carry over. Apple requires `CFBundleShortVersionString` to be
one to three period-separated integers, so `1.2.3-gasoline` -- which is exactly
what Android's `versionName` is -- is refused by App Store Connect and by
`altool`. The suffix therefore lives in the custom `ContainerVersionName` key,
in `VariantConfig.versionName` and in the artifact filename, where it is more
useful anyway: the app can show it. `CFBundleVersion` takes Android's
`versionCode` integer unchanged, so the ordering rule and its ceiling transfer
exactly.
"""

from __future__ import annotations

import dataclasses
import pathlib
import re
import subprocess

#: `v1.2.3`, `1.2`, or either with a trailing `-rc1` / `-12-gabc1234` / `+build3`.
#: A suffix may not start with a dot: `v1.2.100` would otherwise pass as 1.2 with
#: a suffix of `.100`, quietly losing the patch, and `v1.2.3.4` as 1.2.3.
VERSION_PATTERN = re.compile(
    r"^v?(\d{1,4})(?:\.(\d{1,2}))?(?:\.(\d{1,2}))?(?:[-+][^.].*)?$"
)

#: Highest versionCode Google Play accepts. Kept on iOS as well: the point is
#: that one tag yields one version everywhere, not that each store's own limit is
#: squeezed dry.
MAX_CODE = 2_100_000_000

#: What a build outside a tagged checkout says.
UNKNOWN_NAME = "0.0.0-unknown"
UNKNOWN_CODE = 1


class VersionError(ValueError):
    """The tag is not a version tag, or yields a code outside the allowed range."""


@dataclasses.dataclass(frozen=True)
class AppVersion:
    """The version in the three shapes the iOS build needs."""

    #: Android's `versionName`: whatever the tag said, minus a leading `v`.
    name: str
    #: Android's `versionCode`, and iOS's `CFBundleVersion`.
    code: int
    #: `CFBundleShortVersionString`: the numeric part only.
    short: str

    def version_name(self, suffix: str | None) -> str:
        """The full name for one variant, e.g. `1.2.3-gasoline`."""
        return f"{self.name}{suffix or ''}"


def parse(raw: str | None) -> AppVersion:
    tag = (raw or "").strip()
    if not tag:
        return AppVersion(name=UNKNOWN_NAME, code=UNKNOWN_CODE, short="0.0.0")

    match = VERSION_PATTERN.match(tag)
    if not match:
        raise VersionError(
            f"Version `{tag}` is not a version tag: expected "
            f"v<major>[.<minor>[.<patch>]], optionally followed by -<anything>, "
            f"e.g. v1.2.3 or v1.2.3-rc1."
        )
    major, minor, patch = (int(g or 0) for g in match.groups())
    code = major * 10_000 + minor * 100 + patch
    if code <= 0:
        raise VersionError(f"Version `{tag}` yields versionCode {code}; it must be positive.")
    if code > MAX_CODE:
        raise VersionError(f"Version `{tag}` yields versionCode {code}, above {MAX_CODE}.")

    return AppVersion(
        name=tag[1:] if tag.startswith("v") else tag,
        code=code,
        short=f"{major}.{minor}.{patch}",
    )


def describe(repository_root: pathlib.Path) -> str | None:
    """The working copy's own answer, or None whenever git cannot say."""
    try:
        result = subprocess.run(
            ["git", "describe", "--tags", "--always", "--dirty=-dirty"],
            cwd=repository_root,
            capture_output=True,
            text=True,
            timeout=10,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None
    output = result.stdout.strip()
    return output if VERSION_PATTERN.match(output) else None


def resolve(declared: str | None, repository_root: pathlib.Path) -> AppVersion:
    """`declared` wins -- the tag the release workflow was triggered by."""
    raw = (declared or "").strip() or describe(repository_root)
    return parse(raw)


def xcconfig(version: AppVersion) -> str:
    """`MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`, for the Info.plists."""
    return (
        "// Generated from the git tag by ios/tools/generate.py — do not edit.\n"
        "//\n"
        "// MARKETING_VERSION is CFBundleShortVersionString, which Apple requires\n"
        "// to be one to three integers, so a variant's versionNameSuffix is not\n"
        "// here: it is in ContainerVersionName and in the artifact filename.\n"
        f"MARKETING_VERSION = {version.short}\n"
        f"CURRENT_PROJECT_VERSION = {version.code}\n"
    )
