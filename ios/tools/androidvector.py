"""Converts an Android vector drawable to SVG, or refuses to.

A variant whose symbol is more than a built-in glyph points `icon.vector` at a
108x108 vector drawable, which the Android build copies into the APK verbatim.
iOS needs a raster icon, so that drawable has to become an SVG first.

Most of the conversion is a rename: `android:pathData` *is* SVG path data, and
the 108x108 viewport is a `viewBox`. Gradients and group transforms need real
translation, and everything else is refused. That is deliberate, and matches how
`app-variants.yaml` is parsed: a feature quietly dropped here would ship an icon
missing part of its artwork, and nobody would notice until they looked at a home
screen. So the supported set is exactly what this repository's artwork uses, and
anything outside it raises with the attribute named.
"""

from __future__ import annotations

import pathlib
import xml.etree.ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"
AAPT = "http://schemas.android.com/aapt"

#: The canvas every launcher drawable is drawn on.
VIEWPORT = 108.0


class VectorError(ValueError):
    """The drawable uses something this converter will not silently drop."""


def _attr(element: ET.Element, name: str) -> str | None:
    return element.get(f"{{{ANDROID}}}{name}")


def _tag(element: ET.Element) -> str:
    return element.tag.split("}")[-1]


def _refuse(element: ET.Element, what: str) -> None:
    raise VectorError(
        f"<{_tag(element)}> uses {what}, which has no faithful SVG counterpart "
        f"here. Either add support for it to ios/tools/androidvector.py or supply "
        f"the artwork as an SVG."
    )


def _check_unsupported(element: ET.Element, unsupported: tuple[str, ...]) -> None:
    for name in unsupported:
        if _attr(element, name) is not None:
            _refuse(element, f"android:{name}")


def _float(element: ET.Element, name: str, fallback: float) -> float:
    raw = _attr(element, name)
    if raw is None:
        return fallback
    try:
        return float(raw)
    except ValueError as error:
        raise VectorError(f"<{_tag(element)}>: android:{name} is not a number ({raw!r}).") from error


def convert(file: pathlib.Path) -> str:
    """Returns the drawable as a standalone SVG document."""
    try:
        root = ET.parse(file).getroot()
    except ET.ParseError as error:
        raise VectorError(f"{file.name} is not well-formed XML: {error}") from error

    if _tag(root) != "vector":
        raise VectorError(f"{file.name}: expected a <vector> root, found <{_tag(root)}>.")
    _check_unsupported(root, ("tint", "tintMode", "alpha", "autoMirrored"))

    for name in ("viewportWidth", "viewportHeight"):
        if _float(root, name, 0.0) != VIEWPORT:
            raise VectorError(
                f"{file.name}: android:{name} must be {VIEWPORT:g}. A launcher "
                f"drawable is drawn on a {VIEWPORT:g}x{VIEWPORT:g} canvas on both "
                f"platforms, and rescaling it here would move the artwork."
            )

    gradients: list[str] = []
    body = "".join(_convert_children(root, file.name, gradients, depth=1))
    defs = f'  <defs>\n{"".join(gradients)}  </defs>\n' if gradients else ""

    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {VIEWPORT:g} {VIEWPORT:g}"\n'
        f'     width="{VIEWPORT:g}" height="{VIEWPORT:g}">\n'
        f"  <!-- Generated from {file.name} by ios/tools/androidvector.py — do not edit. -->\n"
        f"{defs}{body}"
        f"</svg>\n"
    )


def _convert_children(
    parent: ET.Element, file_name: str, gradients: list[str], depth: int
) -> list[str]:
    out: list[str] = []
    for child in parent:
        tag = _tag(child)
        if tag == "group":
            out.extend(_convert_group(child, file_name, gradients, depth))
        elif tag == "path":
            out.append(_convert_path(child, file_name, gradients, depth))
        elif tag == "clip-path":
            _refuse(child, "clipping")
        else:
            raise VectorError(f"{file_name}: unsupported element <{tag}>.")
    return out


def _convert_group(
    group: ET.Element, file_name: str, gradients: list[str], depth: int
) -> list[str]:
    # A rotation needs its pivot composed into the matrix, and an alpha needs a
    # group opacity; neither appears in this repository's artwork, so neither is
    # guessed at.
    _check_unsupported(group, ("rotation", "pivotX", "pivotY", "alpha", "name"))

    scale_x = _float(group, "scaleX", 1.0)
    scale_y = _float(group, "scaleY", 1.0)
    translate_x = _float(group, "translateX", 0.0)
    translate_y = _float(group, "translateY", 0.0)

    # Android composes the group matrix as scale-then-translate, which in SVG is
    # written the other way round: the rightmost transform applies first.
    parts = []
    if (translate_x, translate_y) != (0.0, 0.0):
        parts.append(f"translate({translate_x:g},{translate_y:g})")
    if (scale_x, scale_y) != (1.0, 1.0):
        parts.append(f"scale({scale_x:g},{scale_y:g})")

    pad = "  " * depth
    inner = _convert_children(group, file_name, gradients, depth + 1)
    if not parts:
        return inner
    return [f'{pad}<g transform="{" ".join(parts)}">\n', *inner, f"{pad}</g>\n"]


