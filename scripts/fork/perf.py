#!/usr/bin/env python3
"""Performance baseline on the owner's devices: repeatable numbers every later change is compared to.

Measures the `benchmark` build (app.reikai.jp.benchmark: R8 like a release, profileable, its own
data beside the owner's app) on a generated offline library, so runs differ only by the code:
300 local manga with covers and 30 Japanese novels whose chapters are already downloaded. Nothing
touches the network or the owner's own Reikai JP.

Usage:
  scripts/fork/gw :app:assembleBenchmark        build the app first (one Gradle build at a time)
  scripts/fork/perf.py setup [device...] [--fresh]
                                                once per device: install, storage folder, library;
                                                --fresh starts the benchmark app over
  scripts/fork/perf.py run [device...] [--runs N] [--save]
                                                measure, compare with the first (baseline) and last
                                                saved runs; --save appends to docs/fork/perf/results.jsonl
                                                (refused for uncommitted code, a stale APK or lost samples)

Devices are adb serials or the aliases `tablet` and `phone`; the default is every connected one.
Several devices are measured at the same time.
Metrics (ms unless noted; lower is better):
  cold_start           launcher tap to first frame (am start -W), what the owner waits through;
                       it includes Reikai's 500 ms minimum splash
  library_ready        process start to the library list on screen (PerfMarks.libraryReady)
  library_pss_mb       memory (PSS) with the library shown
  library_jank_pct     janky frames while flinging through the library
  chapter_open_first   the first chapter open after a start: the reader's code loads then
  chapter_open         later opens: reader launch to the chapter's text drawn ("Fully drawn");
                       exact for the default native renderer only
  reader_pss_mb        memory with the novel reader open
  reader_jank_pct      janky frames while flinging through another chapter
"""
import argparse
import gzip
import hashlib
import io
import json
import re
import statistics
import struct
import subprocess
import sys
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
PKG = "app.reikai.jp.benchmark"
MAIN = f"{PKG}/eu.kanade.tachiyomi.ui.main.MainActivity"
READER = "eu.kanade.tachiyomi.ui.reader.ReaderActivity"
BENCH_DIR = "ReikaiJPBench"  # on the device's shared storage, beside the owner's Reikai folder
FIXTURE = ROOT / "build/fork-perf/fixture"
RESULTS = ROOT / "docs/fork/perf/results.jsonl"
ALIASES = {"SM-X520": "tablet", "SM-A546E": "phone"}
MANGA_COUNT = 300
NOVEL_COUNT = 30
NOVEL_SOURCE = "reikai.perf.fixture"  # a plugin id no one has: downloads are read without it
NOVEL_CHAPTERS = 5
TARGET_NOVEL = "性能テスト小説 001"
TARGET_CHAPTER = "第1話 始まりの朝"
JANK_CHAPTER = "第2話"
UI_DUMP = "/data/local/tmp/reikai-perf-ui.xml"
# Runs compare only against runs on the same fixture: bump the version whenever build_fixture changes.
FIXTURE_INFO = {"manga": MANGA_COUNT, "novels": NOVEL_COUNT, "version": 1}
# Everything that can change the APK: a commit touching only these exclusions leaves the numbers alone.
CODE_PATHS = [".", ":!docs", ":!scripts", ":!.claude", ":!.github", ":!*.md"]


# ---------------------------------------------------------------- adb

