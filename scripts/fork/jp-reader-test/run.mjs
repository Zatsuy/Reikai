/*
 * Reikai JP: headless tests of the Japanese reading mode's page script (roadmap 4.1).
 * GPL-3.0-or-later.
 *
 * Serves app/src/main/assets/jp-reader/ and chapter documents built exactly as the page contract
 * says (docs/fork/research/phase4-design-2026-09.md) at https://chapter.reikai.invalid, with a
 * Content-Security-Policy and a stand-in for the app's jpReader WebMessageListener that records every
 * message, then drives the page the way JpPageViewport and a finger do, at a phone (412 x 915) and a
 * tablet (800 x 1280) viewport, both at device pixel ratio 2. Exits 1 on any failure.
 *
 * Run: node scripts/fork/jp-reader-test/run.mjs, after `npm ci --prefix scripts/fork/jp-reader-test`.
 * Chrome: $REIKAI_CHROME if set, else the installed Google Chrome, else Playwright's Chromium from
 * $PLAYWRIGHT_BROWSERS_PATH (default build/ms-playwright when present; see README.md).
 * --only <text> runs the tests whose name contains the text. --slowdown <n> slows every page's CPU n
 * times (Chrome's throttling), a rough stand-in for a slower device when reading the timings.
 */
import {readFile} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {join, normalize, sep} from 'node:path';
import {CHAPTERS, IMAGES, ORIGIN, chapterDocument, random} from './fixtures.mjs';
import {TTU_REFERENCE} from './ttu-reference.mjs';
import {PAGE_HELPERS} from './page-helpers.mjs';

const HERE = fileURLToPath(new URL('.', import.meta.url));
const ROOT = join(HERE, '../../..');
const ASSETS = join(ROOT, 'app/src/main/assets/jp-reader');
if (!process.env.PLAYWRIGHT_BROWSERS_PATH && existsSync(join(ROOT, 'build/ms-playwright'))) {
    process.env.PLAYWRIGHT_BROWSERS_PATH = join(ROOT, 'build/ms-playwright');
}
const {chromium} = await import('playwright-core');

const argument = (name) => (process.argv.includes(name) ? process.argv[process.argv.indexOf(name) + 1] : null);
const ONLY = argument('--only');
const SLOWDOWN = Number(argument('--slowdown') || 1);
const TIMEOUT_MS = 10 * 60_000;
// The design's budget for a re-layout of a 20 000-character chapter on the tablet.
const RELAYOUT_BUDGET_MS = 300;

const started = Date.now();
const say = (text) => console.log(`[jp-reader ${((Date.now() - started) / 1000).toFixed(1)} s] ${text}`);
const failures = [];
const report = [];
const timer = setTimeout(() => {
    console.error('[jp-reader] FAIL: no result after the time limit');
    process.exit(1);
}, TIMEOUT_MS);

// --- the chapter origin ---------------------------------------------------------------------------

const CSP = "default-src 'none'; script-src " + ORIGIN + '/jp-reader/; style-src ' + ORIGIN +
    "/jp-reader/ 'unsafe-inline'; img-src " + ORIGIN + '; font-src ' + ORIGIN;
const STRICT_CSP = CSP.replace(" 'unsafe-inline'", '');
const docs = new Map();
let nextDoc = 1;

async function serve(route) {
    const url = new URL(route.request().url());
    if (url.origin !== ORIGIN) return route.abort('blockedbyclient');
    const path = url.pathname;
    if (path.startsWith('/jp-reader/')) {
        const file = normalize(join(ASSETS, path.slice('/jp-reader/'.length)));
        if (!file.startsWith(ASSETS + sep) || !existsSync(file)) return route.fulfill({status: 404, body: ''});
        const type = file.endsWith('.css') ? 'text/css' : 'text/javascript';
        return route.fulfill({status: 200, contentType: `${type}; charset=utf-8`, body: await readFile(file)});
    }
    if (path.startsWith('/img/') && IMAGES[path.slice(5)]) {
        return route.fulfill({status: 200, contentType: 'image/svg+xml', body: IMAGES[path.slice(5)]});
    }
    const doc = docs.get(path);
    if (doc) {
        return route.fulfill({
            status: 200,
            headers: {'content-type': 'text/html; charset=utf-8', 'content-security-policy': doc.csp},
            body: doc.html,
        });
    }
    return route.fulfill({status: 404, body: ''});
}

// Stands in for the app's WebMessageListener object, and records what the page did wrong.
const INIT_SCRIPT = `
  window.__jpMessages = [];
  window.__cspViolations = [];
  window.jpReader = { postMessage: (text) => window.__jpMessages.push(JSON.parse(text)) };
  document.addEventListener('securitypolicyviolation',
    (e) => window.__cspViolations.push(e.violatedDirective + ' ' + e.blockedURI + ' ' + e.sourceFile + ':' + e.lineNumber));
`;

// --- settings -------------------------------------------------------------------------------------

// Tap zones as the app sends them: already mirrored for vertical text.
const ZONES = {
    vertical: [[0, 0, 0.3, 1, 'forward'], [0.7, 0, 1, 1, 'back'], [0.3, 0, 0.7, 1, 'menu']],
    horizontal: [[0, 0, 0.3, 1, 'back'], [0.7, 0, 1, 1, 'forward'], [0.3, 0, 0.7, 1, 'menu']],
};

const BASE = {
    writing: 'vertical',
    layout: 'paged',
    furigana: 'show',
    tapMode: 'lookup',
    fontFamily: "'Noto Serif CJK JP', 'Noto Serif CJK', serif",
    fontSize: 22,
    lineHeight: 1.8,
    margins: {top: 24, right: 20, bottom: 24, left: 20},
    insets: {top: 24, bottom: 16},
    colors: {background: '#fdf8ee', text: '#222222', hint: '#999999'},
    textIndent: 1,
    justify: true,
    invertSwipe: false,
};

function settingsWith(changes = {}) {
    const s = {...BASE, ...changes};
    s.tapZones = changes.tapZones || ZONES[s.writing];
    return s;
}

// --- a tiny test runner ---------------------------------------------------------------------------

class Failure extends Error {}