def _convert_path(
    path: ET.Element, file_name: str, gradients: list[str], depth: int
) -> str:
    _check_unsupported(path, (
        "strokeColor", "strokeWidth", "strokeAlpha", "strokeLineCap",
        "strokeLineJoin", "strokeMiterLimit",
        "trimPathStart", "trimPathEnd", "trimPathOffset", "name",
    ))

    data = _attr(path, "pathData")
    if not data:
        raise VectorError(f"{file_name}: a <path> has no android:pathData.")
    # Path data is wrapped across lines in the source for readability; SVG treats
    # whitespace between commands the same way, so it is normalised rather than
    # reflowed.
    data = " ".join(data.split())

    attributes = [f'd="{data}"']

    fill_type = _attr(path, "fillType")
    if fill_type is not None:
        if fill_type not in ("evenOdd", "nonZero"):
            raise VectorError(f"{file_name}: unknown android:fillType `{fill_type}`.")
        attributes.append(
            f'fill-rule="{"evenodd" if fill_type == "evenOdd" else "nonzero"}"'
        )

    fill_alpha = _attr(path, "fillAlpha")
    if fill_alpha is not None:
        attributes.append(f'fill-opacity="{float(fill_alpha):g}"')

    gradient = path.find(f"{{{AAPT}}}attr")
    fill_color = _attr(path, "fillColor")
    if gradient is not None:
        if fill_color is not None:
            raise VectorError(
                f"{file_name}: a <path> declares both android:fillColor and an "
                f"<aapt:attr> gradient."
            )
        if gradient.get("name") != "android:fillColor":
            _refuse(gradient, f"<aapt:attr name=\"{gradient.get('name')}\">")
        fill = f"url(#{_convert_gradient(gradient, file_name, gradients)})"
    elif fill_color is not None:
        fill = _color(fill_color, file_name)
    else:
        # A vector drawable with no fill paints nothing; an SVG with no fill
        # paints black. Refusing beats silently inventing a black shape.
        raise VectorError(f"{file_name}: a <path> has no fill.")

    attributes.append(f'fill="{fill}"')
    return "  " * depth + f"<path {' '.join(attributes)}/>\n"


def _convert_gradient(holder: ET.Element, file_name: str, gradients: list[str]) -> str:
    gradient = holder.find("gradient")
    if gradient is None:
        raise VectorError(f"{file_name}: <aapt:attr> holds no <gradient>.")

    kind = _attr(gradient, "type") or "linear"
    if kind != "linear":
        _refuse(gradient, f"a {kind} gradient")
    _check_unsupported(gradient, ("tileMode", "centerX", "centerY", "gradientRadius"))

    identifier = f"g{len(gradients)}"
    x1 = _float(gradient, "startX", 0.0)
    y1 = _float(gradient, "startY", 0.0)
    x2 = _float(gradient, "endX", 0.0)
    y2 = _float(gradient, "endY", 0.0)

    stops = []
    for item in gradient.findall("item"):
        offset = _float(item, "offset", 0.0)
        color = _attr(item, "color")
        if color is None:
            raise VectorError(f"{file_name}: a gradient <item> has no android:color.")
        stops.append(
            f'      <stop offset="{offset:g}" stop-color="{_color(color, file_name)}"/>\n'
        )
    if len(stops) < 2:
        raise VectorError(f"{file_name}: a gradient needs at least two stops.")

    # userSpaceOnUse keeps the stated coordinates in the drawable's own space,
    # which is what the Android shader does: a group transform applies to the
    # gradient as well as to the path.
    gradients.append(
        f'    <linearGradient id="{identifier}" gradientUnits="userSpaceOnUse"\n'
        f'                    x1="{x1:g}" y1="{y1:g}" x2="{x2:g}" y2="{y2:g}">\n'
        f"{''.join(stops)}"
        f"    </linearGradient>\n"
    )
    return identifier


def _color(value: str, file_name: str) -> str:
    """`#RRGGBB` passes through; `#AARRGGBB` becomes SVG's `#RRGGBBAA`."""
    if not value.startswith("#") or len(value) not in (7, 9):
        raise VectorError(f"{file_name}: colour `{value}` must be #RRGGBB or #AARRGGBB.")
    if len(value) == 7:
        return value
    alpha, rgb = value[1:3], value[3:]
    return f"#{rgb}{alpha}"