class Device:
    def __init__(self, serial, model):
        self.serial, self.model = serial, model
        self.name = ALIASES.get(model, model)

    def sh(self, cmd, check=False):
        r = subprocess.run(["adb", "-s", self.serial, "shell", cmd], capture_output=True, text=True)
        if check and r.returncode:
            sys.exit(f"{self.name}: `{cmd}` failed: {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout

    def adb(self, *args):
        r = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True)
        if r.returncode:
            sys.exit(f"{self.name}: adb {' '.join(args)} failed: {r.stderr.strip()}")
        return r.stdout

    def nodes(self):
        self.sh(f"uiautomator dump {UI_DUMP} >/dev/null")
        xml = self.sh(f"cat {UI_DUMP}")
        out = []
        for attrs in re.findall(r"<node ([^>]*)>", xml) + re.findall(r"<node ([^>]*)/>", xml):
            text = re.search(r'\btext="([^"]*)"', attrs)
            desc = re.search(r'content-desc="([^"]*)"', attrs)
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', attrs)
            if b:
                label = (text.group(1) if text else "") or (desc.group(1) if desc else "")
                x1, y1, x2, y2 = map(int, b.groups())
                out.append((label.replace("&#10;", "\n").replace("&amp;", "&"), (x1 + x2) // 2, (y1 + y2) // 2))
        return out

    def find(self, label, timeout=10.0, exact=True):
        end = time.monotonic() + timeout
        while True:
            for text, x, y in self.nodes():
                if (text.lower() == label.lower()) if exact else (label.lower() in text.lower()):
                    return x, y
            if time.monotonic() > end:
                return None
            time.sleep(0.5)

    def tap(self, label, timeout=10.0, exact=True):
        at = self.find(label, timeout, exact)
        if not at:
            sys.exit(f"{self.name}: nothing labelled {label!r} on screen "
                     f"(visible: {[t for t, _, _ in self.nodes() if t][:25]})")
        self.sh(f"input tap {at[0]} {at[1]}")

    def size(self):
        w, h = map(int, re.search(r"(\d+)x(\d+)", self.sh("wm size").splitlines()[-1]).groups())
        return w, h

    def fling(self, times, up=True):
        w, h = self.size()
        a, b = (int(h * 0.75), int(h * 0.3)) if up else (int(h * 0.3), int(h * 0.75))
        for _ in range(times):
            self.sh(f"input swipe {w // 2} {a} {w // 2} {b} 120")
            time.sleep(0.6)

    def awake(self):
        self.sh("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")


def devices(wanted):
    out = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True).stdout
    found = []
    for line in out.splitlines()[1:]:
        m = re.match(r"(\S+)\s+device\b.*model:(\S+)", line)
        if m:
            found.append(Device(m.group(1), m.group(2).replace("_", "-")))
    if wanted:
        found = [d for d in found if d.serial in wanted or d.name in wanted]
        missing = set(wanted) - {d.serial for d in found} - {d.name for d in found}
        if missing:
            sys.exit(f"not connected over adb: {', '.join(sorted(missing))}")
    if not found:
        sys.exit("no device connected over adb")
    return found


def apk():
    apks = sorted((ROOT / "app/build/outputs/apk/benchmark").glob("*.apk"))
    pick = [a for a in apks if "arm64-v8a" in a.name] or [a for a in apks if "universal" in a.name] or apks
    if not pick:
        sys.exit("no benchmark APK: run scripts/fork/gw :app:assembleBenchmark first")
    return pick[0]


# ---------------------------------------------------------------- fixture

def varint(n):
    out = bytearray()
    n &= (1 << 64) - 1
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def field(num, value):
    """One protobuf field as kotlinx.serialization writes it (Long/Int/Boolean as varints)."""
    if isinstance(value, bool) or isinstance(value, int):
        return varint(num << 3) + varint(int(value))
    if isinstance(value, float):
        raise TypeError("use f32/f64")
    data = value.encode() if isinstance(value, str) else value
    return varint(num << 3 | 2) + varint(len(data)) + data


def f32(num, v):
    return varint(num << 3 | 5) + struct.pack("<f", v)


def f64(num, v):
    return varint(num << 3 | 1) + struct.pack("<d", v)


def doc_uri(path):
    """A document inside the benchmark storage folder, as the app's own tree grant reaches it."""
    tree = quote(f"primary:{BENCH_DIR}", safe="")
    return f"content://com.android.externalstorage.documents/tree/{tree}/document/" + \
        quote(f"primary:{BENCH_DIR}/{path}", safe="")


def cover(i, size=(300, 430)):
    from PIL import Image, ImageDraw  # host-side only, for the fixture's covers

    img = Image.new("RGB", size, ((i * 53) % 256, (i * 97) % 256, (i * 151) % 256))
    d = ImageDraw.Draw(img)
    for k in range(8):  # detail, so each JPEG decodes like a real cover rather than a flat block
        c = ((i * 31 + k * 40) % 256, (k * 70) % 256, (i * 11 + k * 20) % 256)
        d.ellipse([k * 18, k * 25, size[0] - k * 18, size[1] - k * 25], outline=c, width=6)
    d.text((20, size[1] - 60), f"{i:03d}", fill=(255, 255, 255))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=85)
    return buf.getvalue()