function check(condition, message) {
    if (!condition) throw new Failure(message);
}

function same(actual, expected, message) {
    const a = JSON.stringify(actual);
    const e = JSON.stringify(expected);
    if (a !== e) throw new Failure(`${message}: got ${a}, expected ${e}`);
}

async function test(name, body, always = false) {
    if (ONLY && !always && !name.includes(ONLY)) return;
    try {
        await body();
        say(`ok   ${name}`);
    } catch (error) {
        failures.push(`${name}: ${error.message}`);
        say(`FAIL ${name}: ${error instanceof Failure ? error.message : error.stack}`);
    }
}

const note = (text) => {
    report.push(text);
    say(`     ${text}`);
};

const median = (values) => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];

// --- driving a page -------------------------------------------------------------------------------

const allMessages = [];
const pageErrors = [];

async function open(context, chapterKey, {settings = {}, charOffset = null, fraction = 0, strict = false, script = true} = {}) {
    const chapter = CHAPTERS[chapterKey];
    const s = settingsWith(settings);
    const path = `/chapter/${nextDoc++}`;
    const html = chapterDocument(chapter, {charOffset, fraction, settings: s}, {inlineStyles: !strict, script});
    docs.set(path, {html, csp: strict ? STRICT_CSP : CSP});
    const page = await context.newPage();
    page.on('pageerror', (error) => pageErrors.push(`${chapterKey}: ${error.message}`));
    if (SLOWDOWN > 1) {
        const cdp = await context.newCDPSession(page);
        await cdp.send('Emulation.setCPUThrottlingRate', {rate: SLOWDOWN});
    }
    const opened = Date.now();
    await page.goto(ORIGIN + path);
    let ready = null;
    if (script) ready = await waitMessage(page, 'ready', 0, 15_000);
    const openMs = Date.now() - opened;
    await page.evaluate(TTU_REFERENCE);
    await page.evaluate(PAGE_HELPERS);
    return {page, ready, settings: s, openMs, chapter};
}

async function close(page) {
    const messages = await page.evaluate(() => window.__jpMessages);
    const violations = await page.evaluate(() => window.__cspViolations);
    allMessages.push(...messages);
    for (const v of violations) pageErrors.push(`CSP violation: ${v}`);
    await page.close();
}

const mark = (page) => page.evaluate(() => window.__jpMessages.length);

async function waitMessage(page, t, from, timeout = 5000) {
    const handle = await page.waitForFunction(
        ([type, index]) => window.__jpMessages.slice(index).find((m) => m.t === type) || null,
        [t, from],
        {timeout},
    );
    return handle.jsonValue();
}

const messagesSince = (page, from) => page.evaluate((index) => window.__jpMessages.slice(index), from);
const state = (page) => page.evaluate(() => JpReader.state());
const settle = (page, ms = 250) => page.waitForTimeout(ms);
const frames = (page) => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));

async function apply(page, settings) {
    return page.evaluate((s) => {
        const t0 = performance.now();
        JpReader.applySettings(s);
        return performance.now() - t0;
    }, settings);
}

async function swipe(page, from, to, steps = 8) {
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Input.dispatchTouchEvent', {type: 'touchStart', touchPoints: [{x: from.x, y: from.y}]});
    for (let i = 1; i <= steps; i++) {
        const x = from.x + ((to.x - from.x) * i) / steps;
        const y = from.y + ((to.y - from.y) * i) / steps;
        await cdp.send('Input.dispatchTouchEvent', {type: 'touchMove', touchPoints: [{x, y}]});
    }
    await cdp.send('Input.dispatchTouchEvent', {type: 'touchEnd', touchPoints: []});
    await cdp.detach();
}

async function longPress(page, point, ms = 600) {
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Input.dispatchTouchEvent', {type: 'touchStart', touchPoints: [point]});
    await page.waitForTimeout(ms);
    await cdp.send('Input.dispatchTouchEvent', {type: 'touchEnd', touchPoints: []});
    await cdp.detach();
}

// --- the tests ------------------------------------------------------------------------------------

async function countingTests(context) {
    await test('ttu character count matches ttu\'s own code and the fixtures', async () => {
        for (const key of Object.keys(CHAPTERS)) {
            const chapter = CHAPTERS[key];
            const bare = await open(context, key, {script: false});
            const reference = await bare.page.evaluate(() => ttuRef.countNodes(document.getElementById('jp-chapter')));
            await close(bare.page);
            const {page, ready} = await open(context, key);
            const perChar = await page.evaluate(() => __t.count());
            const after = await page.evaluate(() => ttuRef.countNodes(document.getElementById('jp-chapter')));
            await close(page);
            same(ready.pos.chars, reference, `${key}: page count vs ttu's code on the untouched chapter`);
            same(ready.pos.chars, chapter.chars, `${key}: page count vs the fixture's own count`);
            same(perChar, reference, `${key}: character-by-character count vs ttu's`);
            same(after, reference, `${key}: ttu's count after the page's upright-digit wrapping`);
        }
        note(`chapters counted: ${Object.entries(CHAPTERS).map(([k, c]) => `${k} ${c.chars}`).join(', ')}`);
    });
}

