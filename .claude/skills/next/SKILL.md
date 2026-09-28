---
name: next
description: Do the next item on the Reikai JP roadmap end to end - pick it, investigate, decide, build in small verified steps, review, commit, push, update the roadmap, and tell the owner exactly what they need to do. Use when the owner says "next", "continue", "keep going", or names a roadmap item.
argument-hint: "[roadmap item, optional]"
effort: high
---
The owner wants agents to run development on their own and to hear only what they must decide or
do. This skill is that loop.

1. **Start cold, cheaply.** `git pull --ff-only` (GitHub automation pushes to `main`), then read
   the session-start lines, `git status`, the *Now* section of
   `docs/fork/ROADMAP.md`, and *Standing decisions* plus *Open* in `docs/fork/decisions.md`.
   If `$ARGUMENTS` names an item, take that one.
2. **Pick.** The first unchecked *Now* item whose blockers are done. If it waits on an owner step
   (a "You:" line not yet done), do not start it: remind the owner of those steps in the final
   report and take the next item agents can do alone.
3. **Frame** in three lines: what "done" looks like to the owner, the surfaces and files involved,
   the performance budget if any (`docs/fork/architecture.md`).
4. **Investigate** with `/scout` unless the change fits in one sentence. Facts are your job: code,
   refs in `../refs/`, `docs/fork/research/`, the web.
5. **Decide.** Questions only the owner can answer go in one AskUserQuestion call: plain English,
   2-4 options each, your recommendation first, what each option changes for them. Record the
   answers in `decisions.md`. Low-stakes choices you make yourself: log one line in the roadmap
   item, `Ruling: <what> - <why> - <cost if wrong>`.
6. **Build in vertical slices**, each small enough for one context: code, then `/verify`, then a
   commit. New code in fork files (`jp.reikai`, fork modules); an upstream file only as a fenced
   seam. Keep one Gradle build running at a time.
7. **Review** the item's diff with `/review` and fix what the reviewers confirm.
8. **Device check** for anything user-visible: tablet first (the owner's main reader), then the
   phone, install and look (`/verify`); the one you need not connected, ask the owner to connect it
   (AskUserQuestion naming the device, D-019) and continue when they answer. Only steps that need
   the owner's own judgement, or a declined request, become test steps with
   `/owner-steps`.
9. **Land.** Tick the item in the roadmap with its commit SHAs and any "You:" steps, add a line to
   `docs/fork/CHANGELOG.md` if the owner will notice the change, commit, and push `origin main`
   once verification passed. Leave nothing behind (D-031): devices cleaned as `/verify` says,
   no stray files in the repository, and outputs you made for a one-off check (local `nightly` or
   `benchmark` builds, spike folders under `build/`) deleted.
10. **Measure.** `scripts/fork/retro.py --session ${CLAUDE_SESSION_ID} --record --label "<item>"`.
    Fix a crossed class A threshold now if it is small; otherwise leave it for `/retro`.
11. **Report** in at most eight plain lines: what changed for the owner, how it was verified, what
    they need to do (numbered), what comes next.

**Stop** when something unexpected breaks an assumption: explain it, give options, ask if it is the
owner's call. Past about 400k tokens of context, commit what is coherent, write where you stopped
under the roadmap item, and tell the owner to run `/next` in a new session.

**One item per session.** When the item is done, end the report by telling the owner to start the
next one in a new conversation: the repo carries everything the next agent needs.