def chapter_html(novel, chapter):
    lines = ["朝の光が窓から差し込み、彼女はゆっくりと目を開けた。", "「今日は何をしようか」と彼は静かに呟いた。",
             "街の外れにある古い図書館には、誰も読んだことのない本が眠っているという。",
             "風が吹き、桜の花びらが舞い散る中、二人は約束の場所へと歩き出した。",
             "その日の夕方、空は茜色に染まり、遠くで鐘の音が鳴り響いていた。"]
    paras = [lines[(novel + chapter + k) % len(lines)] * 2 for k in range(120)]  # ~6,000 characters
    body = "\n".join(f"<p>{p}</p>" for p in paras)
    return f"<html><head><meta charset=\"utf-8\"></head><body>{body}</body></html>"


def build_fixture():
    """Local manga folders, downloaded novel chapters and the backup that puts them in the library."""
    import shutil

    shutil.rmtree(FIXTURE, ignore_errors=True)
    page = cover(0, (800, 1200))
    added = int(datetime(2026, 1, 1, tzinfo=timezone.utc).timestamp() * 1000)
    mangas, novels = [], []
    for i in range(1, MANGA_COUNT + 1):
        name = f"テスト漫画 {i:03d}"
        folder = FIXTURE / "local" / name
        folder.mkdir(parents=True)
        (folder / "cover.jpg").write_bytes(cover(i))
        with zipfile.ZipFile(folder / "第1話.cbz", "w") as z:
            z.writestr("001.jpg", page)
        chapter = field(1, f"{name}/第1話.cbz") + field(2, "第1話") + f32(9, 1.0) + field(10, 0)
        mangas.append(field(1, 0) + field(2, name) + field(3, name) + field(9, doc_uri(f"local/{name}/cover.jpg"))
                      + field(13, added + i) + field(16, chapter) + field(100, True) + field(111, True))
    covers = FIXTURE / "perf-covers"
    covers.mkdir(parents=True)
    for i in range(1, NOVEL_COUNT + 1):
        title = f"性能テスト小説 {i:03d}"
        (covers / f"{i:03d}.jpg").write_bytes(cover(1000 + i))
        chapters = b""
        for c in range(1, NOVEL_CHAPTERS + 1):
            name = TARGET_CHAPTER if c == 1 else f"第{c}話"
            url = f"/novel/{i:03d}/{c}"
            chapters += field(20, field(1, url) + field(2, name) + f64(6, float(c)) + field(7, NOVEL_CHAPTERS - c))
            # NovelDownloadProvider: <source>/<title>/<chapter name>_<md5(url)[:6]>.html
            out = FIXTURE / "novel_downloads" / NOVEL_SOURCE / title / f"{name}_{hashlib.md5(url.encode()).hexdigest()[:6]}.html"
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(chapter_html(i, c), encoding="utf-8")
        novels.append(field(1, NOVEL_SOURCE) + field(2, f"/novel/{i:03d}") + field(3, title)
                      + field(9, doc_uri(f"perf-covers/{i:03d}.jpg")) + field(10, added + i) + field(12, True)
                      + field(19, True) + chapters)
    backup = b"".join(field(1, m) for m in mangas) + b"".join(field(700, n) for n in novels)
    (FIXTURE / "perf-fixture.tachibk").write_bytes(gzip.compress(backup, mtime=0))
    print(f"fixture: {MANGA_COUNT} manga, {NOVEL_COUNT} novels in {FIXTURE.relative_to(ROOT)}")


# ---------------------------------------------------------------- setup

def installed(d):
    return f"package:{PKG}" in d.sh(f"pm list packages {PKG}")


def install(d):
    path = apk()
    print(f"{d.name}: installing {path.name}")
    d.adb("install", "-r", str(path))