async function pagedTests(context, vp) {
    for (const writing of ['vertical', 'horizontal']) {
        await test(`${vp.name} ${writing} paged: pages, turns, edges, exact position, nothing clipped`, async () => {
            const {page, ready, openMs} = await open(context, 'long', {settings: {writing}});
            const pages = ready.pos.pages;
            check(pages > 5, `expected many pages, got ${pages}`);
            same([ready.pos.page, ready.pos.charOffset, ready.pos.fits, ready.pos.endSeen], [1, 0, false, false], 'first page');
            const clip = await page.evaluate(() => __t.clipReport());
            check(clip.badCount === 0, `${clip.badCount} of ${clip.checked} boxes cross a page edge: ${clip.bad.join('; ')}`);

            let from = await mark(page);
            await page.evaluate(() => JpReader.turn(-1));
            const back = await waitMessage(page, 'edge', from);
            same(back.forward, false, 'turn before the first page posts edge backward');

            const samples = new Set([1, 2, Math.floor(pages / 3), Math.floor(pages / 2), pages - 1]);
            let previous = 0;
            // A page holding only the picture shows no character: it reports the next one's offset.
            let repeats = 0;
            for (let p = 2; p <= pages; p++) {
                const result = await page.evaluate(() => {
                    JpReader.turn(1);
                    return {state: JpReader.state()};
                });
                same(result.state.page, p, 'page after a turn');
                if (result.state.charOffset === previous) repeats += 1;
                check(result.state.charOffset >= previous && repeats <= 1,
                    `charOffset must grow page by page: ${previous} then ${result.state.charOffset} on page ${p}`);
                previous = result.state.charOffset;
                if (samples.has(p - 1)) {
                    const brute = await page.evaluate(() => __t.firstVisible());
                    same(result.state.charOffset, brute, `page ${p}: charOffset vs the first character on screen, found by walking all`);
                }
            }
            const last = await state(page);
            same([last.page, last.pages, last.endSeen], [pages, pages, true], 'last page');
            from = await mark(page);
            await page.evaluate(() => JpReader.turn(1));
            const forward = await waitMessage(page, 'edge', from);
            same(forward.forward, true, 'turn past the last page posts edge forward');
            same((await state(page)).page, pages, 'no page change at the end');
            const pos = await waitMessage(page, 'pos', 0);
            check(pos.pos.chars === ready.pos.chars, 'pos messages after turns');
            for (let p = pages - 1; p >= 1; p--) await page.evaluate(() => JpReader.turn(-1));
            same((await state(page)).charOffset, 0, 'back at the start');
            note(`${vp.name} ${writing} paged: ${pages} pages for ${ready.pos.chars} characters; open to ready ${openMs} ms; ` +
                `${clip.checked} boxes inside their page`);
            await close(page);
        });
    }

    await test(`${vp.name} positions round-trip (seekChar, then state, then seekChar again)`, async () => {
        for (const writing of ['vertical', 'horizontal']) {
            for (const layout of ['paged', 'scroll']) {
                const {page, ready} = await open(context, 'long', {settings: {writing, layout}});
                const total = ready.pos.chars;
                const next = random(writing.length * 7 + layout.length);
                for (let i = 0; i < 8; i++) {
                    const n = Math.floor(next() * total);
                    const r = await page.evaluate((target) => {
                        JpReader.seekChar(target);
                        const first = JpReader.state();
                        const visible = __t.charVisible(target);
                        JpReader.seekChar(first.charOffset);
                        const second = JpReader.state();
                        return {first, second, visible};
                    }, n);
                    check(r.visible, `${writing} ${layout}: character ${n} not on screen after seekChar(${n})`);
                    check(r.first.charOffset <= n, `${writing} ${layout}: state after seekChar(${n}) is past it: ${r.first.charOffset}`);
                    same([r.second.page, r.second.charOffset], [r.first.page, r.first.charOffset],
                        `${writing} ${layout}: seekChar(${n}) then seekChar(state) moved`);
                }
                const half = await page.evaluate((target) => {
                    JpReader.seekFraction(0.5);
                    return __t.charVisible(target);
                }, Math.round(total / 2));
                check(half, `${writing} ${layout}: seekFraction(0.5) does not show the middle character`);
                await close(page);
            }
        }
    });

    await test(`${vp.name} position kept across font size, text direction, layout and furigana changes, without drift`, async () => {
        const {page, ready, settings} = await open(context, 'long');
        const total = ready.pos.chars;
        // The reader turns a page: its first character becomes the position every re-layout keeps.
        await page.evaluate((n) => {
            JpReader.seekChar(n);
            JpReader.turn(1);
        }, Math.round(total * 0.47));
        const c1 = (await state(page)).charOffset;
        const changes = [
            {fontSize: 27},
            {writing: 'horizontal', tapZones: ZONES.horizontal},
            {layout: 'scroll'},
            {writing: 'horizontal', layout: 'scroll', tapZones: ZONES.horizontal},
            {furigana: 'hide'},
            {lineHeight: 2.2, margins: {top: 40, right: 36, bottom: 40, left: 36}},
        ];
        for (let cycle = 0; cycle < 2; cycle++) {
            for (const change of changes) {
                await apply(page, {...settings, ...change});
                const moved = await page.evaluate((n) => ({state: JpReader.state(), visible: __t.charVisible(n)}), c1);
                check(moved.visible, `${JSON.stringify(change)}: character ${c1} not on screen (state ${moved.state.charOffset})`);
                check(moved.state.charOffset <= c1, `${JSON.stringify(change)}: state ${moved.state.charOffset} past ${c1}`);
                await apply(page, settings);
                same((await state(page)).charOffset, c1, `back from ${JSON.stringify(change)}: drifted`);
            }
        }
        await close(page);
    });

    await test(`${vp.name} start position from jp-init (charOffset, else fraction)`, async () => {
        for (const layout of ['paged', 'scroll']) {
            const byChar = await open(context, 'long', {charOffset: 5000, settings: {layout}});
            check(await byChar.page.evaluate(() => __t.charVisible(5000)), `${layout}: character 5000 not on screen at open`);
            check(byChar.ready.pos.charOffset <= 5000 && byChar.ready.pos.charOffset > 4000, `${layout}: ready charOffset ${byChar.ready.pos.charOffset}`);
            await close(byChar.page);
            const byFraction = await open(context, 'long', {fraction: 0.5, settings: {layout, writing: 'horizontal'}});
            const middle = Math.round(byFraction.ready.pos.chars / 2);
            check(await byFraction.page.evaluate((n) => __t.charVisible(n), middle), `${layout}: middle character not on screen at fraction 0.5`);
            await close(byFraction.page);
        }
    });

    await test(`${vp.name} short chapter fits one page`, async () => {
        for (const layout of ['paged', 'scroll']) {
            const {page, ready} = await open(context, 'short', {settings: {layout}});
            same([ready.pos.pages, ready.pos.fits, ready.pos.endSeen, ready.pos.page], [1, true, true, 1], `${layout}: short chapter`);
            let from = await mark(page);
            await page.evaluate(() => JpReader.turn(1));
            same((await waitMessage(page, 'edge', from)).forward, true, `${layout}: edge forward`);
            from = await mark(page);
            await page.evaluate(() => JpReader.turn(-1));
            same((await waitMessage(page, 'edge', from)).forward, false, `${layout}: edge backward`);
            await close(page);
        }
    });
}

