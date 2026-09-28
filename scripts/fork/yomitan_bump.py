#!/usr/bin/env python3
"""Vendor Yomitan's official release into the jp-yomitan module, check it, and keep it current.

Yomitan's files are never edited by hand (AGENTS.md rule 3): they arrive only through this script,
unzipped from the release's `yomitan-firefox.zip` into jp-yomitan/src/main/assets/yomitan/, with
jp-yomitan/yomitan-release.json recording the version, the zip's URL and SHA-256, and the SHA-256
of every vendored file. The Firefox build is the one whose backend runs as a page (background.html),
which is what an Android WebView can host.

Usage:
  scripts/fork/yomitan_bump.py vendor TAG [--sha256 HEX]
      download TAG's yomitan-firefox.zip, check its SHA-256 against GitHub's asset digest and the
      pinned value (PINNED below, or --sha256), wipe the vendored tree and unzip the release into it
  scripts/fork/yomitan_bump.py verify [--root DIR]
      exit 1 unless the vendored tree matches yomitan-release.json exactly (no file changed,
      missing or added); --root checks another checkout, such as a commit's worktree
  scripts/fork/yomitan_bump.py check [--pinned VERSION] [--soak-days N]
      key=value lines for $GITHUB_OUTPUT: pinned, newest, tag (the release to take, or empty),
      update (true or false), reason. It takes the newest promoted release (no draft or
      pre-release) newer than the vendored one that has been out N days (7), so a release Yomitan
      pulls or fixes quickly never reaches the app.
  scripts/fork/yomitan_bump.py tripwire [--write]
      compare the surfaces of the vendored Yomitan that Reikai JP's stand-in depends on with the
      reviewed baseline (jp-yomitan/yomitan-surface.json); exit 1 listing every change. --write
      regenerates the baseline: an agent does that only after reviewing a change
      (scripts/fork/yomitan-smoke/README.md), never the workflow.
  scripts/fork/yomitan_bump.py smoke
      run the vendored Yomitan with the stand-in in headless Chrome (scripts/fork/yomitan-smoke/)
  scripts/fork/yomitan_bump.py update [TAG] [--pinned VERSION] [--soak-days N] [--no-smoke]
      check (unless TAG is given), then vendor, verify, tripwire and smoke; key=value lines
      updated (true or false) and tag. The weekly workflow fork-yomitan-update.yml runs this.

GITHUB_TOKEN or GH_TOKEN, when set, is sent to GitHub's API only (more generous rate limits).
"""
import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "jp-yomitan"
ASSETS = MODULE / "src/main/assets/yomitan"
RELEASE_JSON = MODULE / "yomitan-release.json"
SURFACE_JSON = MODULE / "yomitan-surface.json"
SMOKE = ROOT / "scripts/fork/yomitan-smoke"

