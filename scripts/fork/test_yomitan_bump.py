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


class DecideTest(unittest.TestCase):
    NOW = bump.datetime(2026, 10, 1, tzinfo=bump.timezone.utc)

    def test_a_newer_release_is_taken_after_its_wait(self):
        update, _ = bump.decide("26.9.8.0", "26.9.15.0", "2026-09-15T01:00:00Z", 7, self.NOW)
        self.assertTrue(update)

    def test_a_newer_release_waits_its_seven_days(self):
        update, reason = bump.decide("26.9.8.0", "26.9.29.0", "2026-09-29T01:00:00Z", 7, self.NOW)
        self.assertFalse(update)
        self.assertIn("2026-10-06", reason)

    def test_versions_compare_as_numbers(self):
        self.assertTrue(bump.decide("26.9.8.0", "26.10.1.0", "2026-09-01T00:00:00Z", 7, self.NOW)[0])
        self.assertFalse(bump.decide("26.10.1.0", "26.9.8.0", "2026-09-01T00:00:00Z", 7, self.NOW)[0])
        self.assertFalse(bump.decide("26.9.8.0", "26.9.8.0", "2026-09-01T00:00:00Z", 7, self.NOW)[0])

    def test_an_unexpected_tag_stops(self):
        with self.assertRaises(SystemExit):
            bump.decide("26.9.8.0", "v27-beta", "2026-09-01T00:00:00Z", 7, self.NOW)


class StripCommentsTest(unittest.TestCase):
    def test_comments_go_and_code_strings_and_regexes_stay(self):
        code = ("/** @type {chrome.tabs.Tab} */\n"
                "const a = /** @type {chrome.runtime.Port} */ (x); // chrome.bookmarks.get()\n"
                "const url = 'https://example.org/*not a comment*/';\n"
                "const re = /[/*]+/g; chrome.tabs.query({});\n")
        stripped = bump.strip_js_comments(code)
        self.assertEqual(stripped.count("\n"), code.count("\n"))
        self.assertNotIn("chrome.tabs.Tab", stripped)
        self.assertNotIn("chrome.runtime.Port", stripped)
        self.assertNotIn("bookmarks", stripped)
        self.assertIn("'https://example.org/*not a comment*/'", stripped)
        self.assertIn("/[/*]+/g; chrome.tabs.query({});", stripped)


class TripwireTest(unittest.TestCase):
    """A tiny stand-in for a Yomitan tree, its baseline, then a 'release' that changes it."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.tree = self.dir / "yomitan"
        self.write("manifest.json", json.dumps({"manifest_version": 3, "background": {"page": "background.html"},
                                                "permissions": ["storage"]}))
        self.write("js/application.js", "export class Application {\n    static async main(a) {\n"
                                        "        const x = 'serviceWorker' in navigator;\n    }\n}\n")
        self.write("js/comm/anki-connect.js", "class A {\n    f() { return this._invoke('addNote', {}); }\n}\n")
        self.write("js/background/backend.js", "chrome.runtime.onMessage.addListener(f);\n"
                                               "this._apiMap = createApiMap([\n    ['termsFind', this._onApiTermsFind],\n]);\n")
        self.release = self.dir / "yomitan-release.json"
        self.release.write_text(json.dumps({"version": "1.0.0.0", "files": {}}))
        self.baseline = self.dir / "yomitan-surface.json"
        bump.write_baseline(self.tree, self.baseline, self.release)

    def write(self, rel, text):
        path = self.tree / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def test_an_unchanged_tree_passes(self):
        self.assertEqual(bump.tripwire(self.tree, self.baseline), [])

    def test_new_api_uses_and_removed_actions_are_listed(self):
        self.write("js/background/backend.js", "chrome.runtime.onMessage.addListener(f);\nchrome.sidePanel.open({});\n"
                                               "this._apiMap = createApiMap([\n]);\n")
        report = bump.tripwire(self.tree, self.baseline)
        self.assertIn("  + chrome.sidePanel.open", report)
        self.assertIn("  - _apiMap: termsFind", report)

    def test_a_changed_call_site_is_listed(self):
        self.write("js/application.js", "export class Application {\n    static async main(a) {\n"
                                        "        const x = true;\n    }\n}\n")
        report = bump.tripwire(self.tree, self.baseline)
        self.assertIn("call-sites:", report)
        self.assertTrue(any(line.startswith("  - js/application.js static async main(") for line in report))
        self.assertIn("  - js/application.js: const x = 'serviceWorker' in navigator;", report)

    def test_a_comment_only_change_passes(self):
        self.write("js/application.js", "/* Copyright 2027 */\nexport class Application {\n    static async main(a) {\n"
                                        "        // a new remark\n        const x = 'serviceWorker' in navigator;\n    }\n}\n")
        self.assertEqual(bump.tripwire(self.tree, self.baseline), [])

    def test_a_missing_baseline_fails(self):
        self.baseline.unlink()
        self.assertEqual(len(bump.tripwire(self.tree, self.baseline)), 1)


class VendoredBaselineTest(unittest.TestCase):
    def test_the_vendored_release_matches_the_committed_baseline(self):
        self.assertEqual(bump.tripwire(), [])


if __name__ == "__main__":
    unittest.main()