async function scrollTests(context, vp) {
    // Scroll progress along the reading direction: vertical text scrolls right to left, to negative scrollX.
    const progress = (page) => page.evaluate(() => Math.abs(window.scrollX) + window.scrollY);
    const axis = (page) => page.evaluate(() => ({x: window.scrollX, y: window.scrollY}));
    // The first pos since message `from` whose charOffset is past `after`.
    const waitPos = (page, from, after, timeout = 3000) => page.waitForFunction(
        ([index, least]) => window.__jpMessages.slice(index).find((m) => m.t === 'pos' && m.pos.charOffset > least) || null,
        [from, after], {timeout}).then((h) => h.jsonValue());

    for (const writing of ['vertical', 'horizontal']) {
        const forwardSign = writing === 'vertical' ? 'scrollX falls below 0' : 'scrollY grows';
        await test(`${vp.name} ${writing} scroll: direction, turn by 90% of a screen, edges at both ends`, async () => {
            const {page, ready} = await open(context, 'long', {settings: {writing, layout: 'scroll'}});
            same(await axis(page), {x: 0, y: 0}, 'starts at the chapter start');
            same([ready.pos.charOffset, ready.pos.page, ready.pos.endSeen], [0, 1, false], 'ready at the start');
            let from = await mark(page);
            await page.evaluate(() => JpReader.turn(1));
            const pos = await waitMessage(page, 'pos', from);
            const at = await axis(page);
            if (writing === 'vertical') check(at.x < 0 && at.y === 0, `vertical text scrolls right to left (${forwardSign}): ${JSON.stringify(at)}`);
            else check(at.y > 0 && at.x === 0, `horizontal text scrolls down: ${JSON.stringify(at)}`);
            const screen = writing === 'vertical' ? vp.width : vp.height - BASE.insets.top - BASE.insets.bottom;
            const moved = await progress(page);
            check(Math.abs(moved - Math.round(screen * 0.9)) <= 1, `a turn scrolls 90% of a screen: ${moved} of ${screen}`);
            check(pos.pos.charOffset > 0 && pos.pos.pages === ready.pos.pages, `pos after a turn: ${JSON.stringify(pos.pos)}`);
            check(await page.evaluate((n) => __t.charVisible(n), pos.pos.charOffset), 'the reported first character is on screen');

            let turns = 0;
            let edge = null;
            let last = pos.pos.charOffset;
            while (!edge && turns < 500) {
                from = await mark(page);
                const now = await page.evaluate(() => {
                    JpReader.turn(1);
                    return JpReader.state();
                });
                turns += 1;
                const got = await messagesSince(page, from);
                edge = got.find((m) => m.t === 'edge') || null;
                check(now.charOffset >= last, `charOffset went back on a forward turn: ${last} then ${now.charOffset}`);
                last = now.charOffset;
            }
            check(edge && edge.forward === true, 'edge forward at the end of the scroll');
            const end = await state(page);
            check(end.endSeen && end.page === end.pages && end.charOffset > end.chars * 0.9, `end state: ${JSON.stringify(end)}`);
            const max = await page.evaluate(() => document.documentElement.scrollWidth - innerWidth + document.documentElement.scrollHeight - innerHeight);
            check(Math.abs((await progress(page)) - max) <= 1, 'the last turn reaches the very end of the scroll');
            note(`${vp.name} ${writing} scroll: ${ready.pos.pages} screens, ${turns - 1} turns to the end`);

            for (let i = 0; i < turns + 2; i++) await page.evaluate(() => JpReader.turn(-1));
            same(await axis(page), {x: 0, y: 0}, 'turns back to the very start');
            same((await state(page)).charOffset, 0, 'charOffset 0 at the start');
            from = await mark(page);
            await page.evaluate(() => JpReader.turn(-1));
            same((await waitMessage(page, 'edge', from)).forward, false, 'edge backward at the start');
            await close(page);
        });

        await test(`${vp.name} ${writing} scroll: native touch scrolling with momentum, taps, pulls past the ends`, async () => {
            const {page, settings, ready} = await open(context, 'long', {settings: {writing, layout: 'scroll'}});
            const cdp = await page.context().newCDPSession(page);
            // A finger on the glass, through the browser's own touch input (so the scroll and its fling
            // are Chromium's): forward is rightward in vertical text and upward in horizontal text.
            // A drag holds still before lifting (no fling); a fling lifts while still moving fast.
            const gesture = async (distance, fling) => {
                const steps = fling ? 6 : 15;
                const at = (i) => writing === 'vertical'
                    ? {x: vp.width / 2 - distance / 2 + (distance * i) / steps, y: vp.height / 2}
                    : {x: vp.width / 2, y: vp.height / 2 + distance / 2 - (distance * i) / steps};
                await cdp.send('Input.dispatchTouchEvent', {type: 'touchStart', touchPoints: [at(0)]});
                for (let i = 1; i <= steps; i++) {
                    await cdp.send('Input.dispatchTouchEvent', {type: 'touchMove', touchPoints: [at(i)]});
                    await page.waitForTimeout(fling ? 8 : 16);
                }
                for (let t = 0; !fling && t < 200; t += 16) {
                    await cdp.send('Input.dispatchTouchEvent', {type: 'touchMove', touchPoints: [at(steps)]});
                    await page.waitForTimeout(16);
                }
                await cdp.send('Input.dispatchTouchEvent', {type: 'touchEnd', touchPoints: []});
            };

            let from = await mark(page);
            await gesture(300, false);
            let pos = await waitPos(page, from, 0);
            const dragged = await progress(page);
            // Chromium keeps the first few pixels of a touch as slop, as on Android.
            check(dragged >= 260 && dragged <= 305, `a 300 px drag without fling scrolled ${dragged} px`);
            check(writing === 'vertical' ? (await axis(page)).x < 0 : (await axis(page)).y > 0, `the drag scrolls forward (${forwardSign})`);
            check(await page.evaluate((n) => __t.charVisible(n), pos.pos.charOffset), `pos after a drag names a character on screen: ${pos.pos.charOffset}`);
            let got = await messagesSince(page, from);
            same(got.filter((m) => m.t === 'tap' || m.t === 'edge').length, 0, 'a drag is neither a tap nor an edge');
            check(got.some((m) => m.t === 'touch'), 'touch posted for a drag');

            from = await mark(page);
            await gesture(300, true);
            pos = await waitPos(page, from, pos.pos.charOffset);
            await settle(page, 400);
            const flung = (await progress(page)) - dragged;
            check(flung > 450, `a 300 px fling carried on (momentum): scrolled ${flung} px`);
            note(`${vp.name} ${writing} scroll: a 300 px touch drag scrolls ${dragged} px, a 300 px fling ${Math.round(flung)} px`);

            // The reader's own scroll is the position a re-layout keeps.
            const kept = (await state(page)).charOffset;
            await apply(page, {...settings, fontSize: 25});
            check(await page.evaluate((n) => __t.charVisible(n), kept), `after a fling and a font change, character ${kept} is off screen`);
            same((await state(page)).charOffset <= kept, true, 'state after the font change is not past the kept character');

            const point = await page.evaluate(() => {
                const r = __t.rect(JpReader.state().charOffset + 3);
                return {x: (r.left + r.right) / 2, y: (r.top + r.bottom) / 2};
            });
            await page.evaluate(() => {
                window.__lookups = 0;
                JpReader.onTextTap = () => { window.__lookups += 1; return true; };
            });
            from = await mark(page);
            await page.touchscreen.tap(point.x, point.y);
            await settle(page, 200);
            same([await page.evaluate(() => window.__lookups), (await messagesSince(page, from)).filter((m) => m.t === 'tap').length],
                [1, 0], 'scroll mode: a tap on a character looks up');
            from = await mark(page);
            // Mid-chapter, horizontal text scrolls under the top margin; its side margins stay.
            const margin = writing === 'vertical'
                ? {x: vp.width / 2, y: (BASE.insets.top + BASE.margins.top) / 2, action: 'menu'}
                : {x: BASE.margins.left / 2, y: vp.height / 2, action: 'back'};
            await page.touchscreen.tap(margin.x, margin.y);
            same((await waitMessage(page, 'tap', from)).action, margin.action, 'scroll mode: a tap in the margin follows the zones');

            // A pull past an end asks for the next or previous chapter; one inside the chapter does not.
            from = await mark(page);
            await gesture(150, false);
            await settle(page, 300);
            same((await messagesSince(page, from)).filter((m) => m.t === 'edge').length, 0, 'no edge for a drag inside the chapter');
            await page.evaluate(() => JpReader.seekFraction(1));
            await settle(page, 300);
            check((await state(page)).endSeen, 'seekFraction(1) shows the end');
            from = await mark(page);
            await gesture(150, false);
            same((await waitMessage(page, 'edge', from)).forward, true, 'a pull past the end posts edge forward');
            await page.evaluate(() => JpReader.seekChar(0));
            await settle(page, 300);
            from = await mark(page);
            await gesture(-150, false);
            same((await waitMessage(page, 'edge', from)).forward, false, 'a pull past the start posts edge backward');
            await cdp.detach();

            // Auto-scroll runs in the reading direction and stops with an edge at the end.
            await page.evaluate((n) => JpReader.seekChar(n), Math.round(ready.pos.chars / 2));
            await settle(page, 300);
            const before = await axis(page);
            await page.evaluate(() => JpReader.autoScroll(3));
            await page.waitForTimeout(600);
            await page.evaluate(() => JpReader.autoScroll(0));
            const after = await axis(page);
            const ran = writing === 'vertical' ? before.x - after.x : after.y - before.y;
            check(ran > 30, `auto-scroll at 3 px a frame moved ${ran} px forward in 600 ms (${JSON.stringify(before)} to ${JSON.stringify(after)})`);
            await settle(page, 100);
            same(await axis(page), after, 'autoScroll(0) stops');
            await page.evaluate((n) => JpReader.seekChar(n), ready.pos.chars - 30);
            await settle(page, 300);
            from = await mark(page);
            await page.evaluate(() => JpReader.autoScroll(20));
            same((await waitMessage(page, 'edge', from, 5000)).forward, true, 'auto-scroll reaching the end posts edge forward');
            check((await state(page)).endSeen, 'auto-scroll stops at the end');

            // A wheel (a mouse or a keyboard on the tablet) is the reader's own scroll too.
            await page.evaluate(() => JpReader.seekChar(0));
            await settle(page, 300);
            from = await mark(page);
            await page.mouse.move(vp.width / 2, vp.height / 2);
            await page.mouse.wheel(writing === 'vertical' ? -1500 : 0, writing === 'vertical' ? 0 : 1500);
            pos = await waitPos(page, from, 0);
            check(await page.evaluate((n) => __t.charVisible(n), pos.pos.charOffset), 'pos after a wheel names a character on screen');
            if (writing === 'vertical') {
                // A touchpad's or mouse wheel's downward turn reads on, leftward.
                const x0 = (await axis(page)).x;
                await page.mouse.wheel(0, 400);
                await settle(page, 300);
                // (Playwright's wheel of 400 arrives as deltaY 200 at device pixel ratio 2.)
                check((await axis(page)).x <= x0 - 150, `a downward wheel scrolls vertical text forward: ${x0} to ${(await axis(page)).x}`);
            }
            await close(page);
        });
    }
}