def setup(d, fresh=False):
    """Install, point the app at the ReikaiJPBench folder, then restore the fixture into it."""
    if not FIXTURE.exists():
        build_fixture()
    marker = f"/sdcard/{BENCH_DIR}/.perf-setup"
    if not fresh and installed(d) and d.sh(f"cat {marker} 2>/dev/null").strip() == "done":
        print(f"{d.name}: already set up")
        return
    d.awake()
    d.sh(f"pm uninstall {PKG}; rm -f {marker}; mkdir -p /sdcard/{BENCH_DIR}")
    install(d)
    # Notifications carry the restore's "completed" signal; the battery exemption keeps it running.
    d.sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS; dumpsys deviceidle whitelist +{PKG}")
    # The benchmark build skips the Welcome screens (MainActivity), so the folder is set in Settings.
    d.sh(f"am start -W -n {MAIN}")
    d.tap("More")
    d.tap("Data and storage")
    d.tap("Storage location")
    if not d.find(BENCH_DIR, timeout=4):  # the picker opened elsewhere: go to the storage root
        d.tap("Show roots", timeout=3)
        d.tap("Files on", timeout=3, exact=False)
    d.tap(BENCH_DIR)
    d.tap("Use this folder")
    d.tap("Allow")
    if not d.find(f"/{BENCH_DIR}", exact=False):
        sys.exit(f"{d.name}: the storage folder was not set")
    print(f"{d.name}: pushing the fixture")
    for part in ("local", "novel_downloads", "perf-covers", "perf-fixture.tachibk"):
        d.adb("push", str(FIXTURE / part), f"/sdcard/{BENCH_DIR}/")
    d.sh(f"am start -W -a android.intent.action.VIEW -d '{doc_uri('perf-fixture.tachibk')}' -n {MAIN}")
    d.tap("Restore", timeout=15)
    # The restore runs as a background job and posts a "Restore completed" notification when done.
    for _ in range(120):
        time.sleep(2)
        if re.search(rf"pkg={re.escape(PKG)} .*backup_restore_complete", d.sh("dumpsys notification")):
            break
    else:
        sys.exit(f"{d.name}: the restore did not finish in 4 minutes")
    d.sh(f"echo done > {marker}; am force-stop {PKG}")
    print(f"{d.name}: set up")


# ---------------------------------------------------------------- measurements

def logcat_ms(text):
    """'+1s234ms' or '+456ms' as milliseconds."""
    m = re.search(r"\+(?:(\d+)s)?(\d+)ms", text)
    return int(m.group(1) or 0) * 1000 + int(m.group(2)) if m else None


def meminfo_mb(d):
    out = d.sh(f"dumpsys meminfo {PKG}")
    m = re.search(r"TOTAL PSS:\s+([\d,]+)", out) or re.search(r"TOTAL\s+([\d,]+)", out)
    return round(int(m.group(1).replace(",", "")) / 1024, 1) if m else None


def jank(d, flings):
    d.sh(f"dumpsys gfxinfo {PKG} reset")
    d.fling(flings)
    d.fling(flings, up=False)
    out = d.sh(f"dumpsys gfxinfo {PKG}")
    m = re.search(r"Janky frames: \d+ \(([\d.]+)%\)", out)
    return float(m.group(1)) if m else None


def cold_start(d):
    d.sh(f"am force-stop {PKG}")
    time.sleep(1)
    d.sh("logcat -c")
    out = d.sh(f"am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n {MAIN}")
    total = re.search(r"TotalTime: (\d+)", out)
    ready = None
    for _ in range(40):
        m = re.search(r"library_ready (\d+)", d.sh("logcat -d -s ReikaiPerf:I"))
        if m:
            ready = int(m.group(1))
            break
        time.sleep(0.25)
    return (int(total.group(1)) if total else None), ready


def open_chapter(d):
    d.sh("logcat -c")
    d.tap(TARGET_CHAPTER)
    for _ in range(60):
        log = d.sh("logcat -d -s ActivityTaskManager:I")
        line = next((l for l in log.splitlines() if "Fully drawn" in l and READER in l), None)
        if line:
            return logcat_ms(line)
        time.sleep(0.25)
    return None


def summary(values):
    v = sorted(x for x in values if x is not None)
    if not v:
        return None
    p90 = v[min(len(v) - 1, round(0.9 * (len(v) - 1)))]
    out = {"median": round(statistics.median(v), 1), "p90": round(p90, 1), "min": v[0], "max": v[-1], "n": len(v)}
    if len(v) < len(values):
        out["missing"] = len(values) - len(v)  # a mark or a dumpsys line did not come: see report()
    return out


