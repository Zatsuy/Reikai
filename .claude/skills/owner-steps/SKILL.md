---
name: owner-steps
description: Write step-by-step instructions for things only the owner can do - test on their phone, change a GitHub setting, add a secret, install AnkiDroid, import dictionaries, file an upstream issue - so each step is one clear action with its expected result. Use whenever work needs the owner's hands, and for manual test scripts.
argument-hint: "<what the owner needs to do>"
effort: medium
---
Adapted from mattpocock/skills `wizard` and `to-questionnaire` (MIT) and upstream's `test-steps`.
The owner is not a professional developer; every step must be doable without guessing.

- **One action per step**, numbered: exactly where to tap or what to type, then **Expect:** what
  they should see. **If not:** what to send back (a screenshot, the text on screen).
- **Group by precondition** (phone plugged in, app installed, logged in to GitHub) and say up top
  how long it takes.
- **Check reachability**: every screen and button named must exist in the current build (read the
  code or the UI dump); list anything unreachable at the top instead of pretending.
- **Never ask for secrets in chat.** Say where they paste a token (a GitHub settings page) and how
  to confirm it worked.
- **Where it lives**: short steps go in the roadmap item's "You:" list; a longer test script goes in
  `docs/fork/testing/<item>.md`, linked from the item.
- For device testing, prefer steps the agent can then check (the owner leaves the phone connected
  and the agent reads the UI dump) over steps that need the owner to judge.