async function furiganaTests(context, vp) {
    await test(`${vp.name} furigana modes (computed styles)`, async () => {
        const {page, settings} = await open(context, 'medium');
        const expected = {
            show: {visibility: 'visible', color: 'rgb(34, 34, 34)', display: 'ruby-text'},
            partial: {visibility: 'visible', color: 'rgb(153, 153, 153)', display: 'ruby-text'},
            full: {visibility: 'hidden', color: 'rgb(34, 34, 34)', display: 'ruby-text'},
            toggle: {visibility: 'hidden', color: 'rgb(34, 34, 34)', display: 'ruby-text'},
            hide: {visibility: 'visible', color: 'rgb(34, 34, 34)', display: 'none'},
        };
        for (const [mode, style] of Object.entries(expected)) {
            await apply(page, {...settings, furigana: mode});
            const got = await page.evaluate(() => {
                const rt = document.querySelector('#jp-chapter rt');
                const cs = getComputedStyle(rt);
                return {visibility: cs.visibility, color: cs.color, display: cs.display, userSelect: cs.userSelect,
                    root: document.documentElement.className};
            });
            same({visibility: got.visibility, color: got.color, display: got.display}, style, `rt style in ${mode}`);
            same(got.userSelect, 'none', 'rt is never selectable');
            check(got.root.includes(`jp-furi-${mode}`), `root class for ${mode}: ${got.root}`);
        }
        await close(page);
    });

    await test(`${vp.name} a tap on hidden furigana reveals it and posts no tap`, async () => {
        for (const mode of ['full', 'partial', 'toggle']) {
            const {page} = await open(context, 'medium', {settings: {furigana: mode}});
            await page.evaluate(() => {
                window.__lookups = 0;
                JpReader.onTextTap = () => { window.__lookups += 1; return true; };
                JpReader.seekChar(__t.rubyIndex(0));
            });
            const point = await page.evaluate(() => __t.rubyPoint(0));
            check(point, `${mode}: first ruby not on screen after seeking to it`);
            let from = await mark(page);
            await page.touchscreen.tap(point.x, point.y);
            await settle(page, 200);
            const first = await page.evaluate(() => {
                const ruby = document.querySelectorAll('#jp-chapter ruby')[0];
                const cs = getComputedStyle(ruby.querySelector('rt'));
                return {revealed: ruby.classList.contains('jp-reveal-rt'), visibility: cs.visibility, color: cs.color, lookups: window.__lookups};
            });
            let taps = (await messagesSince(page, from)).filter((m) => m.t === 'tap');
            same([first.revealed, first.lookups, taps.length], [true, 0, 0], `${mode}: first tap reveals, no lookup, no tap message`);
            if (mode === 'partial') same(first.color, 'rgb(34, 34, 34)', 'partial: revealed reading in the text colour');
            else same(first.visibility, 'visible', `${mode}: revealed reading visible`);
            from = await mark(page);
            await page.touchscreen.tap(point.x, point.y);
            await settle(page, 200);
            const second = await page.evaluate(() => ({
                revealed: document.querySelectorAll('#jp-chapter ruby')[0].classList.contains('jp-reveal-rt'),
                lookups: window.__lookups,
            }));
            taps = (await messagesSince(page, from)).filter((m) => m.t === 'tap');
            if (mode === 'toggle') same([second.revealed, second.lookups, taps.length], [false, 0, 0], 'toggle: second tap hides again');
            else same([second.revealed, second.lookups, taps.length], [true, 1, 0], `${mode}: second tap looks up`);
            await close(page);
        }
    });
}

