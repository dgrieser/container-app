"""Reads `app-variants.yaml`, the way the Gradle build reads it.

The Android build parses the same file in Kotlin
(`android/buildSrc/src/main/kotlin/AppVariants.kt`). Two parsers for one file
drift, so this one is driven by `app-variants.schema.json` -- the key sets, the
value types, the screenMode names and every default come from there, which
removes the whole class of "one side allows a key the other rejects".

What a schema cannot express is written out below, and matches the Kotlin
rules line for line: unique ids, unique applicationIds, at most one default,
reserved ids, `configUrl` required unless `defaultKioskPath` is absolute, glyph
xor vector, and `locationReason` only alongside `allowLocation`. Every one of
those has a case in `variants-testdata/`, which both test suites run.

The parser is deliberately strict for the same reason as the Kotlin one: a
misspelled key must fail the build rather than quietly ship an app with the
wrong name or kiosk URL.
"""

from __future__ import annotations

import dataclasses
import json
import pathlib
import re

import yaml

import glyphs as glyphs_module
from paths import GLYPHS_FILE, SCHEMA_FILE, VARIANTS_FILE

#: Ids the Android build claims for its own source sets and build types. Kept on
#: the iOS side too: the variant id is meant to name the same app on both
#: platforms, so a name that cannot exist on one of them is not usable.
RESERVED_IDS = frozenset({"main", "test", "androidTest", "debug", "release"})


class VariantError(ValueError):
    """`app-variants.yaml` is invalid. The message names the offending entry."""


@dataclasses.dataclass(frozen=True)
class BarColors:
    """What the bars a variant keeps on screen are painted in, per system theme."""

    light: str
    dark: str


@dataclasses.dataclass(frozen=True)
class LauncherIcon:
    """Either a built-in `glyph` or a checked-in `vector`, never both."""

    glyph: str | None
    vector: str | None
    background: str
    tint: str


@dataclasses.dataclass(frozen=True)
class Variant:
    """One installable app, with every default already applied."""

    id: str
    name: str
    application_id: str
    version_name_suffix: str | None
    config_url: str
    default_kiosk_path: str
    require_pin: bool
    show_menu: bool
    pull_to_refresh: bool
    screen_mode: str
    bar_color: BarColors
    allow_unverified_ssl: bool
    allow_external_navigation: bool
    allow_location: bool
    location_reason: str | None
    is_default: bool
    icon: LauncherIcon

    @property
    def screen_mode_shows_status_bar(self) -> bool:
        """Whether this variant starts with the status bar on screen.

        Only the starting point: the admin menu changes the mode per device, and
        the view controller decides from then on. It is here because
        `UIStatusBarHidden` is what iOS reads before any code runs, so the launch
        screen matches the first frame instead of flashing a bar into view.
        """
        return self.screen_mode in ("statusBar", "systemBars")

    @property
    def location_purpose(self) -> str:
        """The sentence iOS shows when the page asks for a position.

        iOS terminates an app that asks without one, so a variant that wants
        location always has text; `locationReason` is how to say something better
        than this.
        """
        return self.location_reason or (
            f"{self.name} uses your location to show what is near you."
        )


# --- the schema --------------------------------------------------------------


