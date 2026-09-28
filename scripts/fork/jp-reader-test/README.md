# Japanese reading mode: page script tests (roadmap 4.1)

Headless tests of `app/src/main/assets/jp-reader/` (`jp-reader.js`, `jp-reader.css`) against the
page contract in `docs/fork/research/phase4-design-2026-09.md`. They serve chapter documents built
exactly as the contract describes at `https://chapter.reikai.invalid`, under a
Content-Security-Policy, with a stand-in for the app's `jpReader` WebMessageListener that records
every message, and drive the page as `JpPageViewport` and a finger would, at a phone (412 x 915)
and a tablet (800 x 1280) viewport, device pixel ratio 2.

```sh
npm ci --prefix scripts/fork/jp-reader-test
node scripts/fork/jp-reader-test/run.mjs            # exits 1 on any failure
node scripts/fork/jp-reader-test/run.mjs --only scroll       # tests whose name contains "scroll"
node scripts/fork/jp-reader-test/run.mjs --slowdown 4 --only re-layout   # CPU slowed 4 times
```

Chrome: `$REIKAI_CHROME` if set, else the installed Google Chrome, else Playwright's Chromium from
`$PLAYWRIGHT_BROWSERS_PATH` (defaults to `build/ms-playwright` when that exists). To download it
once, into the ignored build directory:

```sh
PLAYWRIGHT_BROWSERS_PATH=build/ms-playwright \
  node scripts/fork/jp-reader-test/node_modules/playwright-core/cli.js install chromium-headless-shell
```

## What is checked

- **Character count**: the page's total equals ttu's own code (`ttu-reference.mjs`, ttu's
  functions unchanged) on the untouched chapter, the fixture's own count, and a count made one
  character at a time; `counting` covers every rule (rt, hidden, aria-hidden, gaiji, surrogate
  pairs, half-width katakana, case folding).
- **Pages** (vertical and horizontal): page count, every turn, `edge` at both ends, `charOffset`
  equal to the first character on screen found by walking all of them, and every character, reading
  and picture inside the screen of its page (nothing clipped or split).
- **Positions**: `seekChar` then `state` then `seekChar` lands on the same page and offset, in all
  four layouts; the position survives font size, text direction, layout, furigana and margin
  changes and comes back exactly (no drift); start from `charOffset` or `fraction`.
- **Scrolling in both directions**: vertical text scrolls right to left (negative `scrollX`),
  horizontal text downward; `turn` moves 90% of a screen; `edge` at both ends; native touch drags
  and flings (Chromium's own touch input, so momentum is the browser's); a pull past either end
  posts `edge`; wheel; auto-scroll and its `edge` at the end.
- **Taps**: a character goes to `JpReader.onTextTap`; declined or unset posts `tap` `none`; margins
  follow the tap zones; zones mode; long press; `touch`; swipes and `invertSwipe`.
- **Furigana**: all five modes by computed style; a tap on hidden furigana reveals it (toggle hides
  it again) and posts nothing.
- **Read-aloud**: `paragraphs()` equals upstream's rule; `highlight` marks and turns to a paragraph
  or part of one; `firstVisibleParagraph`.
- **Typography**: upright one- and two-digit runs and `!!` `!?` `?!` in vertical text only;
  `line-break: strict`.
- **Timing**: re-layout of the 20 000-character chapter after a font-size change (fails over
  300 ms) and `turn()` at a reader's pace (fails over a frame), printed at the end.
- **Hygiene**: no page errors, no CSP violations (one test uses a policy with no inline styles at
  all), one `ready` per document, every message in the contract's shape.

`fixtures.mjs` holds the sample chapters (sentences written for these tests) and builds the chapter
document; `page-helpers.mjs` holds the in-page measuring tools, written apart from `jp-reader.js`
so they check it rather than repeat it.