async function tapTests(context, vp) {
    await test(`${vp.name} taps: character to onTextTap, margins to zones, long press, touch`, async () => {
        const {page, settings} = await open(context, 'medium');
        const point = await page.evaluate(() => __t.charPoint());
        check(point, 'no plain character on screen');
        const range = await page.evaluate(([x, y]) => {
            const r = JpReader.charRangeAt(x, y);
            return r ? r.toString() : null;
        }, [point.x, point.y]);
        check(range && range.length >= 1 && range.length <= 2, `charRangeAt on a character: ${range}`);
        const marginY = (BASE.insets.top + BASE.margins.top) / 2;
        same(await page.evaluate(([x, y]) => JpReader.charRangeAt(x, y), [vp.width / 2, marginY]), null, 'charRangeAt in the top margin');

        await page.evaluate(() => {
            window.__lookups = [];
            JpReader.onTextTap = (x, y) => { window.__lookups.push([x, y]); return true; };
        });
        let from = await mark(page);
        await page.touchscreen.tap(point.x, point.y);
        await settle(page, 150);
        let lookups = await page.evaluate(() => window.__lookups);
        let got = await messagesSince(page, from);
        same(lookups.length, 1, 'onTextTap called once for a tap on a character');
        check(Math.abs(lookups[0][0] - point.x) <= 1 && Math.abs(lookups[0][1] - point.y) <= 1, `onTextTap gets client px: ${lookups[0]}`);
        same(got.filter((m) => m.t === 'tap').length, 0, 'no tap message when onTextTap handled it');
        check(got.some((m) => m.t === 'touch'), 'touch posted on touch start');

        await page.evaluate(() => { JpReader.onTextTap = () => false; });
        from = await mark(page);
        await page.touchscreen.tap(point.x, point.y);
        let tap = await waitMessage(page, 'tap', from);
        same(tap.action, 'none', 'a tap on a character that onTextTap declines');
        check(Math.abs(tap.x - point.x / vp.width) < 0.01 && Math.abs(tap.y - point.y / vp.height) < 0.01, `tap x, y are fractions: ${JSON.stringify(tap)}`);

        await page.evaluate(() => { JpReader.onTextTap = null; });
        from = await mark(page);
        await page.touchscreen.tap(point.x, point.y);
        same((await waitMessage(page, 'tap', from)).action, 'none', 'a tap on a character with no lookup hook');

        for (const [fx, action] of [[0.5, 'menu'], [0.12, 'forward'], [0.88, 'back']]) {
            from = await mark(page);
            await page.touchscreen.tap(vp.width * fx, marginY);
            tap = await waitMessage(page, 'tap', from);
            same(tap.action, action, `a tap in the top margin at x ${fx}`);
        }

        await apply(page, {...settings, tapMode: 'zones'});
        await page.evaluate(() => {
            window.__lookups = [];
            JpReader.onTextTap = () => { window.__lookups.push(1); return true; };
        });
        from = await mark(page);
        await page.touchscreen.tap(point.x, point.y);
        tap = await waitMessage(page, 'tap', from);
        const zone = point.x / vp.width < 0.3 ? 'forward' : point.x / vp.width > 0.7 ? 'back' : 'menu';
        same([tap.action, (await page.evaluate(() => window.__lookups)).length], [zone, 0], 'zones mode: a tap on a character follows the zone');

        from = await mark(page);
        await longPress(page, {x: vp.width / 2, y: marginY});
        await settle(page, 150);
        got = await messagesSince(page, from);
        same(got.filter((m) => m.t === 'tap').length, 0, 'a long press is not a tap');
        await close(page);
    });

    for (const writing of ['vertical', 'horizontal']) {
        await test(`${vp.name} ${writing} swipes turn pages (and invertSwipe flips them)`, async () => {
            const {page, settings} = await open(context, 'medium', {settings: {writing}});
            const y = vp.height / 2;
            const next = writing === 'vertical' ? [{x: 80, y}, {x: 300, y}] : [{x: 300, y}, {x: 80, y}];
            const previous = [next[1], next[0]];
            await swipe(page, ...next);
            same((await state(page)).page, 2, `${writing}: swipe ${writing === 'vertical' ? 'right' : 'left'} is the next page`);
            await swipe(page, ...previous);
            same((await state(page)).page, 1, `${writing}: the other way is the previous page`);
            let from = await mark(page);
            await swipe(page, ...previous);
            same((await waitMessage(page, 'edge', from)).forward, false, `${writing}: a swipe back on the first page posts edge`);
            await apply(page, {...settings, invertSwipe: true});
            await swipe(page, ...previous);
            same((await state(page)).page, 2, `${writing}: invertSwipe flips the direction`);
            from = await mark(page);
            await swipe(page, {x: 150, y}, {x: 175, y}, 3);
            await settle(page, 150);
            const got = await messagesSince(page, from);
            same([(await state(page)).page, got.filter((m) => m.t === 'tap').length], [2, 0], `${writing}: a short drag is neither a swipe nor a tap`);
            await close(page);
        });
    }
}

