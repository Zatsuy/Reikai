---
name: scout
description: Investigate a non-trivial task before building it and produce an evidence-based plan - current behaviour with file:line citations, upstream and reference equivalents, helpers to reuse, risks (merge seams, performance, licence), vertical-slice plan, and the questions only the owner can answer. Read-only. Use before any feature, port, cross-cutting change or unfamiliar module.
argument-hint: "<task>"
effort: high
---
Task: `$ARGUMENTS`. Never edit files here. Ground every claim in code you just read.

1. **Restate** the task and what "done" means for the owner in two lines.
2. **Map the current behaviour** with `file:line` citations: entry points, data flow, the upstream
   seam the change would touch (`NovelReaderProvider.createViewport`, the extensions engine, ...).
   Hand broad reading to Explore subagents in parallel; keep only their conclusions.
3. **Find the equivalents**: how upstream Reikai, Mihon, Tsundoku, LNReader, Yomitan, Chimahon,
   ttu or Hoshi Reader do it (`../refs/<name>`), and `docs/fork/research/`. Note each licence before
   suggesting reuse: GPL, MIT, BSD and Apache code may be reused with credit; Google's closed
   binaries never.
4. **Reuse before writing**: search for an existing helper first.
5. **Risks**: seams in upstream files (how many, how hot: `git log --since=30.days -- <file>`),
   performance against the budgets in `docs/fork/architecture.md`, licence, things that need the
   owner's phone.
6. **Refute yourself**: for each surprising or load-bearing claim, have a fresh subagent try to
   disprove it against the code. Drop what does not survive.
7. **Plan** as vertical slices, each one context in size, each with its verify step and whether it
   needs the device.
8. **Questions**: split into facts (answer them now) and owner decisions (options plus a
   recommendation, for `/next` to ask).

Output at most about 60 lines plus citations. Point out any stale doc or memory you found.
