#!/usr/bin/env python3
"""Roadmap 2.1, the Yomitan spike: drive the debug app's YomitanSpikeActivity over adb.

Yomitan's official release runs unmodified in the app's WebViews; this script fetches it (pinned by
version and SHA-256), fetches the test dictionaries, pushes both to the app's external files, starts
the spike screen and prints its RESULT lines from logcat. Nothing here is shipped: dictionaries are
never bundled (AGENTS.md rule 4), they are only copied to a test device.

Usage:
  scripts/fork/gw :app:installDebug                  build and install app.reikai.jp.dev first
  scripts/fork/yomitan_spike.py fetch                download Yomitan and the dictionaries once
  scripts/fork/yomitan_spike.py push DEVICE [--js]   copy them to the device (--js: spike pages only)
  scripts/fork/yomitan_spike.py start DEVICE         (re)start the spike screen, wait for the engine
                                                     (run it before `cmd`: a command sent while the
                                                     screen is still starting is lost)
  scripts/fork/yomitan_spike.py cmd DEVICE JSON [--timeout S]
                                                     e.g. '{"do":"import","files":["jitendex-yomitan.zip"]}',
                                                     '{"do":"bench","count":200}', '{"do":"info"}'
  scripts/fork/yomitan_spike.py mem DEVICE           memory of the app and its WebView renderer
  scripts/fork/yomitan_spike.py tap DEVICE WORD... [--rounds N]
                                                     after '{"do":"reader"}': tap each word in the
                                                     vertical text, time tap to Yomitan's popup drawn
  scripts/fork/yomitan_spike.py shot DEVICE FILE     screenshot to FILE (PNG)
  scripts/fork/yomitan_spike.py clean DEVICE         remove what the spike put on the device: pushed
                                                     files, imported dictionaries, the Anki permission

Other commands (through `cmd`): '{"do":"lookup","text":"食べられなかった"}'; '{"do":"probe"}' (does
a worker started by a worker load? finding 5 of the research note).

Adding a card (AnkiDroid installed; creates the note type "Lapis" in the collection if missing):
  adb -s SERIAL shell pm grant app.reikai.jp.dev com.ichi2.anki.permission.READ_WRITE_DATABASE
  cmd DEVICE '{"do":"anki-setup","deck":"Reikai JP test"}'    the deck and Lapis
  cmd DEVICE '{"do":"anki-options","deck":"Reikai JP test"}'  Yomitan's card format for Lapis
  cmd DEVICE '{"do":"reader"}'; tap DEVICE 喫茶店 --keep-open  prints RESULT popup_buttons {"addNote":[x,y]}
  adb -s SERIAL shell input tap X Y                           Yomitan's own add button
  cmd DEVICE '{"do":"anki-find","query":"deck:\\"Reikai JP test\\""}'  the note as AnkiDroid stored it
  cmd DEVICE '{"do":"anki-cleanup","deck":"Reikai JP test","noteIds":[ID]}'  deletes the note; the
                                                     provider cannot delete decks, so remove the empty
                                                     deck in AnkiDroid (long-press it, Delete deck)

DEVICE is an adb serial or `tablet` / `phone`.
"""
import argparse
import hashlib
import json
import queue
import re
import subprocess
import sys
import threading
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "build/yomitan-spike"
PKG = "app.reikai.jp.dev"
ACTIVITY = f"{PKG}/jp.reikai.yomitan.spike.YomitanSpikeActivity"
REMOTE = f"/sdcard/Android/data/{PKG}/files/yomitan-spike"
PAGES = ROOT / "app/src/debug/assets/jp-reikai/yomitan-spike"
TAG = "YomitanSpike"
ALIASES = {"tablet": "SM-X520", "phone": "SM-A546E"}

