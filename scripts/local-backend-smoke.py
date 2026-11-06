#!/usr/bin/env python3
"""Backward-compatible Android entry point for the shared local SDK smoke."""
from pathlib import Path
import sys


def main():
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
    from local_backend_smoke import main as shared_main
    shared_main(default_platform="android")


if __name__ == "__main__":
    main()
