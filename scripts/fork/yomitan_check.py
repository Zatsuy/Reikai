#!/usr/bin/env python3
"""Drive the debug app's Yomitan engine check (EngineCheckActivity) over adb.

The check screen holds the production engine (jp-yomitan) and runs commands sent as intent extras,
printing RESULT lines to logcat (tag YomitanCheck; the engine itself logs under Yomitan). Test data
is only ever copied to a test device, never bundled (AGENTS.md rule 4).

Usage:
  scripts/fork/gw :app:installDebug                   build and install app.reikai.jp.dev first
  scripts/fork/yomitan_check.py testdict              zip Yomitan's GPL test dictionary (refs/yomitan)
  scripts/fork/yomitan_check.py fetch                 download Jitendex (pinned SHA-256)
  scripts/fork/yomitan_check.py push DEVICE FILE...   copy test files to the device; the check serves
                                                      them at https://yomitan.reikai.invalid/__reikai/check/<name>
  scripts/fork/yomitan_check.py start DEVICE          (re)start the check screen, wait for the engine
  scripts/fork/yomitan_check.py cmd DEVICE JSON [--timeout S]
      '{"do":"state"}'  '{"do":"lookup","text":"食べられなかった"}'  '{"do":"bench","count":200}'
      '{"do":"settings"}' then '{"do":"import","url":"https://yomitan.reikai.invalid/__reikai/check/test-dictionary.zip"}'
      '{"do":"search","query":"画像"}'  '{"do":"purge"}' (from settings)  '{"do":"close"}'
      '{"do":"release"}' / '{"do":"acquire"}'  '{"do":"api","action":"getDictionaryInfo"}'
      '{"do":"switch","on":false}' (the lookup switch)  '{"do":"crash"}' (kill WebView's renderer)
  scripts/fork/yomitan_check.py mem DEVICE            PSS of the app and of its WebView renderer
  scripts/fork/yomitan_check.py shot DEVICE FILE      screenshot to FILE (PNG)
  scripts/fork/yomitan_check.py clean DEVICE          remove the pushed test files

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
WORK = ROOT / "build/yomitan-check"
PKG = "app.reikai.jp.dev"
ACTIVITY = f"{PKG}/jp.reikai.yomitan.check.EngineCheckActivity"
REMOTE = f"/sdcard/Android/data/{PKG}/files/yomitan-check"
TAGS = ["YomitanCheck:*", "Yomitan:*"]
ALIASES = {"tablet": "SM-X520", "phone": "SM-A546E"}
TEST_DICTIONARY = ROOT.parent / "refs/yomitan/test/data/dictionaries/valid-dictionary1"

# Jitendex (JMdict-based, CC BY-SA), the dictionary the spike measured; hash of the 2026-08-11 file.
JITENDEX = ("https://github.com/stephenmk/stephenmk.github.io/releases/download/2026.08.11.0/jitendex-yomitan.zip",
            "8364e69e7bd0881c42011e96af921a7399d7fe06e2bf4fff4da6d18affff74fc")


def testdict(_args):
    """Yomitan's own test dictionary (GPL-3.0, part of Yomitan's repository), zipped."""
    out = WORK / "test-dictionary.zip"
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(TEST_DICTIONARY.iterdir()):
            z.write(f, f.name)
    print(f"wrote {out.relative_to(ROOT)} from {TEST_DICTIONARY}")


def fetch(_args):
    url, sha = JITENDEX
    dest = WORK / "jitendex-yomitan.zip"
    if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest() == sha:
        print(f"{dest.relative_to(ROOT)} already there")
        return
    print(f"downloading {url}")
    with urllib.request.urlopen(url) as r:
        data = r.read()
    if hashlib.sha256(data).hexdigest() != sha:
        sys.exit("Jitendex: SHA-256 is not the pinned one")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)
    print(f"saved {dest.relative_to(ROOT)}")


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
    for f in args.files:
        adb(serial, "push", f, f"{REMOTE}/")
    print(adb(serial, "shell", f"ls -l {REMOTE}").strip())


def follow(serial, until, timeout, fatal=True):
    """Prints RESULT, MARK, tripwire and error lines until one matches `until`; returns them."""
    proc = subprocess.Popen(["adb", "-s", serial, "logcat", "-v", "brief", "-s", *TAGS], stdout=subprocess.PIPE, text=True)
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
            if re.match(r"(RESULT|MARK|tripwire|Yomitan's backend|import progress)", text) or line.startswith(("E/", "W/")):
                seen.append(text)
                print(text, flush=True)
            if re.search(until, text):
                matched = True
                break
    finally:
        proc.kill()
    if not matched and fatal:
        sys.exit(f"timed out after {timeout}s waiting for /{until}/")
    return seen


def start(args):
    serial = serial_of(args.device)
    adb(serial, "shell", f"am force-stop {PKG}")
    adb(serial, "logcat", "-c")
    adb(serial, "shell", f"am start -n {ACTIVITY}")
    follow(serial, r"^RESULT state .*(Ready|Failed|Off)", args.timeout)


def cmd(args):
    serial = serial_of(args.device)
    json.loads(args.json)
    adb(serial, "logcat", "-c")
    quoted = args.json.replace("'", "'\\''")
    adb(serial, "shell", f"am start -n {ACTIVITY} --es cmd '{quoted}'")
    follow(serial, r"^RESULT (done|error) ", args.timeout)


def shot(args):
    serial = serial_of(args.device)
    png = subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"], capture_output=True).stdout
    Path(args.file).write_bytes(png)
    print(f"saved {args.file}")


def pss_mb(serial, target):
    m = re.search(r"TOTAL PSS:\s+([\d,]+)", adb(serial, "shell", f"dumpsys meminfo {target}"))
    return int(m.group(1).replace(",", "")) // 1024 if m else None


def mem(args):
    serial = serial_of(args.device)
    app_pid = adb(serial, "shell", f"pidof {PKG}", check=False).strip()
    if not app_pid:
        sys.exit("the app is not running")
    print(f"app PSS {pss_mb(serial, app_pid)} MB")
    # The WebView renderer is an isolated process bound as a service of the app.
    services = adb(serial, "shell", f"dumpsys activity services {PKG}")
    pids = sorted(set(re.findall(r"ProcessRecord\{\w+ (\d+):com\.google\.android\.webview:sandboxed_process", services)))
    for pid in pids:
        print(f"renderer (pid {pid}) PSS {pss_mb(serial, pid)} MB")
    if not pids:
        print("no WebView renderer bound to the app")


def clean(args):
    serial = serial_of(args.device)
    adb(serial, "shell", f"rm -rf {REMOTE}")
    print(f"removed {REMOTE}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("testdict").set_defaults(fn=testdict)
    sub.add_parser("fetch").set_defaults(fn=fetch)
    s = sub.add_parser("push"); s.add_argument("device"); s.add_argument("files", nargs="+"); s.set_defaults(fn=push)
    s = sub.add_parser("start"); s.add_argument("device"); s.add_argument("--timeout", type=int, default=90); s.set_defaults(fn=start)
    s = sub.add_parser("cmd"); s.add_argument("device"); s.add_argument("json"); s.add_argument("--timeout", type=int, default=900); s.set_defaults(fn=cmd)
    s = sub.add_parser("mem"); s.add_argument("device"); s.set_defaults(fn=mem)
    s = sub.add_parser("shot"); s.add_argument("device"); s.add_argument("file"); s.set_defaults(fn=shot)
    s = sub.add_parser("clean"); s.add_argument("device"); s.set_defaults(fn=clean)
    args = p.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