class _Schema:
    """Just enough JSON Schema to drive the parse.

    Only the keywords `app-variants.schema.json` actually uses are honoured, and
    an unknown one raises rather than being ignored -- a schema keyword silently
    skipped here would be a rule the iOS side stops enforcing while Kotlin still
    does. `jsonschema` would do this too, but a sixty-line walker keeps the
    generator dependency-free apart from PyYAML.
    """

    SUPPORTED = frozenset({
        "$schema", "$id", "$defs", "$ref", "title", "description",
        "type", "properties", "additionalProperties", "required",
        "enum", "pattern", "default", "minItems", "minLength", "items",
    })

    def __init__(self, document: dict) -> None:
        self._root = document
        self._defs = document.get("$defs", {})
        for node in self._walk(document):
            unknown = set(node) - self.SUPPORTED
            if unknown:
                raise VariantError(
                    f"{SCHEMA_FILE.name}: unsupported schema keyword(s) "
                    f"{', '.join(sorted(unknown))}; teach variants.py about them "
                    f"or the iOS side stops enforcing them."
                )

    def _walk(self, node):
        """Every schema node, but not the *names* under `properties` / `$defs`.

        Those are keys of the file being described, not schema keywords, so
        descending into them blindly would report `variants` as an unsupported
        keyword.
        """
        if not isinstance(node, dict):
            return
        yield node
        for key, value in node.items():
            if key in ("properties", "$defs"):
                for child in (value or {}).values():
                    yield from self._walk(child)
            elif key == "items":
                yield from self._walk(value)

    def resolve(self, node: dict) -> dict:
        """Inlines a `$ref`, keeping anything declared alongside it (the defaults)."""
        ref = node.get("$ref")
        if not ref:
            return node
        target = self._defs[ref.rsplit("/", 1)[-1]]
        return {**target, **{k: v for k, v in node.items() if k != "$ref"}}

    @property
    def root(self) -> dict:
        return self._root

    def definition(self, name: str) -> dict:
        return self._defs[name]

    def properties(self, node: dict) -> dict:
        return node.get("properties", {})

    def keys(self, node: dict) -> set[str]:
        return set(self.properties(node))

    def property(self, node: dict, key: str) -> dict:
        return self.resolve(self.properties(node)[key])

    def default(self, node: dict, key: str):
        return self.property(node, key).get("default")


def _load_schema(file: pathlib.Path = SCHEMA_FILE) -> _Schema:
    if not file.is_file():
        raise VariantError(f"Missing {file}: it declares the shape of app-variants.yaml.")
    return _Schema(json.loads(file.read_text(encoding="utf-8")))


# --- schema-driven value reading --------------------------------------------


def _check_keys(mapping: dict, allowed: set[str], where: str, file_name: str) -> None:
    unknown = [str(k) for k in mapping if str(k) not in allowed]
    if unknown:
        raise VariantError(
            f"{file_name}: unknown key(s) {', '.join('`' + k + '`' for k in unknown)} "
            f"at {where}. Allowed: {', '.join(sorted(allowed))}."
        )


def _as_mapping(value, where: str, file_name: str) -> dict:
    if value is None:
        return {}
    if not isinstance(value, dict):
        kind = "a list" if isinstance(value, list) else f"`{value}`"
        raise VariantError(f"{file_name}: expected a mapping at {where}, was {kind}.")
    return value


def _string(mapping: dict, key: str, where: str) -> str | None:
    """A string, trimmed; empty counts as absent, as it does in Kotlin."""
    value = mapping.get(key)
    if value is None:
        return None
    if isinstance(value, bool):
        raise VariantError(f"{where}: expected text for `{key}`, was `{value}`.")
    text = str(value).strip()
    return text or None


def _boolean(mapping: dict, key: str, where: str) -> bool | None:
    value = mapping.get(key)
    if value is None:
        return None
    if not isinstance(value, bool):
        # `allowLocation: when asked` parses as a string in YAML; Kotlin rejects
        # it and so must this, or a build would silently lose the flag.
        raise VariantError(f"{where}: expected true/false for `{key}`, was `{value}`.")
    return value


def _matching(value: str, node: dict, key: str, where: str) -> str:
    pattern = node.get("pattern")
    if pattern and not re.match(pattern, value):
        raise VariantError(f"{where}: `{key}` does not match {pattern}, was `{value}`.")
    enum = node.get("enum")
    if enum is not None and value not in enum:
        raise VariantError(
            f"{where}: unknown {key} `{value}`. Available: {', '.join(enum)}."
        )
    if len(value) < node.get("minLength", 0):
        raise VariantError(f"{where}: `{key}` must not be empty.")
    return value


# --- the parse ---------------------------------------------------------------


