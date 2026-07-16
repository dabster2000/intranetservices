#!/usr/bin/env python3
"""Run one closed command with inherited stdio and a hard wall-clock deadline."""

from __future__ import annotations

import subprocess
import sys


def main() -> int:
    if len(sys.argv) < 3:
        return 64
    try:
        timeout_seconds = int(sys.argv[1])
    except ValueError:
        return 64
    if timeout_seconds < 1:
        return 64
    try:
        return subprocess.run(sys.argv[2:], timeout=timeout_seconds, check=False).returncode
    except subprocess.TimeoutExpired:
        return 124


if __name__ == "__main__":
    raise SystemExit(main())
