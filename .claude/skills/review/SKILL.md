---
name: review
description: Review a diff with fresh eyes before it lands - two independent reviewers in parallel (standards and spec), findings verified against the code before any fix. Use at the end of every roadmap item, for any non-trivial diff, and on harness changes.
argument-hint: "[base ref, default origin/main]"
effort: high
---
Adapted from mattpocock/skills `code-review` (MIT). The author of a change is the worst judge of
it, so the reviewers start from a clean context.

1. **Pin the range**: base is `$ARGUMENTS` or `origin/main`; the diff is `git diff <base>...HEAD`.
   Note the roadmap item and decisions it implements.
2. **Spawn both reviewers in one message** (Agent tool), each given the base, the range and the item:
   - `reviewer`: correctness, the conventions in `.claude/rules/`, performance, security, and
     mergeability (seams minimal and fenced, fork code in fork places, licence of anything borrowed).
   - `spec-checker`: does the diff do what the item and `decisions.md` say, nothing missing, no
     scope creep, and is every owner-visible change explained where the owner will see it.
3. **Keep the two reports separate.** For each finding, read the cited code yourself: confirm or
   reject it. Reviewers asked for problems always find some; only confirmed ones get fixed.
4. **Fix** confirmed findings (then `/verify`); list rejected ones with a one-line reason in your
   report.
5. **Harness diffs** get one more question: does it add text every agent pays for on every turn,
   and could a check or hook hold the rule instead?