def load(
    file: pathlib.Path = VARIANTS_FILE,
    fallback_application_id: str = "de.davidgrieser.container",
    glyphs: glyphs_module.Glyphs | None = None,
    schema: _Schema | None = None,
) -> list[Variant]:
    if not file.is_file():
        raise VariantError(
            f"Missing {file.name}: it declares the app variants "
            f"(name, icon, kiosk URL) to build."
        )
    schema = schema or _load_schema()
    glyphs = glyphs if glyphs is not None else glyphs_module.load(GLYPHS_FILE)

    document = yaml.safe_load(file.read_text(encoding="utf-8"))
    if not document:
        raise VariantError(
            f"{file.name} is empty; it must declare at least one entry under `variants:`."
        )
    top = _as_mapping(document, "the document root", file.name)
    _check_keys(top, schema.keys(schema.root), "the document root", file.name)

    base_application_id = _string(top, "applicationId", file.name) or fallback_application_id

    raw_variants = top.get("variants")
    if not isinstance(raw_variants, list):
        raise VariantError(
            f"{file.name} must contain a `variants:` list with at least one entry."
        )
    if not raw_variants:
        raise VariantError(
            f"{file.name}: `variants:` is empty; at least one variant is required."
        )

    variants = [
        _parse_variant(
            raw, f"{file.name} variants[{index}]", file.name,
            base_application_id, glyphs, schema,
        )
        for index, raw in enumerate(raw_variants)
    ]

    _check_uniqueness(variants, file.name)
    return variants


def _check_uniqueness(variants: list[Variant], file_name: str) -> None:
    seen_ids: set[str] = set()
    for variant in variants:
        if variant.id in seen_ids:
            raise VariantError(f"{file_name}: duplicate variant id `{variant.id}`.")
        seen_ids.add(variant.id)

    by_application_id: dict[str, list[str]] = {}
    for variant in variants:
        by_application_id.setdefault(variant.application_id, []).append(variant.id)
    for application_id, ids in by_application_id.items():
        if len(ids) > 1:
            joined = ", ".join(f"`{i}`" for i in ids)
            raise VariantError(
                f"{file_name}: variants {joined} share the applicationId "
                f"`{application_id}`, so they could not be installed side by side."
            )

    if sum(1 for v in variants if v.is_default) > 1:
        raise VariantError(f"{file_name}: only one variant may set `default: true`.")


def _parse_variant(
    raw,
    where: str,
    file_name: str,
    base_application_id: str,
    glyphs: glyphs_module.Glyphs,
    schema: _Schema,
) -> Variant:
    node = schema.definition("variant")
    if raw is None or not isinstance(raw, dict):
        kind = "a list" if isinstance(raw, list) else f"`{raw}`"
        raise VariantError(f"{file_name}: expected a mapping at {where}, was {kind}.")
    _check_keys(raw, schema.keys(node), where, file_name)

    variant_id = _string(raw, "id", where)
    if not variant_id:
        raise VariantError(f"{where}: `id` is required (the internal name of the variant).")
    _matching(variant_id, schema.property(node, "id"), "id", where)
    if variant_id in RESERVED_IDS:
        raise VariantError(f"{where}: id `{variant_id}` is reserved by the Android build.")

    name = _string(raw, "name", where)
    if not name:
        raise VariantError(f"{where}: `name` is required (the launcher label).")
    _matching(name, schema.property(node, "name"), "name", where)

    is_default = _boolean(raw, "default", where)
    if is_default is None:
        is_default = schema.default(node, "default")
    application_id = _string(raw, "applicationId", where) or (
        base_application_id if is_default else f"{base_application_id}.{variant_id}"
    )

    default_kiosk_path = _string(raw, "defaultKioskPath", where) or ""

    # A variant needs somewhere to get its page from: either a kiosk.json to
    # read, or a defaultKioskPath that is a complete URL on its own.
    config_url = _string(raw, "configUrl", where)
    if config_url is None:
        if not _is_absolute_http_url(default_kiosk_path):
            raise VariantError(
                f"{where}: `configUrl` is required unless `defaultKioskPath` is an "
                f"absolute http(s) URL — without either the variant has no page to open."
            )
    else:
        _matching(config_url, schema.property(node, "configUrl"), "configUrl", where)

    screen_mode = _string(raw, "screenMode", where) or schema.default(node, "screenMode")
    _matching(screen_mode, schema.property(node, "screenMode"), "screenMode", where)

    allow_location = _boolean(raw, "allowLocation", where)
    if allow_location is None:
        allow_location = schema.default(node, "allowLocation")

    # A reason without a request is a sentence nobody will ever be shown, and
    # reads as though the variant asks for a position when it does not.
    location_reason = _string(raw, "locationReason", where)
    if location_reason is not None:
        if not allow_location:
            raise VariantError(
                f"{where}: `locationReason` is only meaningful together with "
                f"`allowLocation: true`."
            )
        _matching(
            location_reason,
            schema.property(node, "locationReason"),
            "locationReason",
            where,
        )

    def flag(key: str) -> bool:
        value = _boolean(raw, key, where)
        return schema.default(node, key) if value is None else value

    return Variant(
        id=variant_id,
        name=name,
        application_id=application_id,
        version_name_suffix=_string(raw, "versionNameSuffix", where),
        config_url=config_url or "",
        default_kiosk_path=default_kiosk_path,
        require_pin=flag("requirePin"),
        show_menu=flag("showMenu"),
        pull_to_refresh=flag("pullToRefresh"),
        screen_mode=screen_mode,
        bar_color=_parse_bar_colors(raw.get("barColor"), f"{where} barColor", file_name, schema),
        allow_unverified_ssl=flag("allowUnverifiedSsl"),
        allow_external_navigation=flag("allowExternalNavigation"),
        allow_location=allow_location,
        location_reason=location_reason,
        is_default=is_default,
        icon=_parse_icon(raw.get("icon"), f"{where} icon", file_name, glyphs, schema),
    )