async function readAloudTests(context, vp) {
    await test(`${vp.name} paragraphs(), highlight() and firstVisibleParagraph()`, async () => {
        for (const key of ['counting', 'long']) {
            const {page} = await open(context, key);
            same(await page.evaluate(() => JpReader.paragraphs()), CHAPTERS[key].paragraphs, `${key}: paragraphs() vs upstream's rule`);
            await close(page);
        }
        for (const layout of ['paged', 'scroll']) {
            const {page} = await open(context, 'long', {settings: {layout}});
            const texts = CHAPTERS.long.paragraphs;
            same(await page.evaluate(() => JpReader.firstVisibleParagraph(0, 0)), 0, `${layout}: first visible paragraph at the start`);
            const target = Math.floor(texts.length * 0.6);
            let from = await mark(page);
            await page.evaluate((i) => JpReader.highlight(i, -1), target);
            const pos = await waitMessage(page, 'pos', from);
            const shown = await page.evaluate(() => ({visible: __t.highlightVisible(), text: __t.highlightText()}));
            check(shown.visible, `${layout}: highlight did not turn to paragraph ${target} (pos ${JSON.stringify(pos.pos)})`);
            same(shown.text, texts[target].replace(/\s/g, ''), `${layout}: highlighted text`);
            const first = await page.evaluate(() => JpReader.firstVisibleParagraph(0, 0));
            check(first >= 0 && first <= target, `${layout}: firstVisibleParagraph ${first} after turning to ${target}`);
            await page.evaluate(([i]) => JpReader.highlight(i, 2, 7), [target]);
            same(await page.evaluate(() => __t.highlightText()), texts[target].slice(2, 7).replace(/\s/g, ''), `${layout}: part of a paragraph`);
            await page.evaluate(() => JpReader.highlight(-1));
            same(await page.evaluate(() => CSS.highlights.has('jp-tts')), false, `${layout}: highlight(-1) clears`);
            await close(page);
        }
    });
}

async function typographyTests(context, vp) {
    await test(`${vp.name} upright digits and !? in vertical text (tate-chu-yoko)`, async () => {
        const {page, settings} = await open(context, 'counting');
        const wrapped = await page.evaluate(() => [...document.querySelectorAll('.jp-tcy')].map((e) => ({
            text: e.textContent, inRuby: !!e.closest('ruby'), combine: getComputedStyle(e).textCombineUpright,
        })));
        same(wrapped.map((w) => w.text), ['!?', '12', '!?'], 'wrapped runs (789, 2026, 100, !!!, 3.14 and ruby text stay as they are)');
        check(wrapped.every((w) => !w.inRuby && w.combine === 'all'), `upright in vertical text: ${JSON.stringify(wrapped)}`);
        await apply(page, {...settings, writing: 'horizontal', tapZones: ZONES.horizontal});
        same(await page.evaluate(() => getComputedStyle(document.querySelector('.jp-tcy')).textCombineUpright), 'none', 'not in horizontal text');
        const css = await page.evaluate(() => {
            const cs = getComputedStyle(document.body);
            return {lineBreak: cs.lineBreak, lang: document.documentElement.lang};
        });
        same(css, {lineBreak: 'strict', lang: 'ja'}, 'Japanese line breaking');
        await close(page);
        const long = await open(context, 'long');
        const texts = await long.page.evaluate(() => [...new Set([...document.querySelectorAll('.jp-tcy')].map((e) => e.textContent))].sort());
        same(texts, ['!!', '!?', '12', '3', '7', '?!'], 'wrapped runs in the long chapter');
        await close(long.page);
    });

    await test(`${vp.name} strict Content-Security-Policy: no inline style needed`, async () => {
        const {page, ready} = await open(context, 'medium', {strict: true});
        check(ready.pos.pages > 1, 'laid out under the strict policy');
        const violations = await page.evaluate(() => window.__cspViolations);
        same(violations, [], 'CSP violations');
        await close(page);
    });
}

