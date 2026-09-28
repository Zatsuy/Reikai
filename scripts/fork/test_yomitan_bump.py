#!/usr/bin/env python3
"""Tests for yomitan_bump.py's offline parts. Run: python3 scripts/fork/test_yomitan_bump.py"""
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import yomitan_bump as bump  # noqa: E402


def make_zip(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return buffer.getvalue()


class VerifyTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.tree = self.dir / "yomitan"
        self.release = self.dir / "yomitan-release.json"
        bump.extract(make_zip({"manifest.json": b"{}", "js/a.js": b"a", "lib/b.wasm": b"\0b"}), self.tree)
        self.release.write_text(json.dumps({"version": "1", "files": bump.file_hashes(self.tree)}))

    def test_untouched_tree_verifies(self):
        self.assertEqual(bump.verify(self.tree, self.release), [])

    def test_a_changed_file_is_reported(self):
        (self.tree / "js/a.js").write_bytes(b"edited by hand")
        self.assertEqual(bump.verify(self.tree, self.release), ["changed: js/a.js"])

    def test_a_missing_file_is_reported(self):
        (self.tree / "lib/b.wasm").unlink()
        self.assertEqual(bump.verify(self.tree, self.release), ["missing: lib/b.wasm"])

    def test_an_added_file_is_reported(self):
        (self.tree / "js/extra.js").write_bytes(b"x")
        self.assertEqual(bump.verify(self.tree, self.release), ["not in the release: js/extra.js"])

    def test_a_missing_record_is_reported(self):
        self.release.unlink()
        self.assertEqual(bump.verify(self.tree, self.release), ["yomitan-release.json is missing"])


class ExtractTest(unittest.TestCase):
    def test_extract_wipes_the_old_tree(self):
        tree = Path(tempfile.mkdtemp()) / "yomitan"
        bump.extract(make_zip({"old.js": b"1"}), tree)
        bump.extract(make_zip({"new.js": b"2"}), tree)
        self.assertEqual(sorted(bump.file_hashes(tree)), ["new.js"])

    def test_extract_refuses_an_entry_outside_the_tree(self):
        tree = Path(tempfile.mkdtemp()) / "yomitan"
        with self.assertRaises(SystemExit):
            bump.extract(make_zip({"../escape.js": b"x"}), tree)


class ExpectedShaTest(unittest.TestCase):
    def test_agreeing_sources_give_the_digest(self):
        pinned = bump.PINNED["26.9.8.0"]
        self.assertEqual(bump.expected_sha256("26.9.8.0", pinned.upper(), None), pinned)

    def test_disagreeing_sources_stop_the_vendor_run(self):
        with self.assertRaises(SystemExit):
            bump.expected_sha256("26.9.8.0", "0" * 64, None)

    def test_an_unknown_release_without_any_digest_stops(self):
        with self.assertRaises(SystemExit):
            bump.expected_sha256("0.0.0", None, None)


if __name__ == "__main__":
    unittest.main()