def _parse_bar_colors(raw, where: str, file_name: str, schema: _Schema) -> BarColors:
    node = schema.property(schema.definition("variant"), "barColor")
    if raw is None:
        return BarColors(
            light=schema.default(node, "light"),
            dark=schema.default(node, "dark"),
        )
    mapping = _as_mapping(raw, where, file_name)
    _check_keys(mapping, schema.keys(node), where, file_name)

    values = {}
    for key in ("light", "dark"):
        value = _string(mapping, key, where) or schema.default(node, key)
        values[key] = _matching(value, schema.property(node, key), key, where)
    return BarColors(**values)


def _parse_icon(
    raw, where: str, file_name: str, glyphs: glyphs_module.Glyphs, schema: _Schema
) -> LauncherIcon:
    node = schema.property(schema.definition("variant"), "icon")
    if raw is None:
        return LauncherIcon(
            glyph=schema.default(node, "glyph"),
            vector=None,
            background=schema.default(node, "background"),
            tint=schema.default(node, "tint"),
        )
    mapping = _as_mapping(raw, where, file_name)
    _check_keys(mapping, schema.keys(node), where, file_name)

    glyph = _string(mapping, "glyph", where)
    vector = _string(mapping, "vector", where)
    if glyph is not None and vector is not None:
        raise VariantError(f"{where}: set either `glyph` or `vector`, not both.")
    if glyph is not None and glyphs.path_data(glyph) is None:
        raise VariantError(
            f"{where}: unknown glyph `{glyph}`. Available: {', '.join(glyphs.names)}."
        )

    colors = {}
    for key in ("background", "tint"):
        value = _string(mapping, key, where) or schema.default(node, key)
        colors[key] = _matching(value, schema.property(node, key), key, where)

    return LauncherIcon(
        glyph=(glyph or schema.default(node, "glyph")) if vector is None else None,
        vector=vector,
        **colors,
    )


def _is_absolute_http_url(value: str) -> bool:
    return value.startswith("https://") or value.startswith("http://")


# --- the parity dump ---------------------------------------------------------


def dump_json(variants: list[Variant]) -> str:
    """The fully-resolved model, byte-identical to Gradle's `dumpVariantsJson`.

    A CI job diffs the two. It is the only check that catches the parsers
    applying different *defaults* rather than merely allowing different keys, so
    the formatting here is load-bearing: sorted keys, two-space indent, a
    trailing newline.
    """
    fields = [
        {
            "allowExternalNavigation": v.allow_external_navigation,
            "allowLocation": v.allow_location,
            "allowUnverifiedSsl": v.allow_unverified_ssl,
            "applicationId": v.application_id,
            "barColor": {"dark": v.bar_color.dark, "light": v.bar_color.light},
            "configUrl": v.config_url,
            "default": v.is_default,
            "defaultKioskPath": v.default_kiosk_path,
            "icon": {
                "background": v.icon.background,
                "glyph": v.icon.glyph,
                "tint": v.icon.tint,
                "vector": v.icon.vector,
            },
            "id": v.id,
            "locationReason": v.location_reason,
            "name": v.name,
            "pullToRefresh": v.pull_to_refresh,
            "requirePin": v.require_pin,
            "screenMode": v.screen_mode,
            "showMenu": v.show_menu,
            "versionNameSuffix": v.version_name_suffix,
        }
        for v in variants
    ]
    return json.dumps(fields, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
