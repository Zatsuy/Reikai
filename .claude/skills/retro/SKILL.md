---
name: retro
description: The self-improving half of the harness - measure recent agent sessions against thresholds, trace each crossing to its cause in the transcripts, then fix mechanics at once, trial instruction changes as canaries, and take owner-facing changes to the owner. Also prunes harness text that no longer earns its cost. Use weekly, after a large item, or when the owner asks why something was slow or costly.
argument-hint: "[--since 7d | --session <id>] [label]"
effort: high
---
Adapted from the owner's KanjiWeave retro and mattpocock/skills `retro` (MIT). A change starts from
a measurement and is kept only if the next measurement agrees (docs/fork/harness.md).

1. **Measure**: `scripts/fork/retro.py $ARGUMENTS --record` (default `--since 7d`). Compare with
   the previous entries in `docs/fork/harness/retro-log.jsonl`.
2. **Trace** each crossed threshold to its cause in the transcripts
   (`~/.claude/projects/<dir>/<session>.jsonl` and `<session>/subagents/`), citing
   `<file>:<line>`. For more than a few sessions, give the reading to a subagent.
3. **Act by class**:
   - **A, mechanics** (a script, hook, setting, path, a missing command in a skill): fix it now,
     with a test where one fits; log it in `docs/fork/harness-log.md` with the metric that should
     move.
   - **B, instructions** (skill text, rules, AGENTS.md): the smallest edit that addresses the
     evidence, logged as `canary` with its metric; the next retro keeps or reverts it.
   - **C, owner-facing** (product behaviour, how the owner works, safety surfaces: permissions,
     hook refusals, what agents may do unasked): an *Open* entry in `docs/fork/decisions.md` with
     the evidence, options and a recommendation; ask the owner.
4. **Prune**: propose deleting instructions a check or hook now enforces, rules the model follows
   anyway, and canaries whose metric did not move. AGENTS.md first: every line of it is paid on
   every turn of every agent.
5. **Edit, never regenerate**: targeted changes to single lines or sections, so what worked stays.
6. **Report** in five lines or fewer: what crossed, what you changed, what you reverted, what waits
   for the owner.
