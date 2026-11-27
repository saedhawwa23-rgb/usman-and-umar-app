#!/usr/bin/env python3
"""Validates the emulator screenshot sets for one API level.

Every rule here exists to catch a capture that looked successful but was not:
a missing screen, a truncated PNG, a stale v3.10.5 label, or an extra file that
the workflow never asked for. The script exits non-zero on the first violation
so the job fails instead of publishing a misleading artifact.
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"

# Both the baseline and the current tree render the same seven destinations.
COMMON_SCREENS = (
    "task-bar",
    "tool-bar",
    "ai-providers",
    "github",
    "termux-linux",
    "plugin-platform",
    "remote-platform",
)
AGENT_SCREEN = "agent-platforms"


def png_dimensions(path: Path) -> tuple[int, int]:
    with path.open("rb") as handle:
        header = handle.read(24)
    if len(header) < 24 or header[:8] != PNG_SIGNATURE:
        raise ValueError("not a PNG")
    if header[12:16] != b"IHDR":
        raise ValueError("missing IHDR")
    width, height = struct.unpack(">II", header[16:24])
    return width, height


def verify(directory: Path, version: str, screens: tuple[str, ...], min_width: int, min_height: int) -> list[str]:
    errors: list[str] = []
    if not directory.is_dir():
        return [f"{directory} does not exist"]

    expected = {f"{version}-{name}.png" for name in screens}
    present = {path.name for path in directory.glob("*.png")}

    for missing in sorted(expected - present):
        errors.append(f"missing screenshot: {missing}")
    for extra in sorted(present - expected):
        errors.append(f"unexpected screenshot: {extra}")

    # A stale label means the wrong tree was built, so it is always fatal.
    for path in sorted(directory.glob("*.png")):
        if "-v3.10.5-" in path.name or path.name.startswith("v3.10.5-"):
            errors.append(f"stale v3.10.5 filename: {path.name}")

    for name in sorted(expected & present):
        path = directory / name
        size = path.stat().st_size
        if size == 0:
            errors.append(f"empty screenshot: {name}")
            continue
        try:
            width, height = png_dimensions(path)
        except ValueError as exc:
            errors.append(f"corrupt screenshot: {name} ({exc})")
            continue
        if width < min_width or height < min_height:
            errors.append(f"undersized screenshot: {name} ({width}x{height})")
        print(f"  {name}: {size} bytes, {width}x{height}")

    if len(present) != len(expected):
        errors.append(f"expected {len(expected)} screenshots, found {len(present)}")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--version", required=True, help="expected version label, e.g. 3.12.2")
    parser.add_argument("--screens", required=True, help="comma-separated screen names")
    parser.add_argument("--min-width", type=int, default=200)
    parser.add_argument("--min-height", type=int, default=200)
    args = parser.parse_args()

    screens = tuple(name.strip() for name in args.screens.split(",") if name.strip())
    if not screens:
        print("no screens requested", file=sys.stderr)
        return 1

    print(f"Verifying {args.directory} as v{args.version} ({len(screens)} screens)")
    errors = verify(args.directory, f"v{args.version}", screens, args.min_width, args.min_height)

    if errors:
        print("\nVisual evidence verification FAILED:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print(f"Visual evidence verification passed: {len(screens)} valid screenshots")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