async function timingTests(context, vp) {
    await test(`${vp.name} re-layout of the 20 000-character chapter within ${RELAYOUT_BUDGET_MS} ms`, async () => {
        for (const [writing, layout] of [['vertical', 'paged'], ['horizontal', 'paged'], ['vertical', 'scroll']]) {
            const {page, settings, ready} = await open(context, 'long', {settings: {writing, layout}});
            await page.evaluate((n) => JpReader.seekChar(n), Math.round(ready.pos.chars * 0.6));
            const sync = [];
            const framed = [];
            for (let i = 0; i < 8; i++) {
                const size = i % 2 === 0 ? 24 : 22;
                const ms = await page.evaluate((s) => new Promise((resolve) => {
                    const t0 = performance.now();
                    JpReader.applySettings(s);
                    const t1 = performance.now();
                    requestAnimationFrame(() => requestAnimationFrame(() => resolve([t1 - t0, performance.now() - t0])));
                }), {...settings, fontSize: size});
                sync.push(ms[0]);
                framed.push(ms[1]);
            }
            await frames(page);
            // A page turn at a reader's pace (every 150 ms): one scroll assignment, within a frame.
            // (Turning every frame instead makes Chromium wait for the last page's tiles, which headless
            // Chrome draws in software; that is a test artefact, not the page's cost.)
            const turns = await page.evaluate(async () => {
                const out = [];
                for (let i = 0; i < 12; i++) {
                    const t0 = performance.now();
                    JpReader.turn(i < 6 ? 1 : -1);
                    out.push(performance.now() - t0);
                    await new Promise((r) => setTimeout(r, 150));
                }
                return out;
            });
            const worst = Math.max(...sync);
            note(`${vp.name} ${writing} ${layout}: font-size re-layout ${median(sync).toFixed(1)} ms median, ${worst.toFixed(1)} ms max ` +
                `(${median(framed).toFixed(1)} ms to the second frame); turn() ${median(turns).toFixed(2)} ms median, ` +
                `${Math.max(...turns).toFixed(2)} ms max`);
            check(worst < RELAYOUT_BUDGET_MS, `${writing} ${layout}: re-layout took ${worst.toFixed(1)} ms`);
            check(Math.max(...turns) < 16, `${writing} ${layout}: a turn took ${Math.max(...turns).toFixed(1)} ms, more than a frame`);
            await close(page);
        }
    });
}

async function readyOnceTest(context, vp) {
    await test(`${vp.name} ready posted once; every message has the contract's shape`, async () => {
        const {page, settings} = await open(context, 'medium');
        await apply(page, {...settings, fontSize: 26});
        await page.evaluate(() => JpReader.turn(1));
        await settle(page, 300);
        const got = await page.evaluate(() => window.__jpMessages);
        same(got.filter((m) => m.t === 'ready').length, 1, 'ready messages');
        await close(page);
    });
}

function checkShapes() {
    const POS = ['charOffset', 'chars', 'fraction', 'page', 'pages', 'fits', 'endSeen'];
    const bad = [];
    for (const m of allMessages) {
        const keys = Object.keys(m).sort().join(',');
        if (m.t === 'ready' || m.t === 'pos') {
            const p = m.pos || {};
            const ok = keys === 'pos,t' && Object.keys(p).sort().join(',') === [...POS].sort().join(',') &&
                Number.isInteger(p.charOffset) && Number.isInteger(p.chars) && p.charOffset >= 0 && p.charOffset <= p.chars &&
                p.fraction >= 0 && p.fraction <= 1 && Number.isInteger(p.page) && Number.isInteger(p.pages) &&
                p.page >= 1 && p.page <= p.pages && typeof p.fits === 'boolean' && typeof p.endSeen === 'boolean';
            if (!ok) bad.push(m);
        } else if (m.t === 'tap') {
            if (keys !== 'action,t,x,y' || !(m.x >= 0 && m.x <= 1 && m.y >= 0 && m.y <= 1) ||
                !['menu', 'back', 'forward', 'none'].includes(m.action)) bad.push(m);
        } else if (m.t === 'edge') {
            if (keys !== 'forward,t' || typeof m.forward !== 'boolean') bad.push(m);
        } else if (m.t === 'touch') {
            if (keys !== 't') bad.push(m);
        } else {
            bad.push(m);
        }
    }
    return bad;
}

// --- main -----------------------------------------------------------------------------------------

async function launch() {
    if (process.env.REIKAI_CHROME) return chromium.launch({executablePath: process.env.REIKAI_CHROME});
    try {
        return await chromium.launch({channel: 'chrome'});
    } catch (chromeError) {
        try {
            return await chromium.launch();
        } catch (chromiumError) {
            throw new Error(`no browser: Google Chrome (${chromeError.message.split('\n')[0]}) and Playwright's Chromium ` +
                `(${chromiumError.message.split('\n')[0]}); see README.md`);
        }
    }
}

const VIEWPORTS = [
    {name: 'phone', width: 412, height: 915},
    {name: 'tablet', width: 800, height: 1280},
];

const browser = await launch();
say(`browser ${browser.version()}${SLOWDOWN > 1 ? `, CPU slowed ${SLOWDOWN} times` : ''}`);
try {
    for (const vp of VIEWPORTS) {
        const context = await browser.newContext({
            viewport: {width: vp.width, height: vp.height},
            deviceScaleFactor: 2,
            hasTouch: true,
            isMobile: true,
            locale: 'ja-JP',
        });
        await context.route(`${ORIGIN}/**`, serve);
        await context.addInitScript(INIT_SCRIPT);
        if (vp.name === 'phone') await countingTests(context);
        await pagedTests(context, vp);
        await scrollTests(context, vp);
        await furiganaTests(context, vp);
        await tapTests(context, vp);
        await readAloudTests(context, vp);
        await typographyTests(context, vp);
        await timingTests(context, vp);
        await readyOnceTest(context, vp);
        await context.close();
    }
    await test('no page errors, no CSP violations, all messages in the contract\'s shape', async () => {
        same(pageErrors, [], 'page errors');
        const bad = checkShapes();
        check(bad.length === 0, `${bad.length} malformed messages, first: ${JSON.stringify(bad[0])}`);
        note(`${allMessages.length} messages checked`);
    }, true);
} finally {
    await browser.close();
    clearTimeout(timer);
}

console.log('\n--- numbers ---');
for (const line of report) console.log(line);
if (failures.length) {
    console.log(`\n${failures.length} FAILED:`);
    for (const f of failures) console.log(`- ${f}`);
    process.exit(1);
}
console.log('\nall passed');
