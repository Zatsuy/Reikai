---
name: grill
description: Turn an owner request or idea into a shared, researched decision before anything is built - restate it, gather the facts yourself, check it against the evidence, suggest better options when they exist, then ask the owner only the decisions that are theirs and record the answers. Use when the owner asks for a feature, a change of direction, or says "I want", "what if", "should we".
argument-hint: "<the request>"
effort: high
---
Request: `$ARGUMENTS`. Adapted from mattpocock/skills `grilling` (MIT). The owner is not a
professional developer: explain in plain English, never talk down, and do not simply comply when
the evidence points elsewhere.

1. **Restate** the request in two plain lines and what the owner seems to want from it.
2. **Facts are your job.** Look up the code, `docs/fork/`, the refs and the web (subagents for
   breadth) before asking anything. Never ask the owner what the repository can answer.
3. **Check the fit.** Does it align with the research, the architecture principles and the
   decisions already standing? If a better option exists, say so plainly: what it is, why, and
   what the owner gives up either way.
4. **Ask the decision frontier**: only the questions the owner can answer now, in one
   AskUserQuestion call (at most four), each with 2-4 options, your recommendation first, and what
   each option changes for them. Repeat rounds until nothing the owner must decide remains.
5. **Record**: answers into `docs/fork/decisions.md` (*Standing decisions* plus a dated *Log* line
   quoting the owner); the work into `docs/fork/ROADMAP.md` as one-line items with their "You:"
   steps. Do not start building unless the owner says so.
