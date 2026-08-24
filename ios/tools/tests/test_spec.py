import shutil
import subprocess
import tempfile
import unittest
import pathlib

import paths
import variants
import xcodegen_spec
import yaml


class SpecTest(unittest.TestCase):
    """The generated half of the XcodeGen spec."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.declared = variants.load()
        cls.spec = xcodegen_spec.spec(cls.declared)

    def test_one_target_and_one_scheme_per_variant(self) -> None:
        expected = {v.id for v in self.declared}
        self.assertEqual(expected, set(self.spec["targets"]))
        self.assertEqual(expected, set(self.spec["schemes"]))

    def test_every_target_uses_the_hand_written_template(self) -> None:
        # The generated half must carry no build settings of its own: that is what
        # keeps "how the app is built" in one editable place.
        for name, target in self.spec["targets"].items():
            with self.subTest(target=name):
                self.assertEqual(["Variant"], target["templates"])
                self.assertEqual({"templates", "templateAttributes"}, set(target))

    def test_each_target_carries_its_bundle_id(self) -> None:
        by_id = {v.id: v for v in self.declared}
        for name, target in self.spec["targets"].items():
            with self.subTest(target=name):
                attributes = target["templateAttributes"]
                self.assertEqual(name, attributes["variantId"])
                self.assertEqual(by_id[name].application_id, attributes["bundleId"])

    def test_bundle_ids_are_unique(self) -> None:
        ids = [t["templateAttributes"]["bundleId"] for t in self.spec["targets"].values()]
        self.assertEqual(len(ids), len(set(ids)))

    def test_a_scheme_archives_release_and_runs_debug(self) -> None:
        for name, scheme in self.spec["schemes"].items():
            with self.subTest(scheme=name):
                self.assertEqual("Release", scheme["archive"]["config"])
                self.assertEqual("Debug", scheme["run"]["config"])
                self.assertEqual({name: "all"}, scheme["build"]["targets"])

    def test_every_scheme_runs_the_shared_tests(self) -> None:
        # The tests cover ContainerKit, which every variant is built out of, so
        # picking any scheme in Xcode and hitting test does the right thing.
        for name, scheme in self.spec["schemes"].items():
            with self.subTest(scheme=name):
                self.assertEqual(
                    [xcodegen_spec.TEST_TARGET], scheme["test"]["targets"]
                )

    def test_the_test_target_is_declared_in_the_hand_written_half(self) -> None:
        # The generated half must not invent a target: a scheme naming one that
        # does not exist is a project Xcode refuses to open.
        project = yaml.safe_load((paths.IOS / "project.yml").read_text(encoding="utf-8"))
        self.assertIn(xcodegen_spec.TEST_TARGET, project.get("targets", {}))

    def test_the_dump_is_valid_yaml_and_carries_the_do_not_edit_header(self) -> None:
        text = xcodegen_spec.dumps(self.declared)
        self.assertTrue(text.startswith("# Generated from app-variants.yaml"))
        self.assertIn("do not edit", text)
        self.assertEqual(self.spec, yaml.safe_load(text))


@unittest.skipUnless(shutil.which("xcodegen"), "needs xcodegen (macOS)")
class XcodeGenTest(unittest.TestCase):
    """Only runs where xcodegen exists, i.e. on a developer's Mac or in CI."""

    def test_the_project_generates(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            result = subprocess.run(
                ["xcodegen", "generate", "--spec", str(paths.IOS / "project.yml"),
                 "--project", raw],
                capture_output=True, text=True,
            )
            self.assertEqual(0, result.returncode, result.stderr or result.stdout)
            self.assertTrue(list(pathlib.Path(raw).glob("*.xcodeproj")))


if __name__ == "__main__":
    unittest.main()
