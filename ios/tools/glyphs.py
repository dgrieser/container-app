"""The built-in launcher symbols, read from the file the Android build reads.

`app-icons/glyphs.yaml` holds each glyph as `pathData` in a 24x24 viewport,
which is SVG path data unchanged -- the one reason a single file can serve an
Android adaptive icon and an iOS app icon. Kotlin's LauncherGlyphs reads the
same file and validates it the same way, so a glyph that is not path data fails
on both platforms rather than rendering as nothing on one of them.
"""

import pathlib
import re

import yaml

from paths import GLYPHS_FILE

#: Only the characters an SVG / vector-drawable path may be made of.
PATH_DATA = re.compile(r"[MmLlHhVvCcSsQqTtAaZz0-9.,\- ]+\Z")

#: The glyph a variant gets when it declares no icon at all.
DEFAULT = "container"


class GlyphError(ValueError):
    """The glyph file is unusable, or a glyph is not path data."""


class Glyphs:
    """The symbols, in the order the file lists them."""

    def __init__(self, paths: dict[str, str]) -> None:
        self._paths = paths

    @property
    def names(self) -> list[str]:
        return list(self._paths)

    def path_data(self, name: str) -> str | None:
        return self._paths.get(name)

    def __len__(self) -> int:
        return len(self._paths)


def load(file: pathlib.Path = GLYPHS_FILE) -> Glyphs:
    if not file.is_file():
        raise GlyphError(f"Missing {file}: it holds the built-in launcher symbols.")
    document = yaml.safe_load(file.read_text(encoding="utf-8"))
    if not document:
        raise GlyphError(f"{file.name} is empty; it must declare at least one glyph.")
    if not isinstance(document, dict):
        raise GlyphError(f"{file.name}: expected a mapping of glyph name to path data.")

    paths: dict[str, str] = {}
    for name, raw in document.items():
        data = "" if raw is None else str(raw).strip()
        if not data or not PATH_DATA.match(data):
            raise GlyphError(f"{file.name}: glyph `{name}` is not path data (`{data}`).")
        paths[str(name)] = data

    if DEFAULT not in paths:
        raise GlyphError(f"{file.name}: the default glyph `{DEFAULT}` is missing.")
    return Glyphs(paths)
