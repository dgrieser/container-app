import unittest

import pick_simulator


SAMPLE = """
{"devices": {
  "com.apple.CoreSimulator.SimRuntime.iOS-18-2": [
    {"name": "iPhone 16", "isAvailable": true},
    {"name": "iPhone 16 Pro", "isAvailable": true},
    {"name": "iPad Pro 11-inch (M4)", "isAvailable": true}
  ],
  "com.apple.CoreSimulator.SimRuntime.iOS-17-5": [
    {"name": "iPhone 15", "isAvailable": true}
  ]
}}
"""


class AvailableIPhonesTest(unittest.TestCase):

    def test_it_finds_iphones_across_every_runtime(self) -> None:
        self.assertEqual(
            ["iPhone 16", "iPhone 16 Pro", "iPhone 15"],
            pick_simulator.available_iphones(SAMPLE),
        )

    def test_it_ignores_everything_that_is_not_an_iphone(self) -> None:
        self.assertNotIn("iPad Pro 11-inch (M4)", pick_simulator.available_iphones(SAMPLE))

    def test_an_empty_list_is_not_a_crash(self) -> None:
        # A runner image with no iPhone runtime installed. The caller turns this
        # into a message rather than an IndexError.
        self.assertEqual([], pick_simulator.available_iphones('{"devices": {}}'))
        self.assertEqual([], pick_simulator.available_iphones('{}'))

    def test_a_device_without_a_name_is_skipped(self) -> None:
        self.assertEqual(
            [], pick_simulator.available_iphones('{"devices": {"r": [{"udid": "x"}]}}')
        )


class PickTest(unittest.TestCase):

    def test_the_choice_is_stable(self) -> None:
        # Any iPhone will do; what matters is that two runs on one machine agree,
        # or a flake could not be reproduced.
        names = pick_simulator.available_iphones(SAMPLE)
        self.assertEqual(pick_simulator.pick(names), pick_simulator.pick(list(reversed(names))))

    def test_it_returns_one_of_the_names_it_was_given(self) -> None:
        names = pick_simulator.available_iphones(SAMPLE)
        self.assertIn(pick_simulator.pick(names), names)


if __name__ == "__main__":
    unittest.main()
