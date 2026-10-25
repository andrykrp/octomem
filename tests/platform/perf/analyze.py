"""Compatibility entrypoint for the single validated TEST-05 analyzer."""
import pathlib
import sys

if __name__ == "__main__":
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    from finalize import main
    raise SystemExit(main())