YOMITAN = {
    "version": "26.9.8.0",
    "url": "https://github.com/yomidevs/yomitan/releases/download/26.9.8.0/yomitan-firefox.zip",
    "sha256": "8c23aa2d61ebeefdfec8606394411e19bc24e5141fea61888f1d2690f5375217",
}
# Free dictionaries Yomitan itself recommends (JMdict-based Jitendex, JPDB frequency) plus Kanjium
# pitch accents; hashes are of the files the spike measured on 2026-09-27.
DICTIONARIES = {
    "jitendex-yomitan.zip": ("https://github.com/stephenmk/stephenmk.github.io/releases/download/2026.08.11.0/jitendex-yomitan.zip",
                             "8364e69e7bd0881c42011e96af921a7399d7fe06e2bf4fff4da6d18affff74fc"),
    "JPDB_v2.2_Frequency_Kana.zip": ("https://github.com/Kuuuube/yomitan-dictionaries/releases/download/yomitan-permalink/JPDB_v2.2_Frequency_Kana.zip",
                                     "4fa06c784155ea0ea0953740b99d421296c775ee0d035cfe1ff65a40d7d3e685"),
    "kanjium_pitch_accents.zip": ("https://github.com/FooSoft/yomichan/raw/dictionaries/kanjium_pitch_accents.zip",
                                  "90d05ad6efc6f44a495bcc01db6b9d3a0f2f1c42ba38adcbefda0ddfaec8b8c2"),
}

# The Lapis note type's templates (GPL-3.0, github.com/donkuri/lapis), added to AnkiDroid for the test card.
LAPIS = {name: (f"https://raw.githubusercontent.com/donkuri/lapis/1.7.0/src/{name}", sha) for name, sha in {
    "front.html": "7bb9993df961d35e7f49b3edec9f7b0f43987f114f3cfdc627a10219c62aef49",
    "back.html": "1d92182a7626b8a14fd80bc82ee07e71a7a387533be3a4124d25fe8b3090a137",
    "styling.css": "51590e7545d43896cb15e494db49b611a9c6e7bbf05c8e0debbf1f98f7ae8920",
}.items()}


def download(url, dest, sha256):
    if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest() == sha256:
        return
    print(f"downloading {url}")
    with urllib.request.urlopen(url) as r:
        data = r.read()
    got = hashlib.sha256(data).hexdigest()
    if got != sha256:
        sys.exit(f"{dest.name}: SHA-256 {got} is not the pinned {sha256}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)


def fetch(_args):
    zip_path = WORK / f"yomitan-firefox-{YOMITAN['version']}.zip"
    download(YOMITAN["url"], zip_path, YOMITAN["sha256"])
    ext = WORK / "ext"
    if not (ext / "manifest.json").exists():
        with zipfile.ZipFile(zip_path) as z:
            z.extractall(ext)
    for name, (url, sha) in DICTIONARIES.items():
        download(url, WORK / "dicts" / name, sha)
    for name, (url, sha) in LAPIS.items():
        download(url, WORK / "lapis" / name, sha)
    print(f"ready in {WORK.relative_to(ROOT)}")


def serial_of(device):
    out = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True).stdout
    model = ALIASES.get(device, device).replace("-", "_")
    for line in out.splitlines()[1:]:
        if f"model:{model}" in line or line.startswith(device + " "):
            return line.split()[0]
    sys.exit(f"{device} is not connected over adb")


