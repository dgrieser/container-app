#!/usr/bin/env python3
"""Prints the name of an available iPhone simulator.

`xcodebuild test` needs a *concrete* destination — unlike `xcodebuild build`,
which accepts `generic/platform=iOS Simulator` — and which iPhones a machine
carries changes with the Xcode version and, on CI, with the runner image. So the
name is discovered rather than written down, here rather than in two places:
the Makefile and the workflow both call this.

    $ python3 ios/tools/pick_simulator.py
    iPhone 16 Pro
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys


def available_iphones(payload: str) -> list[str]:
    """Every available iPhone in `simctl list devices available --json` output."""
    devices = json.loads(payload).get("devices", {})
    return [
        device["name"]
        for runtime in devices
        for device in devices[runtime]
        if "iPhone" in device.get("name", "")
    ]


def pick(names: list[str]) -> str:
    """One of them, chosen the same way every time.

    Any available iPhone will do for a logic-test run, so the only thing that
    matters is that repeated runs on one machine agree — a destination that
    wandered between runs would make a flake impossible to reproduce.
    """
    return sorted(names)[-1]


def main() -> int:
    if not shutil.which("xcrun"):
        print("xcrun not found: this needs Xcode's command-line tools.", file=sys.stderr)
        return 1
    result = subprocess.run(
        ["xcrun", "simctl", "list", "devices", "available", "--json"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print(f"simctl failed: {result.stderr.strip()}", file=sys.stderr)
        return 1

    names = available_iphones(result.stdout)
    if not names:
        print("No iPhone simulator is available. Install one in Xcode's "
              "Settings -> Platforms.", file=sys.stderr)
        return 1
    print(pick(names))
    return 0


if __name__ == "__main__":
    sys.exit(main())
