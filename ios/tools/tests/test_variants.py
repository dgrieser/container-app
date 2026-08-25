import pathlib
import unittest

import glyphs as glyphs_module
import paths
import variants


class CorpusTest(unittest.TestCase):
    """The same accept/reject corpus VariantsCorpusTest runs in Kotlin.

    A rule implemented on one side and forgotten on the other fails here or
    there; see variants-testdata/README.md.
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.glyphs = glyphs_module.load(paths.GLYPHS_FILE)

    def cases(self, folder: str) -> list[pathlib.Path]:
        directory = paths.TESTDATA / folder
        self.assertTrue(directory.is_dir(), f"missing {directory}")
        files = sorted(directory.glob("*.yaml"))
        self.assertTrue(files, f"{directory} holds no cases")
        return files

    @staticmethod
    def expectation(case: pathlib.Path) -> str:
        for line in case.read_text(encoding="utf-8").splitlines():
            if line.startswith("# expect:"):
                return line.removeprefix("# expect:").strip()
        return "no `# expect:` comment"

    def load(self, case: pathlib.Path) -> list[variants.Variant]:
        return variants.load(case, "com.example.container", glyphs=self.glyphs)

    def test_every_valid_case_parses(self) -> None:
        for case in self.cases("valid"):
            with self.subTest(case=case.name):
                try:
                    self.load(case)
                except variants.VariantError as error:
                    self.fail(
                        f"{case.name} should have been accepted "
                        f"({self.expectation(case)}): {error}"
                    )

    def test_every_invalid_case_is_rejected(self) -> None:
        for case in self.cases("invalid"):
            with self.subTest(case=case.name):
                with self.assertRaises(
                    variants.VariantError,
                    msg=f"{case.name} should have been rejected: {self.expectation(case)}",
                ):
                    self.load(case)

    def test_every_case_names_the_rule_it_covers(self) -> None:
        # A case with no `# expect:` line is a case whose failure message says
        # nothing, which is how a corpus stops being useful.
        for folder in ("valid", "invalid"):
            for case in self.cases(folder):
                with self.subTest(case=case.name):
                    self.assertNotEqual("no `# expect:` comment", self.expectation(case))


class DefaultsTest(unittest.TestCase):
    """What a variant gets when it declares almost nothing."""

    def load(self, name: str) -> list[variants.Variant]:
        return variants.load(paths.TESTDATA / "valid" / name, "com.example.container")

    def test_minimal_variant_takes_the_schema_defaults(self) -> None:
        variant = self.load("minimal.yaml")[0]
        self.assertEqual("com.example.container.a", variant.application_id)
        self.assertTrue(variant.require_pin)
        self.assertTrue(variant.show_menu)
        self.assertEqual("fullscreen", variant.screen_mode)
        self.assertFalse(variant.allow_unverified_ssl)
        self.assertFalse(variant.allow_external_navigation)
        self.assertFalse(variant.allow_location)
        self.assertIsNone(variant.location_reason)
        self.assertFalse(variant.is_default)
        self.assertEqual("container", variant.icon.glyph)
        self.assertIsNone(variant.icon.vector)
        self.assertEqual("#1F6FEB", variant.icon.background)
        self.assertEqual("#FFFFFF", variant.icon.tint)
        self.assertEqual("#FFFFFF", variant.bar_color.light)
        self.assertEqual("#FFFFFF", variant.bar_color.dark)

    def test_the_default_variant_keeps_the_bare_application_id(self) -> None:
        declared = self.load("default-variant-keeps-bare-id.yaml")
        self.assertEqual("com.example.container", declared[0].application_id)
        self.assertEqual("com.example.container.b", declared[1].application_id)

    def test_a_vector_icon_drops_the_glyph(self) -> None:
        variant = self.load("icon-vector.yaml")[0]
        self.assertIsNone(variant.icon.glyph)
        self.assertEqual("app-icons/gasoline.xml", variant.icon.vector)

    def test_a_translucent_bar_colour_is_kept_as_written(self) -> None:
        variant = self.load("bar-colours.yaml")[0]
        self.assertEqual("#F4F2ED", variant.bar_color.light)
        self.assertEqual("#CC0D0E11", variant.bar_color.dark)

    def test_a_generated_purpose_string_names_the_app(self) -> None:
        variant = self.load("minimal.yaml")[0]
        self.assertIn("A", variant.location_purpose)
        self.assertTrue(variant.location_purpose.endswith("."))


class RepositoryFileTest(unittest.TestCase):
    """The file this repository actually ships."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.declared = variants.load()

    def test_it_parses(self) -> None:
        self.assertTrue(self.declared)
        self.assertEqual(1, sum(1 for v in self.declared if v.is_default))

    def test_every_variant_has_a_symbol_that_exists(self) -> None:
        glyphs = glyphs_module.load(paths.GLYPHS_FILE)
        for variant in self.declared:
            with self.subTest(variant=variant.id):
                self.assertTrue(
                    variant.icon.glyph or variant.icon.vector,
                    "needs a glyph or a vector",
                )
                if variant.icon.glyph:
                    self.assertIsNotNone(glyphs.path_data(variant.icon.glyph))
                if variant.icon.vector:
                    self.assertTrue((paths.REPO_ROOT / variant.icon.vector).is_file())

    def test_a_variant_wanting_location_always_has_a_purpose_string(self) -> None:
        # iOS terminates an app that asks for a position without one, so this is
        # not a nicety: an empty purpose is a crash on first use.
        for variant in self.declared:
            if variant.allow_location:
                with self.subTest(variant=variant.id):
                    self.assertTrue(variant.location_purpose.strip())

    def test_bundle_identifiers_are_unique(self) -> None:
        ids = [v.application_id for v in self.declared]
        self.assertEqual(len(ids), len(set(ids)))


if __name__ == "__main__":
    unittest.main()
