import json
import pathlib
import plistlib
import shutil
import subprocess
import sys
import tempfile
import unittest

import generate
import paths
import variants


class CliTest(unittest.TestCase):
    """The generator end to end, as CI and the Makefile call it."""

    def _run(self, *arguments: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(paths.TOOLS / "generate.py"), *arguments],
            capture_output=True, text=True,
        )

    def test_dump_json_matches_what_gradle_prints(self) -> None:
        # The two dumps are diffed in CI; this only checks the shape is stable and
        # parseable, since Gradle is not available here.
        result = self._run("--dump-json")
        self.assertEqual(0, result.returncode, result.stderr)
        document = json.loads(result.stdout)
        self.assertEqual([v.id for v in variants.load()], [d["id"] for d in document])
        # Canonical: sorted keys, two-space indent, trailing newline.
        self.assertEqual(result.stdout, json.dumps(document, indent=2, sort_keys=True) + "\n")

    def test_a_full_generate_writes_every_target(self) -> None:
        result = self._run("--app-version", "v1.2.3")
        self.assertEqual(0, result.returncode, result.stderr)
        declared = variants.load()
        for variant in declared:
            with self.subTest(variant=variant.id):
                target = paths.variant_dir(variant.id)
                for name in ("main.swift", "VariantConfig.swift", "Info.plist",
                             "Variant.xcconfig"):
                    self.assertTrue((target / name).is_file(), f"missing {name}")
                icon = target / "Assets.xcassets" / "AppIcon.appiconset" / "icon-1024.png"
                self.assertTrue(icon.is_file())
                plist = plistlib.loads((target / "Info.plist").read_bytes())
                self.assertEqual(variant.id, plist["ContainerVariantId"])
        self.assertTrue(paths.VARIANTS_SPEC.is_file())
        self.assertIn("MARKETING_VERSION = 1.2.3", paths.VERSION_XCCONFIG.read_text())

    def test_skip_icons_still_produces_a_complete_project(self) -> None:
        result = self._run("--skip-icons", "--app-version", "v1.2.3")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(paths.VARIANTS_SPEC.is_file())
        # Regenerate with icons so the working tree is not left half-built.
        self.addCleanup(self._run, "--app-version", "v1.2.3")

    def test_the_two_icon_switches_contradict_each_other(self) -> None:
        result = self._run("--skip-icons", "--only-icons")
        self.assertNotEqual(0, result.returncode)

    def test_skip_icons_leaves_icons_that_are_already_there(self) -> None:
        """The regression that would have shipped apps with no icon.

        CI draws the icons on a Linux runner, hands them to the macOS one as an
        artifact, and then generates with --skip-icons. A generator that cleared
        its whole output tree first would delete them, and the only symptom would
        be a published .ipa with a blank icon.
        """
        self.assertEqual(0, self._run("--app-version", "v1.2.3").returncode)
        icons = {
            variant.id: paths.variant_dir(variant.id) / "Assets.xcassets"
            / "AppIcon.appiconset" / "icon-1024.png"
            for variant in variants.load()
        }
        before = {}
        for variant_id, icon in icons.items():
            self.assertTrue(icon.is_file(), f"{variant_id}: nothing drawn to begin with")
            before[variant_id] = icon.read_bytes()

        result = self._run("--skip-icons", "--app-version", "v1.2.3")
        self.assertEqual(0, result.returncode, result.stderr)

        for variant_id, icon in icons.items():
            with self.subTest(variant=variant_id):
                self.assertTrue(icon.is_file(), "the icon was deleted")
                self.assertEqual(before[variant_id], icon.read_bytes(), "the icon changed")

    def test_a_removed_variant_leaves_no_stale_target(self) -> None:
        # The generated tree is the only place a deleted variant could linger, and
        # a leftover directory is a target XcodeGen would happily keep building.
        stale = paths.variant_dir("ghost")
        stale.mkdir(parents=True, exist_ok=True)
        (stale / "main.swift").write_text("// left over\n", encoding="utf-8")
        result = self._run("--skip-icons", "--app-version", "v1.2.3")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(stale.exists(), "a stale target directory survived")
        self.addCleanup(self._run, "--app-version", "v1.2.3")

    def test_a_bad_version_fails_with_a_message_rather_than_a_traceback(self) -> None:
        result = self._run("--app-version", "banana")
        self.assertEqual(1, result.returncode)
        self.assertTrue(result.stderr.startswith("error:"), result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.addCleanup(self._run, "--app-version", "v1.2.3")


if __name__ == "__main__":
    unittest.main()
