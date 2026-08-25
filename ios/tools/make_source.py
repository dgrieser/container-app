#!/usr/bin/env python3
"""Writes the AltStore source that distributes these apps, and the site it sits on.

    python3 ios/tools/make_source.py \
        --app-version v1.2.3 \
        --artifacts build/ipa \
        --repository dgrieser/container-app \
        --site-url https://dgrieser.github.io/container-app \
        --previous published/source.json \
        --out site

Produces a directory ready to publish:

    site/source.json        the source AltStore subscribes to
    site/icons/<id>.png     the app icons, which the source points at

Everything in it is a function of `app-variants.yaml` plus the release, so adding
a variant adds an app to the source with nothing else to edit.

`--previous` is the source already published, if any: a release *appends* its
version rather than replacing the list, so someone on an older build can still
install what they have. A missing or unreadable file is treated as "first
release", which is what makes the very first run work.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import shutil
import sys

import altstore
import glyphs as glyphs_module
import icons
import paths
import variants as variants_module
import version as version_module


def artifact_name(variant_id: str, tag: str) -> str:
    """The release asset for one variant, matching the release workflow."""
    return f"container-app-{variant_id}-{tag}-ios.ipa"


def build(
    *,
    tag: str,
    artifacts: pathlib.Path,
    repository: str,
    site_url: str,
    developer_name: str,
    previous: pathlib.Path | None,
    out: pathlib.Path,
) -> dict:
    declared = variants_module.load(paths.VARIANTS_FILE)
    resolved = version_module.parse(tag)
    repository_url = f"https://github.com/{repository}"

    apps = []
    for variant in declared:
        name = artifact_name(variant.id, tag)
        ipa = artifacts / name
        if not ipa.is_file():
            # A source that points at an asset which is not there installs
            # nothing, and AltStore reports it as a network error. Better to fail
            # the release.
            raise FileNotFoundError(f"missing {ipa}: the release has no asset for {variant.id}")
        apps.append(
            altstore.app_entry(
                variant,
                resolved,
                repository_url=repository_url,
                assets_url=site_url.rstrip("/"),
                download_url=f"{repository_url}/releases/download/{tag}/{name}",
                size=ipa.stat().st_size,
                date=release_date(),
                developer_name=developer_name,
                release_notes=f"{repository_url}/releases/tag/{tag}",
            )
        )

    current = altstore.source(apps, repository_url=repository_url)
    document = altstore.merge(read_previous(previous), current)

    out.mkdir(parents=True, exist_ok=True)
    (out / "source.json").write_text(altstore.dumps(document), encoding="utf-8")
    write_icons(declared, out / "icons")
    return document


def release_date() -> str:
    """The date the source records for this version.

    Read from the environment rather than the clock, so a re-run of the same
    release produces the same document.
    """
    import os

    stamped = os.environ.get("RELEASE_DATE", "").strip()
    if stamped:
        return stamped
    import datetime

    return datetime.datetime.now(datetime.timezone.utc).date().isoformat()


def read_previous(path: pathlib.Path | None) -> dict | None:
    if path is None or not path.is_file():
        return None
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        # A corrupt published file must not stop a release; the worst case is
        # that older versions stop being offered.
        print(f"warning: {path} is not valid JSON, treating as first release", file=sys.stderr)
        return None
    return document if isinstance(document, dict) else None


def write_icons(declared: list[variants_module.Variant], into: pathlib.Path) -> None:
    """The icons the source points at, at the URL it points at."""
    into.mkdir(parents=True, exist_ok=True)
    glyphs = glyphs_module.load(paths.GLYPHS_FILE)
    for variant in declared:
        rendered = paths.variant_dir(variant.id) / "Assets.xcassets" / "AppIcon.appiconset"
        source = rendered / f"icon-{icons.SIZE}.png"
        target = into / f"{variant.id}.png"
        if source.is_file():
            shutil.copyfile(source, target)
            continue
        # Not generated yet (a --skip-icons build, or a source built on its own):
        # draw it here rather than publishing a source with a broken iconURL.
        svg = icons.svg_for(variant, glyphs, paths.REPO_ROOT)
        target.write_bytes(icons.render_png(svg, variant.icon.background))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--app-version", required=True, help="The release tag, e.g. v1.2.3.")
    parser.add_argument(
        "--artifacts", required=True, type=pathlib.Path,
        help="Directory holding the built .ipa files, read for their sizes.",
    )
    parser.add_argument("--repository", required=True, help="owner/repo on GitHub.")
    parser.add_argument(
        "--site-url", required=True,
        help="Where the source and its icons will be served from.",
    )
    parser.add_argument("--developer-name", default="David Grieser")
    parser.add_argument(
        "--previous", type=pathlib.Path,
        help="The source already published, so older versions stay installable.",
    )
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("site"))
    args = parser.parse_args(argv)

    try:
        document = build(
            tag=args.app_version,
            artifacts=args.artifacts,
            repository=args.repository,
            site_url=args.site_url,
            developer_name=args.developer_name,
            previous=args.previous,
            out=args.out,
        )
    except (FileNotFoundError, variants_module.VariantError, version_module.VersionError,
            icons.IconError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    print(
        f"Wrote {args.out / 'source.json'} with {len(document['apps'])} app(s): "
        + ", ".join(f"{a['name']} {a['versions'][0]['version']}" for a in document["apps"])
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