def adb(serial, *args, check=True):
    r = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True)
    if check and r.returncode:
        sys.exit(f"adb {' '.join(args)}: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout


def push(args):
    serial = serial_of(args.device)
    adb(serial, "shell", f"mkdir -p {REMOTE}")
    adb(serial, "push", f"{PAGES}/.", f"{REMOTE}/reikai/")
    if not args.js:
        if not (WORK / "ext/manifest.json").exists():
            sys.exit("run `fetch` first")
        adb(serial, "push", f"{WORK}/ext/.", f"{REMOTE}/ext/")
        adb(serial, "push", f"{WORK}/dicts/.", f"{REMOTE}/dicts/")
        adb(serial, "push", f"{WORK}/lapis/.", f"{REMOTE}/lapis/")
    print(adb(serial, "shell", f"du -sh {REMOTE}/*").strip())


def follow(serial, until, timeout, quiet=False, fatal=True):
    """Prints the spike's RESULT/MARK/tripwire/error lines until one matches `until`; returns them."""
    proc = subprocess.Popen(["adb", "-s", serial, "logcat", "-v", "brief", "-s", f"{TAG}:*"],
                            stdout=subprocess.PIPE, text=True)
    end = time.monotonic() + timeout
    matched = False
    seen = []
    lines = queue.Queue()
    threading.Thread(target=lambda: [lines.put(l) for l in proc.stdout], daemon=True).start()
    try:
        while time.monotonic() < end:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                continue
            text = line.split(": ", 1)[-1].rstrip()
            if re.match(r"(RESULT|MARK|tripwire)", text) or line.startswith(("E/", "W/")):
                seen.append(text)
                if not quiet:
                    print(text, flush=True)
            if re.search(until, text):
                matched = True
                break
    finally:
        proc.kill()
    if not matched and fatal:
        sys.exit(f"timed out after {timeout}s waiting for /{until}/")
    return seen


def result_of(lines, name):
    for text in reversed(lines):
        if text.startswith(f"RESULT {name} "):
            return json.loads(text.split(" ", 2)[2])
    return None


def start(args):
    serial = serial_of(args.device)
    adb(serial, "shell", f"am force-stop {PKG}")
    adb(serial, "logcat", "-c")
    adb(serial, "shell", f"am start -n {ACTIVITY}")
    follow(serial, r"^RESULT (storage|error) ", args.timeout)


def cmd(args):
    serial = serial_of(args.device)
    json.loads(args.json)
    adb(serial, "logcat", "-c")
    quoted = args.json.replace("'", "'\\''")
    adb(serial, "shell", f"am start -n {ACTIVITY} --es cmd '{quoted}'")
    follow(serial, r"^RESULT (done|error) ", args.timeout)


def tap(args):
    serial = serial_of(args.device)
    log = adb(serial, "logcat", "-d", "-v", "brief", "-s", f"{TAG}:*")
    targets = result_of([l.split(": ", 1)[-1] for l in log.splitlines()], "targets")
    saved = WORK / f"targets-{serial}.json"
    if targets:
        saved.write_text(json.dumps(targets))
        time.sleep(3)  # the reader just opened: let Yomitan's content script finish starting
    elif saved.exists():
        targets = json.loads(saved.read_text())
    else:
        sys.exit("no tap targets: run cmd DEVICE '{\"do\":\"reader\"}' first")
    width, height = map(int, re.search(r"(\d+)x(\d+)", adb(serial, "shell", "wm size").splitlines()[-1]).groups())
    times = []
    for _ in range(args.rounds):
        for word in args.words:
            # The reader reports new places when it scrolls; the newest report wins.
            fresh = result_of([l.split(": ", 1)[-1] for l in adb(serial, "logcat", "-d", "-v", "brief", "-s", f"{TAG}:*").splitlines()], "targets")
            if fresh:
                targets = fresh
            if word not in targets:
                print(f"{word}: not on the page")
                continue
            x, y = targets[word]
            if not (0 < x < width and 0 < y < height):
                print(f"{word}: off screen now")
                continue
            adb(serial, "logcat", "-c")
            adb(serial, "shell", f"input tap {x} {y}")
            lines = follow(serial, r"^RESULT popup_shown ", 15, quiet=True, fatal=False)
            shown, up = result_of(lines, "popup_shown"), result_of(lines, "tap_up")
            if not shown or not up:
                print(f"{word}: no popup within 15 s (missed)")
                continue
            ms = round(shown["at"] - up["at"], 1)
            times.append(ms)
            print(f"{word}: tap to popup {ms} ms ({shown['entries']} entries)")
            buttons = result_of(lines, "popup_buttons")
            if buttons:
                print(f"RESULT popup_buttons {json.dumps(buttons)}")
            if not args.keep_open:
                # A tap on blank paper (the reader's text starts at the right edge) closes the popup,
                # so the next word is not hidden under it.
                adb(serial, "shell", f"input tap {args.blank[0]} {args.blank[1]}")
            time.sleep(0.6)
    times.sort()
    if len(times) > 1:
        pct = lambda p: times[min(len(times) - 1, int(p * len(times)))]
        print(f"RESULT tap_to_popup {json.dumps({'n': len(times), 'p50': pct(0.5), 'p95': pct(0.95), 'max': times[-1]})}")


def shot(args):
    serial = serial_of(args.device)
    png = subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"], capture_output=True).stdout
    Path(args.file).write_bytes(png)
    print(f"saved {args.file}")


def pss_mb(serial, target):
    m = re.search(r"TOTAL PSS:\s+([\d,]+)", adb(serial, "shell", f"dumpsys meminfo {target}"))
    return int(m.group(1).replace(",", "")) // 1024 if m else None


def clean(args):
    serial = serial_of(args.device)
    adb(serial, "shell", f"am force-stop {PKG}")
    adb(serial, "shell", f"rm -rf {REMOTE}")
    # Yomitan's database is the spike origin's IndexedDB in the debug app's WebView data (run-as works
    # on debug builds). Its settings (a few KB of localStorage) share a file with other sites'
    # storage, so they stay.
    adb(serial, "shell", f"run-as {PKG} sh -c 'rm -rf app_webview/Default/IndexedDB/https_appassets.androidplatform.net_0.*'", check=False)
    adb(serial, "shell", f"pm revoke {PKG} com.ichi2.anki.permission.READ_WRITE_DATABASE", check=False)
    left = adb(serial, "shell", f"run-as {PKG} du -sh app_webview/Default/IndexedDB", check=False).strip()
    print(f"cleaned; the debug app's IndexedDB now holds {left.split()[0] if left else 'nothing'}")


def mem(args):
    serial = serial_of(args.device)
    app_pid = adb(serial, "shell", f"pidof {PKG}", check=False).strip()
    if not app_pid:
        sys.exit("the app is not running")
    print(f"app PSS {pss_mb(serial, app_pid)} MB")
    # The WebView renderer is an isolated process bound as a service of the app: Android lists it
    # under the app's services, which tells it apart from other apps' renderers.
    services = adb(serial, "shell", f"dumpsys activity services {PKG}")
    pids = sorted(set(re.findall(r"ProcessRecord\{\w+ (\d+):com\.google\.android\.webview:sandboxed_process", services)))
    for pid in pids:
        print(f"renderer (pid {pid}) PSS {pss_mb(serial, pid)} MB")
    if not pids:
        print("no WebView renderer bound to the app")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("fetch").set_defaults(fn=fetch)
    s = sub.add_parser("push"); s.add_argument("device"); s.add_argument("--js", action="store_true"); s.set_defaults(fn=push)
    s = sub.add_parser("start"); s.add_argument("device"); s.add_argument("--timeout", type=int, default=90); s.set_defaults(fn=start)
    s = sub.add_parser("cmd"); s.add_argument("device"); s.add_argument("json"); s.add_argument("--timeout", type=int, default=900); s.set_defaults(fn=cmd)
    s = sub.add_parser("mem"); s.add_argument("device"); s.set_defaults(fn=mem)
    s = sub.add_parser("tap"); s.add_argument("device"); s.add_argument("words", nargs="+"); s.add_argument("--rounds", type=int, default=1)
    s.add_argument("--keep-open", action="store_true", help="leave the last popup open (to tap its buttons)")
    s.add_argument("--blank", type=int, nargs=2, default=[150, 700], help="a screen point with no text"); s.set_defaults(fn=tap)
    s = sub.add_parser("shot"); s.add_argument("device"); s.add_argument("file"); s.set_defaults(fn=shot)
    s = sub.add_parser("clean"); s.add_argument("device"); s.set_defaults(fn=clean)
    args = p.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
