import re
import unittest

import paths
import swiftgen
import variants
import version


def _swift(name: str) -> str:
    file = paths.CONTAINER_KIT / "Core" / name
    assert file.is_file(), f"missing {file}"
    return file.read_text(encoding="utf-8")


class VariantConfigContractTest(unittest.TestCase):
    """Keeps the generator and `VariantConfig.swift` in step, without a compiler.

    On a Mac the Swift compiler catches this: a field the generator does not pass
    fails to build. But the generator runs on Linux -- in CI, and for anyone
    without a Mac -- so the same mismatch is caught here by reading the source.
    This is the counterpart of Android's `the screen modes match the app's
    ScreenMode enum`, and the reason it is safe to emit a typed enum case rather
    than a string.
    """

    @classmethod
    def setUpClass(cls) -> None:
        source = _swift("VariantConfig.swift")
        cls.properties = re.findall(r"^\s*public let (\w+):", source, re.MULTILINE)
        # The initialiser's parameter list, which is what the generated call fills.
        initialiser = source.split("public init(", 1)[1].split(")", 1)[0]
        cls.parameters = re.findall(r"(\w+):", initialiser)

    def test_the_generator_emits_every_stored_property(self) -> None:
        self.assertEqual(sorted(self.properties), sorted(swiftgen.FIELD_ORDER))

    def test_the_generator_emits_them_in_the_initialiser_s_order(self) -> None:
        # Not required by Swift, but a generated call that reads in a different
        # order than the declaration is needlessly hard to diff against it.
        self.assertEqual(list(self.parameters), list(swiftgen.FIELD_ORDER))

    def test_the_declaration_order_matches_the_initialiser(self) -> None:
        self.assertEqual(self.properties, self.parameters)


class ScreenModeContractTest(unittest.TestCase):
    """The four mode names, in three places, all of which must agree."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.cases = re.findall(r"^\s*case (\w+)$", _swift("ScreenMode.swift"), re.MULTILINE)
        cls.schema_modes = _schema_screen_modes()

    def test_the_enum_declares_exactly_the_modes_the_schema_allows(self) -> None:
        self.assertEqual(self.schema_modes, self.cases)

    def test_every_variant_s_mode_has_an_enum_case(self) -> None:
        for variant in variants.load():
            with self.subTest(variant=variant.id):
                self.assertIn(variant.screen_mode, self.cases)

    def test_the_generated_config_names_a_real_case(self) -> None:
        resolved = version.parse("v1.2.3")
        for variant in variants.load():
            with self.subTest(variant=variant.id):
                emitted = swiftgen.variant_config(variant, resolved)
                match = re.search(r"screenMode: \.(\w+)", emitted)
                self.assertIsNotNone(match, "no screenMode in the generated config")
                self.assertIn(match.group(1), self.cases)


class BarColorContractTest(unittest.TestCase):
    """The luminance threshold is the one number both platforms have to share."""

    def test_the_dark_icon_threshold_matches_android(self) -> None:
        source = _swift("BarColor.swift")
        match = re.search(r"darkIconThreshold = ([\d.]+)", source)
        self.assertIsNotNone(match)
        android = (
            paths.REPO_ROOT / "android" / "app" / "src" / "main" / "java" / "de"
            / "davidgrieser" / "container" / "SystemBarColors.kt"
        ).read_text(encoding="utf-8")
        expected = re.search(r"DARK_ICON_THRESHOLD = ([\d.]+)", android)
        self.assertIsNotNone(expected, "Android no longer declares the threshold")
        self.assertEqual(float(expected.group(1)), float(match.group(1)))

    def test_a_generated_colour_is_a_grouped_argb_literal(self) -> None:
        self.assertEqual("0xFF_F4_F2_ED", swiftgen.swift_argb("#F4F2ED"))
        self.assertEqual("0xCC_0D_0E_11", swiftgen.swift_argb("#CC0D0E11"))
        with self.assertRaises(ValueError):
            swiftgen.swift_argb("white")


class GeneratedSwiftTest(unittest.TestCase):
    def test_every_generated_file_says_not_to_edit_it(self) -> None:
        resolved = version.parse("v1.2.3")
        for variant in variants.load():
            with self.subTest(variant=variant.id):
                for text in (
                    swiftgen.variant_config(variant, resolved),
                    swiftgen.main_swift(variant),
                ):
                    self.assertIn("do not edit", text)
                    self.assertIn("import ContainerKit", text)

    def test_a_string_with_a_quote_in_it_is_escaped(self) -> None:
        self.assertEqual(r'"a\"b"', swiftgen.swift_string('a"b'))
        self.assertEqual(r'"a\\b"', swiftgen.swift_string("a\\b"))


def _schema_screen_modes() -> list[str]:
    import json
    schema = json.loads(paths.SCHEMA_FILE.read_text(encoding="utf-8"))
    return schema["$defs"]["variant"]["properties"]["screenMode"]["enum"]


if __name__ == "__main__":
    unittest.main()