def to_chapters(d):
    d.tap("Novels")
    d.tap(TARGET_NOVEL)
    d.find(TARGET_CHAPTER, timeout=10)


def back_to_chapters(d):
    d.sh("input keyevent KEYCODE_BACK")
    if not d.find(TARGET_CHAPTER, timeout=5):  # the first Back only closed the reader menu
        d.sh("input keyevent KEYCODE_BACK")
        d.find(TARGET_CHAPTER, timeout=5)


def measure(d, runs):
    d.awake()
    # Portrait, the natural orientation of both devices, so a run never measures the other layout.
    rotation = d.sh("settings get system accelerometer_rotation; settings get system user_rotation").split()
    d.sh("settings put system accelerometer_rotation 0; settings put system user_rotation 0")
    try:
        return measure_portrait(d, runs)
    finally:
        d.sh(f"settings put system accelerometer_rotation {rotation[0]}; "
             f"settings put system user_rotation {rotation[1]}")


def measure_portrait(d, runs):
    install(d)
    # A steady compiled state, as on a device a day after an update: walk the measured paths once,
    # have the app write its profile now rather than when ART gets round to it, compile against it.
    cold_start(d)
    to_chapters(d)
    open_chapter(d)
    back_to_chapters(d)
    d.sh(f"am broadcast -a androidx.profileinstaller.action.SAVE_PROFILE "
         f"-n {PKG}/androidx.profileinstaller.ProfileInstallReceiver")
    time.sleep(3)
    d.sh("input keyevent KEYCODE_BACK")
    d.tap("Manga")  # the chip every cold start opens on
    time.sleep(1)
    d.sh(f"am force-stop {PKG}")
    d.sh(f"cmd package compile -f -m speed-profile {PKG}", check=True)
    starts, readies, pss = [], [], []
    for i in range(runs):
        total, ready = cold_start(d)
        starts.append(total)
        readies.append(ready)
        time.sleep(3)  # covers decoded and caches settled
        pss.append(meminfo_mb(d))
        print(f"{d.name}: cold start {i + 1}/{runs}: {total} ms, library ready {ready} ms, {pss[-1]} MB")
    metrics = {"cold_start": summary(starts), "library_ready": summary(readies), "library_pss_mb": summary(pss),
               "library_jank_pct": summary([jank(d, 6) for _ in range(3)])}
    to_chapters(d)
    # The first open after a start loads the reader's code; the owner meets it once per session.
    opens, reader_pss = [], []
    for i in range(max(4, runs // 2) + 1):
        opens.append(open_chapter(d))
        time.sleep(3)
        reader_pss.append(meminfo_mb(d))
        print(f"{d.name}: chapter open {i + 1}: {opens[-1]} ms, {reader_pss[-1]} MB")
        back_to_chapters(d)
    metrics["chapter_open_first"] = summary(opens[:1])
    metrics["chapter_open"] = summary(opens[1:])
    metrics["reader_pss_mb"] = summary(reader_pss)
    # Flinging saves a reading position, so it happens in another chapter: the timed one must open
    # at its start on every run.
    d.tap(JANK_CHAPTER)
    time.sleep(3)
    metrics["reader_jank_pct"] = summary([jank(d, 6) for _ in range(3)])
    back_to_chapters(d)
    d.sh("input keyevent KEYCODE_BACK")
    d.tap("Manga")
    d.sh(f"am force-stop {PKG}")
    version = re.search(r"versionName=(\S+)", d.sh(f"dumpsys package {PKG}"))
    return {
        "date": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "commit": git("rev-parse", "--short=9", "HEAD"),
        "dirty": bool(git("status", "--porcelain", "--", *CODE_PATHS)),
        "stale_apk": stale_apk(),
        "device": d.name, "model": d.model,
        "android": d.sh("getprop ro.build.version.release").strip(),
        "build": version.group(1) if version else None,
        "fixture": FIXTURE_INFO,
        "metrics": metrics,
    }


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, cwd=ROOT).stdout.strip()


def stale_apk():
    """The APK is older than the files of the last commit that could change it (edited or pulled)."""
    files = [ROOT / f for f in git("log", "-1", "--name-only", "--format=", "--", *CODE_PATHS).splitlines()]
    newest = max((f.stat().st_mtime for f in files if f.exists()), default=0)
    return apk().stat().st_mtime < newest


def unsaveable(result):
    """Why a run must not become a reference point, or an empty list."""
    why = []
    if result["dirty"]:
        why.append("the app's code has uncommitted changes")
    if result["stale_apk"]:
        why.append("the APK is older than the last code commit (run scripts/fork/gw :app:assembleBenchmark)")
    for name, s in result["metrics"].items():
        if not s or s.get("missing"):
            why.append(f"{name} lost samples")
    return why


def saved(device):
    """This device's saved runs, oldest first: the first is the baseline, the last the newest reference."""
    if not RESULTS.exists():
        return []
    rows = [json.loads(line) for line in RESULTS.read_text().splitlines() if line.strip()]
    return [r for r in rows if r["device"] == device and r.get("fixture") == FIXTURE_INFO]


def report(result, history):
    print(f"\n{result['device']} ({result['model']}, Android {result['android']}, {result['build']}, "
          f"commit {result['commit']}{' + local changes' if result['dirty'] else ''})")
    # Against the first save too, so regressions each too small to see cannot add up unnoticed.
    refs = [("baseline", history[0])] if history else []
    if len(history) > 1:
        refs.append(("last save", history[-1]))
    head = "".join(f"   vs {label} {r['commit']} ({r['date'][:10]})" for label, r in refs) or "   nothing saved yet"
    print(f"  {'metric':<18}{'median':>9}{'p90':>9}{head}")
    for name, s in result["metrics"].items():
        if not s:
            print(f"  {name:<18}{'-':>9}")
            continue
        deltas = ""
        for _, ref in refs:
            was = ref.get("metrics", {}).get(name)
            if was and was["median"]:
                change = (s["median"] - was["median"]) / was["median"] * 100
                deltas += f"   {was['median']:>9} -> {change:+4.0f}%"
        lost = f"   ({s['missing']} samples lost)" if s.get("missing") else ""
        print(f"  {name:<18}{s['median']:>9}{s['p90']:>9}{deltas}{lost}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["fixture", "setup", "run"])
    p.add_argument("devices", nargs="*")
    p.add_argument("--runs", type=int, default=10, help="cold starts per device (chapter opens: half)")
    p.add_argument("--fresh", action="store_true", help="setup: start the benchmark app over with a new library")
    p.add_argument("--save", action="store_true", help="append the results to docs/fork/perf/results.jsonl")
    a = p.parse_args()
    if a.command == "fixture":
        build_fixture()
        return
    found = devices(a.devices)
    if a.command == "setup" and not FIXTURE.exists():
        build_fixture()  # before the threads, which would otherwise race to build it
    if a.command == "run":
        for d in found:
            if not installed(d):
                sys.exit(f"{d.name}: not set up; run scripts/fork/perf.py setup {d.name}")
    # The devices share nothing but the USB bus, and every measurement is taken on the device, so they
    # run side by side: a two-device run takes as long as the slower device.
    work = (lambda d: setup(d, a.fresh)) if a.command == "setup" else (lambda d: measure(d, a.runs))
    with ThreadPoolExecutor(len(found)) as pool:
        futures = {d: pool.submit(work, d) for d in found}
    failed = False
    for d, future in futures.items():
        try:
            result = future.result()
        except (SystemExit, Exception) as e:  # one device failing leaves the other's numbers standing
            print(f"{d.name}: failed: {e}")
            failed = True
            continue
        if a.command == "setup":
            continue
        report(result, saved(d.name))
        if a.save and (why := unsaveable(result)):
            print(f"  not saved: {'; '.join(why)}")
        elif a.save:
            RESULTS.parent.mkdir(parents=True, exist_ok=True)
            with RESULTS.open("a") as f:
                f.write(json.dumps(result, ensure_ascii=False) + "\n")
            print(f"  saved to {RESULTS.relative_to(ROOT)}")
    if failed:
        sys.exit(1)

if __name__ == "__main__":
    main()
