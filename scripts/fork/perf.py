#!/usr/bin/env python3
"""Performance baseline on the owner's devices: repeatable numbers every later change is compared to.

Measures the `benchmark` build (app.reikai.jp.benchmark: R8 like a release, profileable, its own
data beside the owner's app) on a generated offline library, so runs differ only by the code:
300 local manga with covers and 30 Japanese novels whose chapters are already downloaded. Nothing
touches the network or the owner's own Reikai JP.

Usage:
  scripts/fork/gw :app:assembleBenchmark        build the app first (one Gradle build at a time)
  scripts/fork/perf.py setup [device...]        once per device: install, storage folder, library
  scripts/fork/perf.py run [device...] [--runs N] [--save]
                                                measure, compare with the last saved run; --save
                                                appends to docs/fork/perf/results.jsonl

Devices are adb serials or the aliases `tablet` and `phone`; the default is every connected one.
Metrics (ms unless noted; lower is better):
  cold_start           launcher tap to first frame (am start -W), what the owner waits through;
                       it includes Reikai's 500 ms minimum splash
  library_ready        process start to the library having its data (PerfMarks.libraryReady)
  library_pss_mb       memory (PSS) with the library shown
  library_jank_pct     janky frames while flinging through the library
  chapter_open         tap on a downloaded novel chapter to its text drawn ("Fully drawn")
  reader_pss_mb        memory with the novel reader open
  reader_jank_pct      janky frames while flinging through the chapter
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
UI_DUMP = "/data/local/tmp/reikai-perf-ui.xml"


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


def setup(d):
    """Install, point the app at the ReikaiJPBench folder, then restore the fixture into it."""
    if not FIXTURE.exists():
        build_fixture()
    marker = f"/sdcard/{BENCH_DIR}/.perf-setup"
    if installed(d) and d.sh(f"cat {marker} 2>/dev/null").strip() == "done":
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
    return {"median": round(statistics.median(v), 1), "p90": round(p90, 1), "min": v[0], "max": v[-1], "n": len(v)}


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
    # A steady compiled state, as on a phone a day after an update: run once so the profile is
    # recorded, then compile against it. The Manga chip is the one every cold start opens on.
    cold_start(d)
    d.tap("Manga")
    time.sleep(5)
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
    d.tap("Novels")
    d.tap(TARGET_NOVEL)
    d.find(TARGET_CHAPTER, timeout=10)
    opens, reader_pss = [], []
    for i in range(max(3, runs // 2)):
        ms = open_chapter(d)
        opens.append(ms)
        time.sleep(3)
        reader_pss.append(meminfo_mb(d))
        print(f"{d.name}: chapter open {i + 1}: {ms} ms, {reader_pss[-1]} MB")
        if i == 0:
            metrics["reader_jank_pct"] = summary([jank(d, 6) for _ in range(3)])
        d.sh("input keyevent KEYCODE_BACK")
        if not d.find(TARGET_CHAPTER, timeout=5):  # the first Back only closed the reader menu
            d.sh("input keyevent KEYCODE_BACK")
            d.find(TARGET_CHAPTER, timeout=5)
    metrics["chapter_open"] = summary(opens)
    metrics["reader_pss_mb"] = summary(reader_pss)
    d.sh("input keyevent KEYCODE_BACK")
    d.tap("Manga")
    d.sh(f"am force-stop {PKG}")
    version = re.search(r"versionName=(\S+)", d.sh(f"dumpsys package {PKG}"))
    return {
        "date": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "commit": subprocess.run(["git", "rev-parse", "--short=9", "HEAD"], capture_output=True, text=True,
                                 cwd=ROOT).stdout.strip(),
        "dirty": bool(subprocess.run(["git", "status", "--porcelain", "--", "app", "core", "data", "domain"],
                                     capture_output=True, text=True, cwd=ROOT).stdout.strip()),
        "device": d.name, "model": d.model,
        "android": d.sh("getprop ro.build.version.release").strip(),
        "build": version.group(1) if version else None,
        "fixture": {"manga": MANGA_COUNT, "novels": NOVEL_COUNT},
        "metrics": metrics,
    }


def last_saved(device):
    if not RESULTS.exists():
        return None
    rows = [json.loads(l) for l in RESULTS.read_text().splitlines() if l.strip()]
    rows = [r for r in rows if r["device"] == device]
    return rows[-1] if rows else None


def report(result, before):
    print(f"\n{result['device']} ({result['model']}, Android {result['android']}, {result['build']}, "
          f"commit {result['commit']}{' + local changes' if result['dirty'] else ''})")
    head = f"vs {before['commit']} ({before['date'][:10]})" if before else "no saved run to compare"
    print(f"  {'metric':<18}{'median':>9}{'p90':>9}   {head}")
    for name, s in result["metrics"].items():
        if not s:
            print(f"  {name:<18}{'-':>9}")
            continue
        was = (before or {}).get("metrics", {}).get(name)
        delta = ""
        if was:
            change = (s["median"] - was["median"]) / was["median"] * 100 if was["median"] else 0
            delta = f"{was['median']:>9} -> {change:+.0f}%"
        print(f"  {name:<18}{s['median']:>9}{s['p90']:>9}   {delta}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["fixture", "setup", "run"])
    p.add_argument("devices", nargs="*")
    p.add_argument("--runs", type=int, default=10, help="cold starts per device (chapter opens: half)")
    p.add_argument("--save", action="store_true", help="append the results to docs/fork/perf/results.jsonl")
    a = p.parse_args()
    if a.command == "fixture":
        build_fixture()
        return
    for d in devices(a.devices):
        if a.command == "setup":
            setup(d)
            continue
        if not installed(d):
            sys.exit(f"{d.name}: not set up; run scripts/fork/perf.py setup {d.name}")
        result = measure(d, a.runs)
        report(result, last_saved(d.name))
        if a.save:
            RESULTS.parent.mkdir(parents=True, exist_ok=True)
            with RESULTS.open("a") as f:
                f.write(json.dumps(result, ensure_ascii=False) + "\n")
            print(f"  saved to {RESULTS.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
