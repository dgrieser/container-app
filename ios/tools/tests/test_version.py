import unittest

import paths
import version


class TagTest(unittest.TestCase):
    """Mirrors AppVersionTest, so one tag yields one version on both platforms."""

    def test_a_tag_becomes_a_name_and_an_ordered_code(self) -> None:
        self.assertEqual(("1.2.3", 10_203), _both("v1.2.3"))
        self.assertEqual(("1.2.3", 10_203), _both("1.2.3"))
        # The tags this repository actually uses, and their successors.
        self.assertEqual(8, version.parse("v0.0.8").code)
        self.assertEqual(9, version.parse("v0.0.9").code)
        self.assertEqual(100, version.parse("v0.1.0").code)
        self.assertEqual(10_000, version.parse("v1.0").code)

    def test_every_release_supersedes_the_one_before_it(self) -> None:
        released = [
            version.parse(tag).code
            for tag in (
                "v0.0.8", "v0.0.9", "v0.0.10", "v0.1.0",
                "v0.1.1", "v1.0", "v1.0.1", "v2.0.0",
            )
        ]
        for earlier, later in zip(released, released[1:]):
            self.assertLess(earlier, later)
        # All of them newer than the versionCode 1 every release shipped with up
        # to v0.0.8, so those upgrade cleanly.
        self.assertTrue(all(code > 1 for code in released))

    def test_a_prerelease_or_describe_suffix_stays_in_the_name_only(self) -> None:
        self.assertEqual(("1.2.3-rc1", 10_203), _both("v1.2.3-rc1"))
        self.assertEqual(("0.0.8-12-gabc1234", 8), _both("v0.0.8-12-gabc1234"))
        self.assertEqual(("0.0.8-12-gabc1234-dirty", 8), _both("v0.0.8-12-gabc1234-dirty"))

    def test_an_unknown_version_still_builds(self) -> None:
        for raw in (None, "   "):
            self.assertEqual(1, version.parse(raw).code)
            self.assertIn("unknown", version.parse(raw).name)

    def test_a_version_that_would_break_ordering_is_rejected(self) -> None:
        # 100 in a place worth 100 of the next one up would collide with the
        # component above it: 1.100.0 and 2.0.0 are both 20000.
        for tag in ("v1.100.0", "v1.2.100", "v99999.0.0", "release-1", "v1.2.3.4"):
            with self.subTest(tag=tag):
                with self.assertRaises(version.VersionError):
                    version.parse(tag)


class AppleConstraintsTest(unittest.TestCase):
    """The half of the version that Android has no rule about."""

    def test_the_short_version_is_one_to_three_integers(self) -> None:
        # Apple requires it, and App Store Connect / altool reject anything else.
        # A suffix in CFBundleShortVersionString is the mistake this guards.
        for tag in ("v1.2.3", "v1.2.3-rc1", "v1.2.3-gasoline", "v1.2", "v1",
                    "v0.0.8-12-gabc1234-dirty", None):
            with self.subTest(tag=tag):
                short = version.parse(tag).short
                parts = short.split(".")
                self.assertLessEqual(len(parts), 3)
                self.assertTrue(all(part.isdigit() for part in parts), short)

    def test_the_suffix_lives_in_the_version_name_instead(self) -> None:
        resolved = version.parse("v1.2.3")
        self.assertEqual("1.2.3-gasoline", resolved.version_name("-gasoline"))
        self.assertEqual("1.2.3", resolved.version_name(None))
        # …and never in the short version, whatever the suffix is.
        self.assertEqual("1.2.3", resolved.short)

    def test_the_bundle_version_is_a_single_integer(self) -> None:
        # CFBundleVersion is also limited to one to three integers; Android's
        # versionCode is one, so the ordering rule transfers unchanged.
        self.assertEqual("10203", str(version.parse("v1.2.3").code))

    def test_the_xcconfig_declares_both_numbers(self) -> None:
        text = version.xcconfig(version.parse("v1.2.3"))
        self.assertIn("MARKETING_VERSION = 1.2.3\n", text)
        self.assertIn("CURRENT_PROJECT_VERSION = 10203\n", text)


class DescribeTest(unittest.TestCase):
    def test_a_working_copy_answers_or_says_nothing(self) -> None:
        # None is a valid answer (no tags, no git, a source download) and must
        # not raise: a local build still has to succeed, marked as unknown.
        described = version.describe(paths.REPO_ROOT)
        if described is not None:
            self.assertTrue(version.VERSION_PATTERN.match(described))

    def test_a_declared_version_wins_over_the_working_copy(self) -> None:
        self.assertEqual("1.2.3", version.resolve("v1.2.3", paths.REPO_ROOT).name)


def _both(tag: str) -> tuple[str, int]:
    resolved = version.parse(tag)
    return resolved.name, resolved.code


if __name__ == "__main__":
    unittest.main()
