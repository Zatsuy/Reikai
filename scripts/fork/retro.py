#!/usr/bin/env python3
"""Measure what agent sessions on this repo actually did, against thresholds.

The self-improving half of the harness (docs/fork/harness.md): a harness change starts from a
number here, and is kept only if the next measurement agrees.

Usage:
  scripts/fork/retro.py                   the latest session
  scripts/fork/retro.py --since 7d        every session in the last 7 days (also 12h, 2026-09-27)
  scripts/fork/retro.py --session <id>    one session
  scripts/fork/retro.py --json            machine-readable
  scripts/fork/retro.py --record          also append the summary to docs/fork/harness/retro-log.jsonl

Reads Claude Code transcripts (~/.claude/projects/<dir>/<session>.jsonl plus <session>/subagents/).
Their format is internal to Claude Code and may change between releases; when a field goes
missing this script reports zeros rather than failing, so check a surprising zero by hand.
"""
import argparse
import json
import re
import sys
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = json.loads((ROOT / "scripts/fork/retro-thresholds.json").read_text())
PROJECT_DIR = Path.home() / ".claude/projects" / re.sub(r"[^A-Za-z0-9]", "-", str(ROOT))
LOG = ROOT / "docs/fork/harness/retro-log.jsonl"


def parse_time(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def price_for(model):
    table = CONFIG["prices_usd_per_mtok"]
    for key, prices in table.items():
        if key != "default" and model and model.startswith(key):
            return prices
    return table["default"]


def read_lines(path):
    with path.open(errors="replace") as fh:
        for line in fh:
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue


def measure(files):
    calls = {}  # message id -> (model, usage, timestamp)
    tools, tool_errors, error_samples = Counter(), 0, Counter()
    hook_errors, hook_denials, compactions = Counter(), 0, 0
    bash_cmds, times = [], []
    pending = {}  # tool_use id -> (name, command)
    gradle_runs = gradle_fails = 0
    for path in files:
        for d in read_lines(path):
            ts = d.get("timestamp")
            if ts:
                times.append(parse_time(ts))
            if d.get("type") == "attachment":
                att = d.get("attachment") or {}
                if att.get("type") == "hook_non_blocking_error":
                    hook_errors[att.get("hookName", "?")] += 1
            if d.get("type") == "system" and "compact" in json.dumps(d)[:400]:
                compactions += 1
            msg = d.get("message")
            if not isinstance(msg, dict):
                continue
            if d.get("type") == "assistant" and msg.get("usage") and msg.get("id"):
                calls[msg["id"]] = (msg.get("model"), msg["usage"], ts)
            content = msg.get("content")
            if not isinstance(content, list):
                continue
            for block in content:
                if not isinstance(block, dict):
                    continue
                if block.get("type") == "tool_use":
                    name = block.get("name", "?")
                    tools[name] += 1
                    cmd = (block.get("input") or {}).get("command", "") if name == "Bash" else ""
                    pending[block.get("id")] = (name, cmd)
                    if cmd:
                        bash_cmds.append(cmd)
                elif block.get("type") == "tool_result":
                    text = json.dumps(block.get("content"))[:600]
                    name, cmd = pending.get(block.get("tool_use_id"), ("?", ""))
                    if "gradlew" in cmd or "scripts/fork/gw" in cmd:
                        gradle_runs += 1
                        if block.get("is_error") or "BUILD FAILED" in text:
                            gradle_fails += 1
                    if "[fork-guard]" in text:
                        hook_denials += 1
                    if block.get("is_error"):
                        tool_errors += 1
                        error_samples[f"{name}: {text[1:70]}"] += 1

    tok = Counter()
    peak = 0
    cost = 0.0
    models = Counter()
    call_times = []
    for model, u, ts in calls.values():
        models[model] += 1
        cache_write = u.get("cache_creation") or {}
        w5 = cache_write.get("ephemeral_5m_input_tokens", 0)
        w1h = cache_write.get("ephemeral_1h_input_tokens", 0)
        if not (w5 or w1h):
            w5 = u.get("cache_creation_input_tokens", 0)
        inp, read, out = u.get("input_tokens", 0), u.get("cache_read_input_tokens", 0), u.get("output_tokens", 0)
        tok.update(input=inp, cache_read=read, cache_write_5m=w5, cache_write_1h=w1h, output=out)
        peak = max(peak, inp + read + w5 + w1h)
        p = price_for(model)
        cost += (inp * p["input"] + read * p["cache_read"] + w5 * p["cache_write_5m"]
                 + w1h * p["cache_write_1h"] + out * p["output"]) / 1e6
        if ts:
            call_times.append(parse_time(ts))
    call_times.sort()
    gaps = [(b - a).total_seconds() for a, b in zip(call_times, call_times[1:])]
    total_tools = sum(tools.values())
    polling = sum(1 for c in bash_cmds if re.search(r"\bsleep\s+([3-9]\d|\d{3,})", c)
                  or re.search(r"\b(while|until)\b[^\n]*\bsleep\b", c))
    return {
        "sessions": len([f for f in files if f.parent == PROJECT_DIR]),
        "subagent_transcripts": len([f for f in files if f.parent.name == "subagents"]),
        "wall_minutes": round((max(times) - min(times)).total_seconds() / 60, 1) if times else 0,
        "api_calls": len(calls),
        "models": dict(models),
        "tokens": dict(tok),
        "peak_context": peak,
        "est_cost_usd": round(cost, 2),
        "tool_calls": total_tools,
        "tools": dict(tools.most_common(12)),
        "tool_error_rate": round(tool_errors / total_tools, 3) if total_tools else 0,
        "tool_errors_top": [f"{n}x {s}" for s, n in error_samples.most_common(5)],
        "hook_errors": dict(hook_errors),
        "hook_denials": hook_denials,
        "gradle_runs": gradle_runs,
        "gradle_failures": gradle_fails,
        "help_calls": sum(1 for c in bash_cmds if re.search(r"\s--help\b", c)),
        "polling_calls": polling,
        "cache_gaps_over_5m": sum(1 for g in gaps if g > 300),
        "cache_gaps_over_1h": sum(1 for g in gaps if g > 3600),
        "ask_user": tools.get("AskUserQuestion", 0),
        "compactions": compactions,
    }


def crossings(m):
    t = CONFIG["thresholds"]
    found = []
    def check(key, value, limit, klass, why):
        if value > limit:
            found.append({"metric": key, "value": value, "limit": limit, "class": klass, "why": why})
    check("tool_error_rate", m["tool_error_rate"], t["tool_error_rate"], "A",
          "tools failing often: a missing script, a wrong path in a skill, or a misleading rule")
    check("hook_errors", sum(m["hook_errors"].values()), t["hook_errors"], "A",
          "a hook fails to run: its gate is silently off")
    check("peak_context", m["peak_context"], t["peak_context"], "A/B",
          "context grew large: hand reading to subagents, split the task, trim always-loaded text")
    check("help_calls", m["help_calls"], t["help_calls"], "A",
          "agents looking up how to run things: add the command to the skill or AGENTS.md")
    check("polling_calls", m["polling_calls"], t["polling_calls"], "A",
          "polling waits: use background commands and notifications")
    check("gradle_failures", m["gradle_failures"], t["gradle_failures"], "A/B",
          "builds failing repeatedly: check the verify recipe or the code conventions")
    check("cache_gaps_over_1h", m["cache_gaps_over_1h"], t["cache_gaps_over_1h"], "A",
          "idle over an hour: the prompt cache expired and the next call paid full price")
    check("est_cost_usd", m["est_cost_usd"], t["est_cost_usd_per_session"], "B",
          "an expensive session: check what dominated (tools, subagents, context)")
    return found


def session_files(args):
    mains = sorted(PROJECT_DIR.glob("*.jsonl"), key=lambda p: p.stat().st_mtime)
    if args.session:
        mains = [PROJECT_DIR / f"{args.session}.jsonl"]
    elif args.since:
        now = datetime.now(timezone.utc)
        m = re.fullmatch(r"(\d+)([hd])", args.since)
        cutoff = now - (timedelta(hours=int(m[1])) if m and m[2] == "h" else timedelta(days=int(m[1]))) \
            if m else datetime.fromisoformat(args.since).replace(tzinfo=timezone.utc)
        mains = [p for p in mains if datetime.fromtimestamp(p.stat().st_mtime, timezone.utc) >= cutoff]
    else:
        mains = mains[-1:]
    files = []
    for p in mains:
        files.append(p)
        files.extend(sorted((PROJECT_DIR / p.stem / "subagents").glob("*.jsonl")))
    return [f for f in files if f.exists()], [p.stem for p in mains]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--since")
    ap.add_argument("--session")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--record", action="store_true")
    ap.add_argument("--label", default="")
    args = ap.parse_args()
    files, ids = session_files(args)
    if not files:
        print(f"No transcripts found in {PROJECT_DIR}")
        return 1
    m = measure(files)
    found = crossings(m)
    record = {"at": datetime.now(timezone.utc).isoformat(timespec="seconds"), "label": args.label,
              "session_ids": ids, "metrics": m, "crossings": found}
    if args.record:
        LOG.parent.mkdir(parents=True, exist_ok=True)
        with LOG.open("a") as fh:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")
    if args.json:
        print(json.dumps(record, indent=1, ensure_ascii=False))
        return 0
    tk = m["tokens"]
    print(f"{len(ids)} session(s), {m['subagent_transcripts']} subagent transcripts, {m['wall_minutes']} min wall")
    print(f"API calls {m['api_calls']}, est ${m['est_cost_usd']}, peak context {m['peak_context']:,} tokens")
    print(f"tokens: input {tk.get('input', 0):,} cache-read {tk.get('cache_read', 0):,} "
          f"write5m {tk.get('cache_write_5m', 0):,} write1h {tk.get('cache_write_1h', 0):,} output {tk.get('output', 0):,}")
    print(f"tools {m['tool_calls']} (errors {m['tool_error_rate']:.1%}), gradle {m['gradle_runs']} "
          f"(failed {m['gradle_failures']}), hook errors {sum(m['hook_errors'].values())}, "
          f"hook denials {m['hook_denials']}, asks {m['ask_user']}, gaps>1h {m['cache_gaps_over_1h']}")
    for s in m["tool_errors_top"]:
        print(f"  error: {s}")
    if found:
        print("Crossed thresholds:")
        for c in found:
            print(f"  [{c['class']}] {c['metric']} = {c['value']} (limit {c['limit']}): {c['why']}")
    else:
        print("No thresholds crossed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
