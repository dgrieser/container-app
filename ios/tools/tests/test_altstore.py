import json
import unittest

import altstore
import variants
import version


REPO = "https://github.com/dgrieser/container-app"
ASSETS = "https://dgrieser.github.io/container-app"


def _entry(variant, tag="v1.2.3", size=4_123_456):
    resolved = version.parse(tag)
    return altstore.app_entry(
        variant,
        resolved,
        repository_url=REPO,
        assets_url=ASSETS,
        download_url=(
            f"{REPO}/releases/download/{tag}/"
            f"container-app-{variant.id}-{tag}-ios.ipa"
        ),
        size=size,
        date="2026-08-24",
        developer_name="David Grieser",
    )


class SourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.declared = variants.load()
        cls.apps = [_entry(v) for v in cls.declared]
        cls.source = altstore.source(cls.apps, repository_url=REPO)

    def test_one_app_per_variant(self) -> None:
        self.assertEqual(
            [v.name for v in self.declared], [a["name"] for a in self.source["apps"]]
        )

    def test_every_app_carries_what_altstore_needs_to_install_it(self) -> None:
        for app in self.source["apps"]:
            with self.subTest(app=app["name"]):
                for key in ("name", "bundleIdentifier", "developerName", "iconURL", "versions"):
                    self.assertTrue(app.get(key), f"{key} is missing or empty")
                newest = app["versions"][0]
                for key in ("version", "buildVersion", "date", "downloadURL", "size"):
                    self.assertTrue(newest.get(key), f"versions[0].{key} is missing")
                self.assertGreater(newest["size"], 0)
                self.assertTrue(newest["downloadURL"].endswith(".ipa"))

    def test_the_version_is_the_numeric_one_altstore_can_compare(self) -> None:
        # AltStore orders versions itself, so a suffix here would make "1.2.3" and
        # "1.2.3-gasoline" incomparable. The suffix is in the filename instead.
        for app in self.source["apps"]:
            with self.subTest(app=app["name"]):
                self.assertEqual("1.2.3", app["versions"][0]["version"])
                self.assertEqual("10203", app["versions"][0]["buildVersion"])

    def test_the_tint_is_hex_without_a_hash(self) -> None:
        for app in self.source["apps"]:
            with self.subTest(app=app["name"]):
                self.assertRegex(app["tintColor"], r"^[0-9A-F]{6}$")

    def test_an_alpha_channel_is_dropped_from_the_tint(self) -> None:
        self.assertEqual("0D0E11", altstore._tint("#CC0D0E11"))
        self.assertEqual("1F6FEB", altstore._tint("#1F6FEB"))

    def test_only_a_variant_wanting_location_declares_it_to_the_user(self) -> None:
        by_id = {v.application_id: v for v in self.declared}
        for app in self.source["apps"]:
            with self.subTest(app=app["name"]):
                privacy = app["appPermissions"]["privacy"]
                self.assertEqual(
                    by_id[app["bundleIdentifier"]].allow_location, bool(privacy)
                )

    def test_it_serialises(self) -> None:
        self.assertEqual(self.source, json.loads(altstore.dumps(self.source)))


class MergeTest(unittest.TestCase):
    """A release adds a version rather than replacing the list."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.declared = variants.load()

    def _source(self, tag: str) -> dict:
        return altstore.source(
            [_entry(v, tag=tag) for v in self.declared], repository_url=REPO
        )

    def test_an_older_version_stays_installable(self) -> None:
        merged = altstore.merge(self._source("v1.0.0"), self._source("v1.1.0"))
        for app in merged["apps"]:
            with self.subTest(app=app["name"]):
                self.assertEqual(["1.1.0", "1.0.0"], [v["version"] for v in app["versions"]])

    def test_rebuilding_the_same_version_replaces_it(self) -> None:
        first = self._source("v1.0.0")
        merged = altstore.merge(first, self._source("v1.0.0"))
        for app in merged["apps"]:
            with self.subTest(app=app["name"]):
                self.assertEqual(["1.0.0"], [v["version"] for v in app["versions"]])

    def test_the_first_release_needs_no_previous_source(self) -> None:
        current = self._source("v1.0.0")
        self.assertEqual(current, altstore.merge(None, current))

    def test_a_new_variant_appears_without_disturbing_the_others(self) -> None:
        previous = altstore.source(
            [_entry(self.declared[0], tag="v1.0.0")], repository_url=REPO
        )
        merged = altstore.merge(previous, self._source("v1.1.0"))
        self.assertEqual(len(self.declared), len(merged["apps"]))
        # The one that existed before keeps its history; the new ones start fresh.
        first = next(a for a in merged["apps"]
                     if a["bundleIdentifier"] == self.declared[0].application_id)
        self.assertEqual(["1.1.0", "1.0.0"], [v["version"] for v in first["versions"]])
        others = [a for a in merged["apps"] if a is not first]
        for app in others:
            with self.subTest(app=app["name"]):
                self.assertEqual(["1.1.0"], [v["version"] for v in app["versions"]])


if __name__ == "__main__":
    unittest.main()
