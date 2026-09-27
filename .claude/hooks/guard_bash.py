#!/usr/bin/env python3
"""PreToolUse guard for Bash: refuse what must never happen, ask for what is hard to undo.

Every reason starts with [fork-guard] so scripts/fork/retro.py can count refusals. A refusal that
blocks real work is a harness defect: fix the rule (class A in docs/fork/harness.md), do not work
around it.
"""
import json
import re
import shlex
import sys

SAFE_RM = re.compile(r"^(\./)?(build|app/build|\.gradle|\.kotlin|node_modules)(/|$)|^/tmp/|scratchpad|/build(/|$)")
SECRET_FILE = r"(google-services\.json|keystore\.properties|[\w.-]+\.jks|[\w.-]+\.keystore|(^|/)\.env(\.[\w-]+)?)"
READ_VERBS = r"\b(cat|less|more|head|tail|cp|mv|base64|xxd|od|strings|scp|rsync|curl|openssl|keytool|nano|vi|vim)\b"

DENY = [
    (r"\bgit\s+push\b[^|;&]*\bupstream\b|\bgit\s+push\b[^|;&]*unseensnick",
     "Never push to upstream Reikai. The fork pushes only to origin (Zatsuy/Reikai)."),
    (r"\bgit\s+remote\s+set-url\s+origin\b[^|;&]*unseensnick",
     "origin must stay the owner's fork (Zatsuy/Reikai)."),
    (r"\bgit\s+push\b[^|;&]*(\s--force(-with-lease)?\b|\s-f\b|\s\+\S)",
     "Force-pushing rewrites the fork's public history. Ask the owner first (AskUserQuestion)."),
    (rf"{READ_VERBS}[^|;&]*{SECRET_FILE}",
     "Secrets (signing keys, Firebase config, .env) are never read or copied by agents."),
    (r"(^|[\s;&|(])sudo\b|\bdnf\s+(install|remove|upgrade|update)\b|\brpm\s+-[iU]|\bflatpak\s+install\b",
     "No system installs (the owner decided: JDK 25 is the JDK, nothing else gets installed). "
     "If a tool is truly missing, ask the owner."),
    (r"gradlew\b[^|;&]*(-Pdist=(ci|github)\b|-Pinclude-telemetry\b)",
     "The fork builds without Firebase telemetry (GPL, decisions D-004). Use the default local profile."),
    (r"(^|[;&|]\s*|\b(bash|sh|zsh)\s+)(\./|\S*/)?scripts/fork/setup-github\.sh|\bgh\s+secret\s+(set|delete|remove)\b|\bgh\s+repo\s+deploy-key\s+(add|delete)\b",
     "The owner runs this themselves: it creates the app's signing key and GitHub secrets, which "
     "agents never handle (AGENTS.md rule 5). Point the owner to the ROADMAP checklist instead."),
]

# A wait in the foreground blocks the session and wastes the context cache. The same wait in a
# background command is Claude Code's recommended pattern, so it is allowed there.
POLL = (r"\bsleep\s+([3-9]\d|\d{3,})\b|\b(while|until)\b[^\n]*;\s*do\b[^\n]*\bsleep\b",
        "Foreground polling blocks the session. Run the wait with run_in_background and act on the "
        "notification, or use the Monitor tool with an until-condition.")

ASK = [
    (r"\bgit\s+reset\s+--hard\b", "git reset --hard discards work."),
    (r"\bgit\s+clean\s+-[a-z]*f", "git clean -f deletes untracked files."),
    (r"\bgit\s+(checkout|restore)\s+(--\s+)?\.(\s|$)", "This discards every uncommitted change."),
    (r"\bgit\s+branch\s+-D\b", "git branch -D deletes a branch that may hold unmerged work."),
    (r"\bgit\s+stash\s+(drop|clear)\b", "Dropping a stash loses its changes."),
    (r"\bsdkmanager\b[^|;&]*--install|\bsdkmanager\s+\"", "Installing Android SDK packages changes the owner's machine."),
]


def rm_outside_safe(cmd):
    for part in re.split(r"[;&|]+", cmd):
        try:
            words = shlex.split(part)
        except ValueError:
            words = part.split()
        if not words or words[0] != "rm":
            continue
        flags = "".join(w[1:] for w in words[1:] if w.startswith("-"))
        if "r" not in flags.lower():
            continue
        targets = [w for w in words[1:] if not w.startswith("-")]
        if any(not SAFE_RM.search(t) for t in targets):
            return True
    return False


def decide(cmd, background=False):
    for pattern, reason in DENY:
        if re.search(pattern, cmd):
            return "deny", reason
    if not background and re.search(POLL[0], cmd):
        return "deny", POLL[1]
    for pattern, reason in ASK:
        if re.search(pattern, cmd):
            return "ask", reason
    if rm_outside_safe(cmd):
        return "ask", "Recursive delete outside build/ and scratch folders."
    return None, None


def main():
    data = json.load(sys.stdin)
    tool_input = data.get("tool_input") or {}
    decision, reason = decide(tool_input.get("command", ""), bool(tool_input.get("run_in_background")))
    if decision:
        print(json.dumps({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": decision,
            "permissionDecisionReason": f"[fork-guard] {reason}",
        }}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
