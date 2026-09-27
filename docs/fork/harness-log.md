# Harness log

Every change to the harness, newest first: what changed, the evidence, its class
([harness.md](harness.md), *Self-improvement*), the metric to watch, and its status (`canary`
until a retro keeps or reverts it, then `kept` or `reverted`).

| Date | Change | Evidence | Class | Watch | Status |
|---|---|---|---|---|---|
| 2026-09-27 | Replaced upstream's harness: `AGENTS.md` under 120 lines, four path-scoped rules, nine skills, three roles, Python hooks, git hooks, retro script, fork CI | Upstream loaded ~110 KB every turn; its hooks never ran on Linux (1,417 hook errors this session); owner asked for a lean, self-improving harness (D-011) | A+B | hook errors = 0; peak context; cost per roadmap item | canary |
| 2026-09-27 | `retro.py` counts `--help` lookups only (not `-h`) | First run flagged `df -h` as a help lookup | A | help_calls | kept |
