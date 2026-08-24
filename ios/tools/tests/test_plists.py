import plistlib
import unittest

import plists
import variants


class PerVariantKeysTest(unittest.TestCase):
    """The iOS counterpart of GenerateVariantManifestTaskTest.

    Its subject is the same: entries that belong to *some* variants only, and
    which no other variant may quietly acquire.
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.declared = variants.load()
        cls.plists = {v.id: plists.info_plist(v) for v in cls.declared}

    def test_only_a_variant_that_asks_for_location_declares_it(self) -> None:
        # This is what "the APK carries no location permission at all" becomes on
        # iOS: without the usage description a build cannot ask, because iOS
        # terminates an app that tries.
        for variant in self.declared:
            with self.subTest(variant=variant.id):
                declared = "NSLocationWhenInUseUsageDescription" in self.plists[variant.id]
                self.assertEqual(variant.allow_location, declared)

    def test_the_purpose_string_is_the_variant_s_own_words_when_it_has_them(self) -> None:
        for variant in self.declared:
            if variant.location_reason:
                with self.subTest(variant=variant.id):
                    self.assertEqual(
                        variant.location_reason,
                        self.plists[variant.id]["NSLocationWhenInUseUsageDescription"],
                    )

    def test_the_status_bar_starts_where_the_screen_mode_says(self) -> None:
        for variant in self.declared:
            with self.subTest(variant=variant.id):
                hidden = self.plists[variant.id]["UIStatusBarHidden"]
                self.assertEqual(variant.screen_mode in ("statusBar", "systemBars"), not hidden)

    def test_every_plist_is_serialisable(self) -> None:
        for variant in self.declared:
            with self.subTest(variant=variant.id):
                # A value plistlib cannot write is a target that will not build,
                # and the error would surface only on a Mac.
                self.assertEqual(
                    self.plists[variant.id], plistlib.loads(plists.dumps(variant))
                )

    def test_the_version_keys_are_left_to_the_build_settings(self) -> None:
        for variant in self.declared:
            with self.subTest(variant=variant.id):
                plist = self.plists[variant.id]
                self.assertEqual("$(MARKETING_VERSION)", plist["CFBundleShortVersionString"])
                self.assertEqual("$(CURRENT_PROJECT_VERSION)", plist["CFBundleVersion"])
                self.assertEqual("$(CONTAINER_VERSION_NAME)", plist["ContainerVersionName"])

    def test_a_built_app_says_which_variant_it_is(self) -> None:
        for variant in self.declared:
            with self.subTest(variant=variant.id):
                self.assertEqual(variant.id, self.plists[variant.id]["ContainerVariantId"])
                self.assertEqual(variant.name, self.plists[variant.id]["CFBundleDisplayName"])


class TransportSecurityTest(unittest.TestCase):
    """ATS is the half of the TLS story that lives in the plist -- and only that half."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.declared = variants.load()
        cls.plists = {v.id: plists.info_plist(v) for v in cls.declared}

    def test_cleartext_is_never_allowed_anywhere(self) -> None:
        # Android blocks it outright, and "the switch relaxes certificate
        # verification, not the requirement to use TLS" has to stay true here.
        for variant in self.declared:
            with self.subTest(variant=variant.id):
                security = self.plists[variant.id]["NSAppTransportSecurity"]
                self.assertFalse(security["NSAllowsArbitraryLoads"])
                self.assertFalse(security["NSAllowsArbitraryLoadsInWebContent"])

    def test_only_a_variant_trusting_its_server_gets_an_exception(self) -> None:
        for variant in self.declared:
            with self.subTest(variant=variant.id):
                security = self.plists[variant.id]["NSAppTransportSecurity"]
                has_exception = "NSExceptionDomains" in security
                self.assertEqual(variant.allow_unverified_ssl, has_exception)

    def test_the_exception_is_scoped_to_the_variant_s_own_host(self) -> None:
        for variant in self.declared:
            if not variant.allow_unverified_ssl:
                continue
            with self.subTest(variant=variant.id):
                domains = self.plists[variant.id]["NSAppTransportSecurity"]["NSExceptionDomains"]
                # One host, and it is the one the variant is pinned to -- not a
                # blanket allowance a page could take advantage of.
                self.assertEqual(1, len(domains))
                host = next(iter(domains))
                self.assertIn(host, variant.default_kiosk_path or variant.config_url)

    def test_a_bare_hostname_also_gets_the_local_network_prompt(self) -> None:
        # `https://raspberrypi` is local networking as far as iOS is concerned,
        # and the prompt needs its own usage description or the connection fails
        # with nothing shown to the user. Android needed neither.
        for variant in self.declared:
            with self.subTest(variant=variant.id):
                pinned = variant.default_kiosk_path or variant.config_url
                expected = variant.allow_unverified_ssl and plists._is_bare_hostname(pinned)
                self.assertEqual(
                    expected, "NSLocalNetworkUsageDescription" in self.plists[variant.id]
                )

    def test_a_dotted_host_is_not_treated_as_local(self) -> None:
        self.assertFalse(plists._is_bare_hostname("https://example.com/"))
        self.assertTrue(plists._is_bare_hostname("https://raspberrypi/podcaster"))
        self.assertFalse(plists._is_bare_hostname(""))


if __name__ == "__main__":
    unittest.main()
