import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET

import androidvector
import glyphs as glyphs_module
import icons
import paths
import variants
try:
    from PIL import Image


except ImportError:  # pragma: no cover - environment-dependent
    Image = None


def _cairo_available() -> bool:
    try:
        import cairosvg  # noqa: F401
    except Exception:  # pragma: no cover - environment-dependent
        return False
    return True


HAVE_RASTERISER = _cairo_available()
HAVE_PILLOW = Image is not None


class GlyphSvgTest(unittest.TestCase):
    """The SVG half, which needs no rasteriser at all."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.glyphs = glyphs_module.load(paths.GLYPHS_FILE)

    def test_every_glyph_produces_well_formed_svg_carrying_its_path(self) -> None:
        for name in self.glyphs.names:
            with self.subTest(glyph=name):
                path_data = self.glyphs.path_data(name)
                svg = icons.glyph_svg(path_data, "#123456", "#FEDCBA")
                root = ET.fromstring(svg)
                self.assertIn("#123456", svg, "background colour missing")
                self.assertIn("#FEDCBA", svg, "tint missing")
                self.assertIn(path_data, svg, "path data missing")
                self.assertEqual("0 0 108 108", root.get("viewBox"))

    def test_the_glyph_is_centred_on_the_canvas(self) -> None:
        svg = icons.glyph_svg("M0,0h24v24h-24z", "#000000", "#FFFFFF")
        # 24 * scale, centred, must leave equal margins; a wrong offset is the
        # kind of thing only a home screen would show.
        scale = icons.CANVAS * icons.GLYPH_FRACTION / 24.0
        offset = (icons.CANVAS - 24.0 * scale) / 2.0
        self.assertIn(f"translate({offset:.4f},{offset:.4f})", svg)


class AndroidVectorTest(unittest.TestCase):
    """The converter is narrow on purpose: it must refuse, not approximate."""

    def test_the_repository_artwork_converts(self) -> None:
        svg = androidvector.convert(paths.REPO_ROOT / "app-icons" / "gasoline.xml")
        root = ET.fromstring(svg)
        namespace = "{http://www.w3.org/2000/svg}"
        self.assertEqual("0 0 108 108", root.get("viewBox"))
        self.assertEqual(2, len(list(root.iter(f"{namespace}path"))))
        gradients = list(root.iter(f"{namespace}linearGradient"))
        self.assertEqual(1, len(gradients))
        self.assertEqual("userSpaceOnUse", gradients[0].get("gradientUnits"))
        self.assertEqual(4, len(list(gradients[0])))
        # The group transform is written translate-then-scale, because Android
        # composes the matrix the other way round.
        group = next(root.iter(f"{namespace}g"))
        self.assertRegex(group.get("transform"), r"^translate\([^)]+\) scale\([^)]+\)$")

    def test_every_checked_in_drawable_converts(self) -> None:
        # The one above knows what gasoline.xml is made of; this one only insists
        # that nothing in app-icons/ uses something the converter refuses, so a
        # new piece of artwork cannot reach a home screen on Android while
        # failing to draw at all on iOS.
        drawables = sorted((paths.REPO_ROOT / "app-icons").glob("*.xml"))
        self.assertTrue(drawables, "no drawables to convert")
        for drawable in drawables:
            with self.subTest(drawable=drawable.name):
                root = ET.fromstring(androidvector.convert(drawable))
                self.assertEqual("0 0 108 108", root.get("viewBox"))

    def test_evenodd_becomes_a_fill_rule(self) -> None:
        svg = androidvector.convert(paths.REPO_ROOT / "app-icons" / "gasoline.xml")
        self.assertIn('fill-rule="evenodd"', svg)

    def test_an_argb_colour_is_reordered_for_svg(self) -> None:
        self.assertEqual("#0D0E11CC", androidvector._color("#CC0D0E11", "x.xml"))
        self.assertEqual("#0D0E11", androidvector._color("#0D0E11", "x.xml"))

    def test_unsupported_features_raise_rather_than_being_dropped(self) -> None:
        cases = {
            "a rotation": '<group android:rotation="45"><path android:pathData="M0,0z" '
                          'android:fillColor="#000000"/></group>',
            "a stroke": '<path android:pathData="M0,0z" android:strokeColor="#000000" '
                        'android:fillColor="#000000"/>',
            "a clip path": '<clip-path android:pathData="M0,0z"/>',
            "a radial gradient": '<path android:pathData="M0,0z">'
                                 '<aapt:attr name="android:fillColor">'
                                 '<gradient android:type="radial"/></aapt:attr></path>',
            "a trimmed path": '<path android:pathData="M0,0z" android:fillColor="#000000" '
                              'android:trimPathStart="0.5"/>',
            "a tint": None,  # handled below, it is an attribute of <vector>
            "no fill": '<path android:pathData="M0,0z"/>',
        }
        for what, body in cases.items():
            if body is None:
                continue
            with self.subTest(case=what):
                with self.assertRaises(androidvector.VectorError):
                    androidvector.convert(self._drawable(body))

    def test_a_tinted_vector_raises(self) -> None:
        with self.assertRaises(androidvector.VectorError):
            androidvector.convert(self._drawable(
                '<path android:pathData="M0,0z" android:fillColor="#000000"/>',
                extra='android:tint="#FF0000"',
            ))

    def test_a_viewport_other_than_108_raises(self) -> None:
        with self.assertRaises(androidvector.VectorError):
            androidvector.convert(self._drawable(
                '<path android:pathData="M0,0z" android:fillColor="#000000"/>',
                viewport=24,
            ))

    def _drawable(self, body: str, extra: str = "", viewport: int = 108) -> pathlib.Path:
        document = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '        xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'        android:viewportWidth="{viewport}" '
            f'android:viewportHeight="{viewport}" {extra}>\n'
            f"{body}\n"
            "</vector>\n"
        )
        handle = tempfile.NamedTemporaryFile(
            "w", suffix=".xml", delete=False, encoding="utf-8"
        )
        handle.write(document)
        handle.close()
        self.addCleanup(pathlib.Path(handle.name).unlink)
        return pathlib.Path(handle.name)


@unittest.skipUnless(HAVE_RASTERISER and HAVE_PILLOW, "needs CairoSVG and Pillow")
class RasterTest(unittest.TestCase):
    """What actually lands in the .appiconset."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.glyphs = glyphs_module.load(paths.GLYPHS_FILE)

    def _render(self, svg: str, background: str) -> "Image.Image":
        import io
        return Image.open(io.BytesIO(icons.render_png(svg, background, size=128)))

    def test_every_glyph_renders_opaque_and_visible(self) -> None:
        for name in self.glyphs.names:
            with self.subTest(glyph=name):
                svg = icons.glyph_svg(self.glyphs.path_data(name), "#123456", "#FEDCBA")
                image = self._render(svg, "#123456")
                # Any alpha at all shows as black on the home screen.
                self.assertNotIn("A", image.mode, f"{name} has an alpha channel")
                colours = {c for _, c in image.convert("RGB").getcolors(maxcolors=65536)}
                # A glyph that rendered as nothing would leave one flat colour.
                self.assertGreater(len(colours), 1, f"{name} rendered as nothing")

    def test_the_repository_variants_render_at_full_size(self) -> None:
        declared = variants.load()
        with tempfile.TemporaryDirectory() as raw:
            for variant in declared:
                with self.subTest(variant=variant.id):
                    target = pathlib.Path(raw) / variant.id
                    png = icons.write(variant, self.glyphs, paths.REPO_ROOT, target)
                    with Image.open(png) as image:
                        self.assertEqual((icons.SIZE, icons.SIZE), image.size)
                        self.assertNotIn("A", image.mode)
                    self.assertTrue(
                        (target / "Assets.xcassets" / "Contents.json").is_file()
                    )
                    self.assertTrue(
                        (target / "Assets.xcassets" / "AppIcon.appiconset"
                         / "Contents.json").is_file()
                    )

    def test_the_manifest_names_the_file_that_was_written(self) -> None:
        import json
        document = json.loads(icons.contents_json())
        self.assertEqual(
            f"icon-{icons.SIZE}.png", document["images"][0]["filename"]
        )
        self.assertEqual(f"{icons.SIZE}x{icons.SIZE}", document["images"][0]["size"])


if __name__ == "__main__":
    unittest.main()