REPO = "yomidevs/yomitan"
ASSET = "yomitan-firefox.zip"
SOAK_DAYS = 7
# Known-good release zips, so a vendor run never trusts the network alone. Add a line per release
# vendored by hand; the automatic update relies on GitHub's asset digest instead.
PINNED = {
    "26.9.8.0": "8c23aa2d61ebeefdfec8606394411e19bc24e5141fea61888f1d2690f5375217",
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def shown(path):
    """PATH relative to the repository when it is inside it, for messages."""
    return path.relative_to(ROOT) if path.is_relative_to(ROOT) else path


def http_get(url, accept=None):
    request = urllib.request.Request(url, headers={"User-Agent": "reikai-jp-yomitan-bump"})
    if accept:
        request.add_header("Accept", accept)
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        # Unredirected: urllib copies ordinary headers to a redirect's target, whatever its host.
        request.add_unredirected_header("Authorization", f"Bearer {token}")
    # Three tries: a passing network hiccup should not wake an agent (D-015).
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.read()
        except (urllib.error.URLError, TimeoutError) as e:
            if attempt == 2 or (isinstance(e, urllib.error.HTTPError) and e.code < 500 and e.code != 429):
                raise
            time.sleep(10 * (attempt + 1))


def github_api(path):
    return json.loads(http_get(f"https://api.github.com/repos/{REPO}/{path}", accept="application/vnd.github+json"))


def release_info(tag):
    """The GitHub release of TAG: its publication date and the zip asset's URL and digest."""
    release = github_api(f"releases/tags/{tag}")
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
    print(f"downloading {info['url']}", file=sys.stderr)
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
    print(f"vendored Yomitan {tag}: {len(record['files'])} files in {shown(tree)}", file=sys.stderr)


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


# --- check: is there a newer promoted release that has soaked? ------------------------------------

def version_key(tag):
    """Yomitan's tags are dotted numbers (26.9.8.0); anything else is not a release we take."""
    if not re.fullmatch(r"\d+(\.\d+)+", tag or ""):
        raise SystemExit(f"unexpected Yomitan release tag {tag!r}")
    return tuple(int(part) for part in tag.split("."))


def published(release):
    return datetime.fromisoformat(release["published_at"].replace("Z", "+00:00"))


def decide(pinned, releases, soak_days=SOAK_DAYS, now=None):
    """(tag to take or None, reason) for the pinned version and RELEASES (GitHub's release list):
    the newest promoted release (neither draft nor pre-release) that is newer than PINNED and has
    been out SOAK_DAYS. Waiting on the newest alone would take nothing while Yomitan releases more
    often than once a week."""
    now = now or datetime.now(timezone.utc)
    promoted = sorted((r for r in releases if not r.get("draft") and not r.get("prerelease") and r.get("published_at")),
                      key=lambda r: version_key(r["tag_name"]), reverse=True)
    newer = [r for r in promoted if version_key(r["tag_name"]) > version_key(pinned)]
    if not newer:
        newest = promoted[0]["tag_name"] if promoted else "none"
        return None, f"Yomitan {pinned} is vendored and {newest} is the newest release"
    for release in newer:
        if now >= published(release) + timedelta(days=soak_days):
            return release["tag_name"], f"Yomitan {release['tag_name']} (out {published(release):%Y-%m-%d}) replaces {pinned}"
    oldest = newer[-1]
    ready = published(oldest) + timedelta(days=soak_days)
    return None, (f"Yomitan {oldest['tag_name']} is newer than {pinned} but younger than {soak_days} days; "
                  f"it is taken from {ready:%Y-%m-%d}")


def check(pinned=None, soak_days=SOAK_DAYS, now=None, release_json=RELEASE_JSON):
    pinned = pinned or json.loads(release_json.read_text())["version"]
    releases = github_api("releases?per_page=30")
    tag, reason = decide(pinned, releases, soak_days, now)
    newest = next((r["tag_name"] for r in releases if not r.get("draft") and not r.get("prerelease")), "")
    return {"pinned": pinned, "newest": newest, "tag": tag or "", "update": tag is not None, "reason": reason}


def print_values(values):
    for key, value in values.items():
        print(f"{key}={str(value).lower() if isinstance(value, bool) else value}")


# --- the static tripwire --------------------------------------------------------------------------
#
# Lists of what the stand-in, the hub and the app rely on in Yomitan's code, read from the vendored
# files, compared with a baseline an agent reviewed (yomitan-surface.json). A release that adds or
# removes anything on these lists stops the automatic update. What it cannot see: a behaviour change
# behind unchanged names, which the smoke test partly covers.

REGEX_KEYWORDS = ("return", "typeof", "case", "in", "of", "delete", "void", "throw", "new", "else", "do", "yield", "await")


def js_tokens(text):
    """Splits JavaScript source into (kind, piece) pairs whose pieces join back into TEXT; kind is
    "comment", "string" (quotes and template literals, whole), "regex" or "code". Whether a `/`
    starts a regular expression or divides is decided, as usual, by the last significant text
    before it (never white space or a comment)."""
    i, n = 0, len(text)
    last = ""  # the last few characters of significant text
    code_from = 0
    # For each open `(`: whether it follows if/while/for/with. After such a `)` a `/` starts a
    # regular expression (`if (x) /re/.test(s)`); after any other `)` it divides.
    parens = []
    control_paren = False
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        kind = None
        if c == "/" and nxt == "/":
            end = text.find("\n", i)
            kind, j = "comment", (n if end < 0 else end)
        elif c == "/" and nxt == "*":
            end = text.find("*/", i + 2)
            kind, j = "comment", (n if end < 0 else end + 2)
        elif c in "'\"`":
            j = i + 1
            while j < n and text[j] != c:
                if text[j] == "\\":
                    j += 1
                elif c != "`" and text[j] == "\n":
                    break
                j += 1
            kind, j = "string", min(j + 1, n)
        elif c == "/":
            word = re.search(r"[\w$]+$", last)
            if (not last or last[-1] in "(,=:[!&|?{};+-*%<>~^" or (word and word.group() in REGEX_KEYWORDS)
                    or (last[-1] == ")" and control_paren)):
                j, in_class = i + 1, False
                while j < n and text[j] != "\n":
                    if text[j] == "\\":
                        j += 2
                        continue
                    if text[j] == "[":
                        in_class = True
                    elif text[j] == "]":
                        in_class = False
                    elif text[j] == "/" and not in_class:
                        break
                    j += 1
                kind, j = "regex", min(j + 1, n)
        if kind is None:
            if not c.isspace():
                if c == "(":
                    parens.append(bool(re.search(r"(?<![\w$.])(?:if|while|for|with)$", last)))
                control_paren = c == ")" and bool(parens) and parens.pop()
                last = (last + c)[-16:]
            i += 1
            continue
        if code_from < i:
            yield "code", text[code_from:i]
        yield kind, text[i:j]
        if kind != "comment":
            last = (last + text[i:j])[-16:]
        i = code_from = j
    if code_from < n:
        yield "code", text[code_from:]


def strip_js_comments(text):
    """TEXT without // and /* */ comments (JSDoc and type casts included), strings and regular
    expressions kept intact; a block comment leaves its line breaks."""
    return "".join(("\n" * piece.count("\n") or " ") if kind == "comment" else piece
                   for kind, piece in js_tokens(text))


def block_after(text, start):
    """The text from START through the brace block that begins at the first `{` after it; braces in
    strings, regular expressions and comments do not count."""
    depth, at = 0, 0
    for kind, piece in js_tokens(text):
        if kind == "code" and at + len(piece) > start:
            for offset in range(max(start - at, 0), len(piece)):
                if piece[offset] == "{":
                    depth += 1
                elif piece[offset] == "}" and depth > 0:
                    depth -= 1
                    if depth == 0:
                        return text[start:at + offset + 1]
        at += len(piece)
    return None


def normalized(code):
    return " ".join(code.split())


def short_hash(code):
    return sha256(normalized(code).encode())[:16]


# Yomitan's lines the stand-in and the hub depend on: workers, the service-worker and SharedWorker
# paths, the database worker handshake, zip.js's worker set-up, the backend's ready signal, every
# network call (the stand-in routes fetch; XHR and others it does not), IndexedDB and storage.
SENSITIVE = re.compile(
    r"new\s+(?:Shared)?Worker\s*\(|serviceWorker|connectToDatabaseWorker|action: 'drawMedia'|\['drawMedia'"
    r"|\bdrawMedia\(|self\.constructor\.name|inExtensionContext\s*=|workerScripts|useWebWorkers|lib/zip\.js"
    r"|applicationBackendReady|importScripts|\bfetch\s*\(|XMLHttpRequest|WebSocket|EventSource|sendBeacon"
    r"|indexedDB|Dexie|navigator\.storage|chrome\.offscreen|\bbrowser\.runtime")

# Functions whose exact code the stand-in relies on, by file and the start of their definition line.
CALL_SITES = {
    "js/application.js": ["static async main("],
    "js/comm/api.js": ["connectToDatabaseWorker(", "_invoke(", "_pmInvoke("],
    "js/background/backend.js": ["_onMessageWrapper(", "_onMessage(", "async _onPmConnectToDatabaseWorker("],
    "js/background/request-builder.js": ["async fetchAnonymous("],
    "js/dictionary/dictionary-worker.js": ["_invoke("],
    "js/dictionary/dictionary-database.js": ["async prepare(", "async drawMedia(", "async connectToDatabaseWorker(",
                                             "_onDrawMedia("],
    # The lookup sheet's popup-host.js swaps lookups through history.state and a popstate event (3.4).
    "js/display/display-history.js": ["constructor(", "_onPopState(", "_updateStateFromHistory("],
    "js/display/display.js": ["async _onStateChanged(", "_setTheme(", "_setNoDictionariesVisible("],
    # The stand-in saves settings exports through the app (blob-download anchors, 3.5).
    "js/pages/settings/backup-controller.js": ["_saveBlob("],
    "js/pages/settings/anki-deck-generator-controller.js": ["_saveBlob("],
    # The Japanese settings open this list for "Get recommended dictionaries" (3.5).
    "js/pages/settings/dictionary-import-controller.js": ["async _renderRecommendedDictionaries("],
}
# Small files the stand-in's worker handling depends on as a whole.
WHOLE_FILES = [
    "js/background/background-main.js",
    "js/comm/shared-worker-bridge.js",
    "js/dictionary/dictionary-database-worker-main.js",
    "js/dictionary/dictionary-worker-main.js",
    "js/display/media-drawing-worker.js",
    "js/extension/web-extension.js",
]
# Paths the app, the stand-in and the fork's worker entries load by name.
ENTRY_FILES = [
    "background.html", "manifest.json", "popup.html", "search.html", "settings.html",
    "js/app/content-script-main.js", "js/app/content-script-wrapper.js",
    "js/dictionary/dictionary-database-worker-main.js", "js/dictionary/dictionary-worker-main.js",
    "js/display/media-drawing-worker.js", "lib/z-worker.js", "lib/zip.js",
]

# Markup the app's hosts find by id or attribute (the lookup sheet 3.4, the settings screen 3.5).
PAGE_ELEMENTS = {
    "popup.html": ['id="dictionary-entries"', 'id="no-results"', 'id="no-dictionaries"', 'id="close-button"',
                   'href="/settings.html#dictionaries"'],
    "search.html": ['id="dictionary-entries"', 'id="no-results"', 'id="no-dictionaries"',
                    'href="/settings.html#dictionaries"'],
    "settings.html": ["<body hidden>", 'data-modal-action="show,recommended-dictionaries"', 'id="dictionaries"',
                      'id="anki"', 'id="backup"', 'id="settings-import-button"'],
    "templates-modals.html": ['id="recommended-dictionaries-modal"'],
    "templates-settings.html": ['data-action="import-recommended-dictionary"'],
}


def js_sources(tree):
    """{relative path: code without comments} of Yomitan's own scripts (js/, not the bundled lib/)."""
    return {path.relative_to(tree).as_posix(): strip_js_comments(path.read_text(encoding="utf-8"))
            for path in sorted((tree / "js").rglob("*.js"))}


def surfaces(tree=ASSETS):
    """{surface name: sorted items} read from the vendored Yomitan at TREE."""
    code = js_sources(tree)
    found = {}

    found["chrome-api"] = sorted({m.group() for text in code.values()
                                  for m in re.finditer(r"(?<![\w$.])chrome(?:\.[A-Za-z_$][\w$]*)+", text)})

    anki = code.get("js/comm/anki-connect.js", "")
    found["anki-connect-actions"] = sorted(set(re.findall(r"\b_invoke\(\s*'(\w+)'", anki)))

    audio = code.get("js/media/audio-downloader.js", "")
    found["audio-sources"] = sorted(set(re.findall(r"\[\s*'([\w-]+)'\s*,\s*this\._getInfo", audio)))

    backend = code.get("js/background/backend.js", "")
    found["backend-actions"] = sorted(
        f"{name}: {action}"
        for name, body in re.findall(r"this\.(_\w+)\s*=\s*createApiMap\(\[(.*?)\]\);", backend, re.S)
        for action in re.findall(r"\[\s*'(\w+)'\s*,", body))

    api = code.get("js/comm/api.js", "")
    found["api-actions"] = sorted(set(re.findall(r"this\._(?:pm)?[iI]nvoke\(\s*'(\w+)'", api)))

    found["manifest"] = manifest_items(tree / "manifest.json")
    found["indexeddb"] = indexeddb_items(code.get("js/dictionary/dictionary-database.js", ""))
    found["licences"] = licence_items(tree / "legal-npm.html")

    lines = {}
    for path, text in code.items():
        for line in text.splitlines():
            if SENSITIVE.search(line):
                key = f"{path}: {normalized(line)}"
                lines[key] = lines.get(key, 0) + 1
    found["sensitive-lines"] = sorted(key if count == 1 else f"{key} (x{count})" for key, count in lines.items())

    sites = []
    for path, starts in CALL_SITES.items():
        text = code.get(path)
        for start in starts:
            match = re.search(r"^[ \t]*" + re.escape(start), text or "", re.M)
            block = block_after(text, match.start()) if match else None
            sites.append(f"{path} {start} {short_hash(block) if block else 'MISSING'}")
    for path in WHOLE_FILES:
        sites.append(f"{path} (whole file) {short_hash(code[path]) if path in code else 'MISSING'}")
    found["call-sites"] = sorted(sites)

    found["entry-files"] = sorted(path for path in ENTRY_FILES if (tree / path).is_file())
    found["page-elements"] = page_elements(tree)
    return found


def page_elements(tree):
    """Each marker of PAGE_ELEMENTS with how often its page has it (MISSING when it is gone)."""
    items = []
    for page, markers in PAGE_ELEMENTS.items():
        path = tree / page
        text = path.read_text(encoding="utf-8") if path.is_file() else ""
        for marker in markers:
            count = text.count(marker)
            items.append(f"{page} {marker} " + (f"x{count}" if count else "MISSING"))
    return sorted(items)


def manifest_items(path):
    if not path.is_file():
        return []
    manifest = json.loads(path.read_text())
    items = [f"manifest_version {manifest.get('manifest_version')}",
             f"background {json.dumps(manifest.get('background'), sort_keys=True)}"]
    for key in ("permissions", "optional_permissions", "host_permissions"):
        items += [f"{key} {value}" for value in manifest.get(key, [])]
    for script in manifest.get("content_scripts", []):
        items += [f"content_scripts js {value}" for value in script.get("js", [])]
        items += [f"content_scripts {key} {json.dumps(script.get(key))}" for key in
                  ("matches", "all_frames", "match_about_blank", "match_origin_as_fallback", "run_at")]
    return sorted(items)


def indexeddb_items(text):
    """The dictionary database's name, the version it opens and each schema version's stores."""
    items = [f"name {name}" for name in re.findall(r"this\._dbName\s*=\s*'([^']+)'", text)]
    items += [f"opens version {v}" for v in re.findall(r"this\._db\.open\(\s*this\._dbName\s*,\s*(\d+)", text)]
    upgrade = re.search(r"const upgrade\s*=(.*?)await this\._db\.open\(", text, re.S)
    for part in re.split(r"\bversion:\s*", upgrade.group(1) if upgrade else "")[1:]:
        version = re.match(r"\d+", part)
        stores = re.findall(r"(\w+):\s*\{\s*primaryKey:\s*\{([^}]*)\},\s*indices:\s*\[([^\]]*)\]", part)
        items += [f"v{version.group() if version else '?'} {store} key {{{normalized(key)}}} indices [{normalized(indices)}]"
                  for store, key, indices in stores]
    return sorted(items)


def licence_items(path):
    """The npm libraries Yomitan bundles and their licences (its legal-npm.html)."""
    if not path.is_file():
        return []
    rows = re.findall(r"<tr><td[^>]*>([^<]*)</td><td[^>]*>[^<]*</td><td[^>]*>([^<]*)</td>", path.read_text())
    return sorted(f"{name}: {licence}" for name, licence in rows)


def tripwire(tree=ASSETS, baseline=SURFACE_JSON):
    """Lines describing every surface change against the baseline (empty when nothing changed)."""
    if not baseline.is_file():
        return [f"{baseline.name} is missing: run `yomitan_bump.py tripwire --write` on a reviewed release"]
    reviewed = json.loads(baseline.read_text())
    before, now = reviewed["surfaces"], surfaces(tree)
    report = []
    for name in sorted(before.keys() | now.keys()):
        old, new = set(before.get(name, [])), set(now.get(name, []))
        if old == new:
            continue
        report.append(f"{name}:")
        report += [f"  - {item}" for item in sorted(old - new)]
        report += [f"  + {item}" for item in sorted(new - old)]
    return report


def write_baseline(tree=ASSETS, baseline=SURFACE_JSON, release_json=RELEASE_JSON):
    version = json.loads(release_json.read_text())["version"]
    record = {
        "comment": "What Reikai JP's stand-in relies on in Yomitan, reviewed by an agent. Written only by "
                   "`scripts/fork/yomitan_bump.py tripwire --write` after a review "
                   "(scripts/fork/yomitan-smoke/README.md); the weekly update only compares.",
        "reviewed": version,
        "surfaces": surfaces(tree),
    }
    baseline.write_text(json.dumps(record, indent=1, ensure_ascii=False) + "\n")
    count = sum(len(items) for items in record["surfaces"].values())
    print(f"wrote {shown(baseline)}: {count} items from Yomitan {version}", file=sys.stderr)


# --- the smoke test and the whole update ----------------------------------------------------------

def smoke():
    """Runs the headless-Chrome smoke test; SystemExit when it fails."""
    if not (SMOKE / "node_modules/playwright-core").is_dir():
        raise SystemExit(f"playwright-core is not installed: run `npm ci --prefix {shown(SMOKE)}`")
    result = subprocess.run(["node", str(SMOKE / "smoke.mjs")], cwd=ROOT)
    if result.returncode != 0:
        raise SystemExit(f"the smoke test failed (exit {result.returncode})")


def update(tag=None, pinned=None, soak_days=SOAK_DAYS, run_smoke=True):
    """Vendors TAG (or the release `check` picks) and proves it; returns the tag, or None when
    there is nothing to update. Leaves the tree changed on failure, for an agent to inspect."""
    if tag is None:
        found = check(pinned, soak_days)
        print(found["reason"], file=sys.stderr)
        if not found["update"]:
            return None
        tag = found["tag"]
    vendor(tag)
    problems = verify()
    if problems:
        raise SystemExit("the vendored tree does not match its record:\n" + "\n".join(problems))
    changes = tripwire()
    if changes:
        raise SystemExit(
            f"tripwire: Yomitan {tag} changed what Reikai JP's stand-in relies on "
            f"(baseline {SURFACE_JSON.name}):\n" + "\n".join(changes)
            + "\nAn agent reviews these (scripts/fork/yomitan-smoke/README.md); nothing was committed.")
    print(f"tripwire: no surface changes in Yomitan {tag}", file=sys.stderr)
    if run_smoke:
        smoke()
    return tag


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    v = sub.add_parser("vendor")
    v.add_argument("tag")
    v.add_argument("--sha256")
    ver = sub.add_parser("verify")
    ver.add_argument("--root", type=Path, default=ROOT, help="check the checkout at ROOT (e.g. a commit's worktree)")
    for name in ("check", "update"):
        p = sub.add_parser(name)
        if name == "update":
            p.add_argument("tag", nargs="?")
            p.add_argument("--no-smoke", action="store_true")
        p.add_argument("--pinned", help="pretend this version is vendored (a dry run of a newer release)")
        p.add_argument("--soak-days", type=int, default=SOAK_DAYS)
    t = sub.add_parser("tripwire")
    t.add_argument("--write", action="store_true", help="regenerate the baseline (agents, after a review)")
    sub.add_parser("smoke")
    args = parser.parse_args()
    if args.command == "vendor":
        vendor(args.tag, args.sha256)
    elif args.command == "verify":
        tree, record = args.root / ASSETS.relative_to(ROOT), args.root / RELEASE_JSON.relative_to(ROOT)
        problems = verify(tree, record)
        for line in problems:
            print(line)
        if problems:
            sys.exit(f"{len(problems)} problem(s): the vendored Yomitan in {shown(args.root)} does not match {RELEASE_JSON.name}")
        version = json.loads(record.read_text())["version"]
        print(f"vendored Yomitan {version} in {shown(args.root)} matches {RELEASE_JSON.name}")
    elif args.command == "check":
        print_values(check(args.pinned, args.soak_days))
    elif args.command == "tripwire":
        if args.write:
            write_baseline()
            return
        changes = tripwire()
        for line in changes:
            print(line)
        if changes:
            sys.exit(f"tripwire: the vendored Yomitan differs from {SURFACE_JSON.name} (see above)")
        count = sum(len(items) for items in surfaces().values())
        print(f"tripwire: {count} items, none changed")
    elif args.command == "smoke":
        smoke()
    else:
        tag = update(args.tag, args.pinned, args.soak_days, not args.no_smoke)
        print_values({"updated": tag is not None, "tag": tag or ""})


if __name__ == "__main__":
    main()
