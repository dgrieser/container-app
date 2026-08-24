"""Draws each variant's iOS app icon from the same glyph and colours as Android's.

The Android build writes an adaptive icon: a background layer and a foreground
layer, masked by whatever shape the launcher chooses. iOS has no equivalent -- an
app icon is one square image, which the system rounds itself -- so this produces
a single 1024x1024 PNG plus the `Contents.json` that makes it an
`.appiconset`. Xcode 14 and later accept that one size and downsample the rest.

Two things are worth knowing:

* **The PNG must be fully opaque.** iOS does not composite an app icon over
  anything; any alpha renders as black on the home screen. So the background
  colour is painted first and the rasteriser is given it as its ground.
* **No rounded corners are baked in.** The system mask is applied at display
  time, and a pre-rounded icon shows its own corners inside the system's.

Nothing here needs a Mac. `pathData` is SVG path data, the vector-drawable
converter produces SVG, and CairoSVG rasterises on Linux -- which is why CI
renders the icons on the Linux leg and hands them to the Mac as an artifact.
"""

from __future__ import annotations

import json
import pathlib

import androidvector
from glyphs import Glyphs
from variants import Variant

#: The one size a modern `.appiconset` needs.
SIZE = 1024

#: The canvas a launcher symbol is drawn on, matching the Android adaptive icon.
CANVAS = 108.0

#: The 24x24 glyph's share of that canvas. Android scales a glyph to about half
#: the canvas because an adaptive-icon mask can crop aggressively; the iOS
#: squircle clips far less, so the same glyph can carry more weight without
#: risking its extremities.
GLYPH_FRACTION = 0.60


class IconError(RuntimeError):
    """The icon could not be drawn."""


def glyph_svg(path_data: str, background: str, tint: str) -> str:
    """A glyph centred on the canvas, over a solid ground."""
    scale = CANVAS * GLYPH_FRACTION / 24.0
    offset = (CANVAS - 24.0 * scale) / 2.0
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {CANVAS:g} {CANVAS:g}"\n'
        f'     width="{CANVAS:g}" height="{CANVAS:g}" shape-rendering="geometricPrecision">\n'
        f'  <rect width="{CANVAS:g}" height="{CANVAS:g}" fill="{background}"/>\n'
        f'  <g transform="translate({offset:.4f},{offset:.4f}) scale({scale:.6f})">\n'
        f'    <path fill="{tint}" d="{path_data}"/>\n'
        f"  </g>\n"
        f"</svg>\n"
    )


def vector_svg(drawable: pathlib.Path, background: str) -> str:
    """A checked-in drawable over a solid ground, keeping its own colours.

    `icon.tint` deliberately does not apply: the artwork brings its own palette,
    which is the whole reason a variant reaches for `icon.vector`.
    """
    converted = androidvector.convert(drawable)
    ground = f'  <rect width="{CANVAS:g}" height="{CANVAS:g}" fill="{background}"/>\n'
    # The converter emits <defs> before the artwork, so the ground goes in after
    # them; inserting it at the top of the document would put a rectangle in
    # front of nothing and behind the defs.
    marker = "</defs>\n" if "</defs>" in converted else ">\n"
    head, _, tail = converted.partition(marker)
    return head + marker + ground + tail


def svg_for(variant: Variant, glyphs: Glyphs, repo_root: pathlib.Path) -> str:
    if variant.icon.vector:
        drawable = repo_root / variant.icon.vector
        if not drawable.is_file():
            raise IconError(
                f"{variant.id}: icon.vector points at {variant.icon.vector}, "
                f"which does not exist."
            )
        return vector_svg(drawable, variant.icon.background)

    glyph = variant.icon.glyph or ""
    path_data = glyphs.path_data(glyph)
    if path_data is None:
        raise IconError(f"{variant.id}: unknown glyph `{glyph}`.")
    return glyph_svg(path_data, variant.icon.background, variant.icon.tint)


def render_png(svg: str, background: str, size: int = SIZE) -> bytes:
    try:
        import cairosvg
    except ImportError as error:  # pragma: no cover - environment-dependent
        raise IconError(
            "CairoSVG is needed to draw the app icons: "
            "pip install -r ios/tools/requirements.txt "
            "(and the cairo library: apt install libcairo2, brew install cairo). "
            "Pass --skip-icons to generate a project without them."
        ) from error

    return cairosvg.svg2png(
        bytestring=svg.encode("utf-8"),
        output_width=size,
        output_height=size,
        # Opaque, because iOS shows any transparency as black.
        background_color=background,
    )


def contents_json() -> str:
    """The `.appiconset` manifest for a single universal 1024 px image."""
    document = {
        "images": [
            {
                "filename": "icon-1024.png",
                "idiom": "universal",
                "platform": "ios",
                "size": f"{SIZE}x{SIZE}",
            }
        ],
        "info": {"author": "xcode", "version": 1},
    }
    return json.dumps(document, indent=2) + "\n"


def asset_catalog_json() -> str:
    """The enclosing `Assets.xcassets` manifest."""
    return json.dumps({"info": {"author": "xcode", "version": 1}}, indent=2) + "\n"


def write(
    variant: Variant,
    glyphs: Glyphs,
    repo_root: pathlib.Path,
    target_dir: pathlib.Path,
) -> pathlib.Path:
    """Writes `Assets.xcassets/AppIcon.appiconset/` for one variant."""
    catalog = target_dir / "Assets.xcassets"
    appiconset = catalog / "AppIcon.appiconset"
    appiconset.mkdir(parents=True, exist_ok=True)

    svg = svg_for(variant, glyphs, repo_root)
    png = appiconset / f"icon-{SIZE}.png"
    png.write_bytes(render_png(svg, variant.icon.background))

    (catalog / "Contents.json").write_text(asset_catalog_json(), encoding="utf-8")
    (appiconset / "Contents.json").write_text(contents_json(), encoding="utf-8")
    return png
