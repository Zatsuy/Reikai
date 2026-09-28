#!/usr/bin/env python3
"""Vendor Yomitan's official release into the jp-yomitan module, and check what is vendored.

Yomitan's files are never edited by hand (AGENTS.md rule 3): they arrive only through this script,
unzipped from the release's `yomitan-firefox.zip` into jp-yomitan/src/main/assets/yomitan/, with
jp-yomitan/yomitan-release.json recording the version, the zip's URL and SHA-256, and the SHA-256
of every vendored file. The Firefox build is the one whose backend runs as a page (background.html),
which is what an Android WebView can host.

Usage:
  scripts/fork/yomitan_bump.py vendor TAG [--sha256 HEX]
      download TAG's yomitan-firefox.zip, check its SHA-256 against GitHub's asset digest and the
      pinned value (PINNED below, or --sha256), wipe the vendored tree and unzip the release into it
  scripts/fork/yomitan_bump.py verify
      exit 1 unless the vendored tree matches yomitan-release.json exactly (no file changed,
      missing or added)

Roadmap 3.6 adds `check` (is there a newer promoted release?) and the static tripwire here; keep
each command a function taking explicit paths, so they and the tests can reuse them.
"""
import argparse
import hashlib
import io
import json
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "jp-yomitan"
ASSETS = MODULE / "src/main/assets/yomitan"
RELEASE_JSON = MODULE / "yomitan-release.json"

REPO = "yomidevs/yomitan"
ASSET = "yomitan-firefox.zip"
# Known-good release zips, so a vendor run never trusts the network alone. Add a line per release
# vendored by hand; the automatic update (3.6) relies on GitHub's asset digest instead.
PINNED = {
    "26.9.8.0": "8c23aa2d61ebeefdfec8606394411e19bc24e5141fea61888f1d2690f5375217",
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def http_get(url, accept=None):
    request = urllib.request.Request(url, headers={"User-Agent": "reikai-jp-yomitan-bump"})
    if accept:
        request.add_header("Accept", accept)
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def release_info(tag):
    """The GitHub release of TAG: its publication date and the zip asset's URL and digest."""
    release = json.loads(http_get(f"https://api.github.com/repos/{REPO}/releases/tags/{tag}",
                                  accept="application/vnd.github+json"))
    asset = next((a for a in release.get("assets", []) if a["name"] == ASSET), None)
    if asset is None:
        raise SystemExit(f"release {tag} has no {ASSET}")
    digest = asset.get("digest") or ""
    return {
        "tag": release["tag_name"],
        "published": release.get("published_at"),
        "prerelease": release.get("prerelease", False),
        "url": asset["browser_download_url"],
        "sha256": digest.removeprefix("sha256:") if digest.startswith("sha256:") else None,
    }


def expected_sha256(tag, api_digest, override):
    """The one SHA-256 the zip must have; every source that names one must agree."""
    sources = {name: value for name, value in (
        ("GitHub's asset digest", api_digest),
        ("the pinned value", PINNED.get(tag)),
        ("--sha256", override),
    ) if value}
    if not sources:
        raise SystemExit(f"no SHA-256 known for {tag}: pass --sha256 or add it to PINNED")
    values = set(v.lower() for v in sources.values())
    if len(values) != 1:
        raise SystemExit("SHA-256 sources disagree: " + ", ".join(f"{k} {v}" for k, v in sources.items()))
    return values.pop()


def file_hashes(tree):
    """{relative posix path: sha256} of every file under TREE."""
    return {
        path.relative_to(tree).as_posix(): sha256(path.read_bytes())
        for path in sorted(tree.rglob("*")) if path.is_file()
    }


def extract(zip_bytes, tree):
    """Wipes TREE and unzips the release into it, refusing any entry that would land outside it."""
    if tree.exists():
        shutil.rmtree(tree)
    tree.mkdir(parents=True)
    root = tree.resolve()
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as archive:
        for entry in archive.infolist():
            target = (tree / entry.filename).resolve()
            if root != target and root not in target.parents:
                raise SystemExit(f"zip entry escapes the tree: {entry.filename}")
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))


def vendor(tag, override=None, tree=ASSETS, release_json=RELEASE_JSON):
    info = release_info(tag)
    want = expected_sha256(tag, info["sha256"], override)
    print(f"downloading {info['url']}")
    data = http_get(info["url"])
    got = sha256(data)
    if got != want:
        raise SystemExit(f"{ASSET} of {tag}: SHA-256 {got}, expected {want}")
    extract(data, tree)
    record = {
        "version": tag,
        "published": info["published"],
        "asset": ASSET,
        "url": info["url"],
        "sha256": got,
        "files": file_hashes(tree),
    }
    manifest = json.loads((tree / "manifest.json").read_text())
    if manifest.get("version") != tag:
        raise SystemExit(f"manifest.json says version {manifest.get('version')}, not {tag}")
    release_json.write_text(json.dumps(record, indent=1, ensure_ascii=False) + "\n")
    print(f"vendored Yomitan {tag}: {len(record['files'])} files in {tree.relative_to(ROOT)}")


def verify(tree=ASSETS, release_json=RELEASE_JSON):
    """Problems found (empty when the tree is exactly what yomitan-release.json records)."""
    if not release_json.is_file():
        return [f"{release_json.name} is missing"]
    recorded = json.loads(release_json.read_text())["files"]
    actual = file_hashes(tree) if tree.is_dir() else {}
    problems = []
    for path in sorted(recorded.keys() | actual.keys()):
        if path not in actual:
            problems.append(f"missing: {path}")
        elif path not in recorded:
            problems.append(f"not in the release: {path}")
        elif recorded[path] != actual[path]:
            problems.append(f"changed: {path}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    v = sub.add_parser("vendor")
    v.add_argument("tag")
    v.add_argument("--sha256")
    sub.add_parser("verify")
    args = parser.parse_args()
    if args.command == "vendor":
        vendor(args.tag, args.sha256)
    else:
        problems = verify()
        for line in problems:
            print(line)
        if problems:
            sys.exit(f"{len(problems)} problem(s): the vendored Yomitan does not match {RELEASE_JSON.name}")
        version = json.loads(RELEASE_JSON.read_text())["version"]
        print(f"vendored Yomitan {version} matches {RELEASE_JSON.name}")


if __name__ == "__main__":
    main()
