/*
 * Reikai JP: the Yomitan update smoke test (roadmap 3.6). GPL-3.0-or-later.
 *
 * Runs the vendored Yomitan the way the app does, in headless Chrome instead of Android WebView:
 * Yomitan's release served at the engine origin, the stand-in injected at document start with a
 * JavaScript copy of the app's hub (hub.mjs), and what WebView lacks taken away (SharedWorker; a
 * worker's own workers are never served). It opens background.html (the engine), waits for the
 * backend to prepare, imports Yomitan's own test dictionary through the settings page's "Import
 * from URL", looks a word up through the app's findTerms path, draws a dictionary picture on the
 * search page, reaches a canned AnkiConnect through the app's network path (binary reply), and
 * fails on any tripwire entry, stand-in error or uncaught page error.
 *
 * The Japanese reader (roadmap 4.3): a chapter page at the chapter origin, built as JpPageViewport
 * builds it (app/src/main/assets/jp-reader, the app's Content-Security-Policy read from
 * JpPageDocument.kt), with the stand-in in content mode and Yomitan's scanner (jp-reikai/reader-scan.js):
 * taps through jp-reader.js find words, sentences and the highlight in vertical text with ruby, and a
 * card added from the lookup sheet's page (popup.html with popup-host.js) gets the book's cover
 * through Anki's {screenshot}, or an empty field without one.
 *
 * Run: scripts/fork/yomitan_bump.py smoke (or node scripts/fork/yomitan-smoke/smoke.mjs), after
 * `npm ci --prefix scripts/fork/yomitan-smoke`. Chrome: $REIKAI_CHROME if set, else the installed
 * Google Chrome (GitHub's runners have it), else Playwright's own Chromium
 * (PLAYWRIGHT_BROWSERS_PATH=<dir> node scripts/fork/yomitan-smoke/node_modules/playwright-core/cli.js
 * install chromium-headless-shell). --assets DIR serves another copy of jp-yomitan's assets (a
 * deliberately broken one, to prove the test fails). REIKAI_SMOKE_SLOWDOWN=N slows every page's CPU
 * N times (Chrome's throttling), to prove locally that a slow runner still passes (D-021).
 *
 * test-dictionary.zip is Yomitan's test/data/dictionaries/valid-dictionary1 (GPL-3.0-or-later,
 * Copyright (C) 2023-2026 Yomitan Authors), zipped; see README.md.
 */
import {readFile} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {join, normalize, sep} from 'node:path';
import {chromium} from 'playwright-core';
import {Hub, ORIGIN} from './hub.mjs';
import {chapterDocument} from '../jp-reader-test/fixtures.mjs';

const HERE = fileURLToPath(new URL('.', import.meta.url));
const ROOT = join(HERE, '../../..');
const argAssets = process.argv.indexOf('--assets');
const ASSETS = argAssets > 0 ? process.argv[argAssets + 1] : join(ROOT, 'jp-yomitan/src/main/assets');
const DICTIONARY = join(HERE, 'test-dictionary.zip');
const DICTIONARY_URL = `${ORIGIN}/__reikai/check/test-dictionary.zip`;
const TIMEOUT_MS = 120_000;
const SLOWDOWN = Number(process.env.REIKAI_SMOKE_SLOWDOWN ?? 1);
const CHAPTER_ORIGIN = 'https://chapter.reikai.invalid';
const READER_ASSETS = join(ROOT, 'app/src/main/assets/jp-reader');

const started = Date.now();
const elapsed = () => `${((Date.now() - started) / 1000).toFixed(1)} s`;
const say = (text) => console.log(`[smoke ${elapsed()}] ${text}`);
/** Everything that makes the run fail, reported together at the end. */
const failures = [];
const fail = (text) => { if (!failures.includes(text)) { failures.push(text); say(`FAIL ${text}`); } };

const timer = setTimeout(() => {
    console.error(`[smoke] FAIL: no result after ${TIMEOUT_MS / 1000} s`);
    process.exit(1);
}, TIMEOUT_MS);

// --- the engine origin, as jp-yomitan's EngineServer serves it --------------------------------------

const MIME = {
    html: 'text/html', js: 'text/javascript', mjs: 'text/javascript', css: 'text/css', json: 'application/json',
    wasm: 'application/wasm', svg: 'image/svg+xml', png: 'image/png', gif: 'image/gif', webp: 'image/webp',
    ttf: 'font/ttf', woff: 'font/woff', woff2: 'font/woff2', mp3: 'audio/mpeg',
    handlebars: 'text/x-handlebars-template', map: 'application/json',
};

async function serve(route) {
    const url = new URL(route.request().url());
    if (url.origin === CHAPTER_ORIGIN) { return serveChapter(route, url); }
    if (url.origin !== ORIGIN) {
        // The smoke test is offline; Yomitan's cross-origin requests go through the hub's fetch.
        say(`blocked a request to ${url.origin}`);
        return route.abort('internetdisconnected');
    }
    const path = url.pathname;
    if (path === '/lib/z-worker.js') {
        // zip.js starting its own worker inside Yomitan's import worker: WebView never serves that.
        // Chrome does not send a worker's worker through this routing either (it then fails to load
        // from the unresolvable .invalid host and the import hangs, as in WebView); this catches it
        // sooner should that change.
        fail('zip.js asked for its worker (lib/z-worker.js): the import worker would hang in WebView');
        return route.fulfill({status: 404, body: ''});
    }
    let file = null;
    if (path === '/__reikai/check/test-dictionary.zip') {
        file = DICTIONARY;
    } else if (!path.includes('..') && !path.endsWith('/')) {
        file = path.startsWith('/__reikai/') ? join(ASSETS, 'jp-reikai', path.slice('/__reikai/'.length)) : join(ASSETS, 'yomitan', path.slice(1));
    }
    const body = file && normalize(file).startsWith(normalize(ASSETS) + sep) || file === DICTIONARY ?
        await readFile(file).catch(() => null) : null;
    if (body === null) {
        return route.fulfill({status: 404, body: '', headers: {'Access-Control-Allow-Origin': ORIGIN}});
    }
    const ext = path.slice(path.lastIndexOf('.') + 1).toLowerCase();
    return route.fulfill({
        status: 200,
        body,
        contentType: file === DICTIONARY ? 'application/zip' : (MIME[ext] ?? 'application/octet-stream'),
        headers: {'Cache-Control': 'no-cache', 'Access-Control-Allow-Origin': '*'},
    });
}

// --- the chapter origin, as JpPageClient serves it ---------------------------------------------------

/**
 * JpPageDocument.CONTENT_SECURITY_POLICY, read from the Kotlin source so the two never drift: the
 * chapter page runs only its own script and Yomitan's.
 */
async function chapterPolicy() {
    const kotlin = await readFile(join(ROOT, 'app/src/main/java/jp/reikai/reader/page/JpPageDocument.kt'), 'utf8');
    const constants = Object.fromEntries([...kotlin.matchAll(/const val (\w+) = "([^"$]*)"/g)].map((m) => [m[1], m[2]]));
    const list = /CONTENT_SECURITY_POLICY: String = listOf\(([\s\S]*?)\)\.joinToString\("; "\)/.exec(kotlin);
    if (!list || !constants.IMAGE_ORIGIN || !constants.YOMITAN_ORIGIN) { throw new Error('JpPageDocument.kt: its Content-Security-Policy is no longer where this test reads it'); }
    return [...list[1].matchAll(/"([^"]*)"/g)]
        .map((m) => m[1].replace(/\$(\w+)/g, (_, name) => constants[name] ?? `$${name}`))
        .join('; ');
}
const CHAPTER_CSP = await chapterPolicy();
if (!CHAPTER_CSP.includes(`script-src 'self' ${ORIGIN}`)) { throw new Error(`unexpected chapter policy: ${CHAPTER_CSP}`); }

/** Sentences over the test dictionary's words, vertical text with ruby (打 with its okurigana outside). */
const CHAPTER = {
    id: 1,
    html: [
        '<h1 class="jp-title">第一章</h1>',
        '<p>朝の光が窓から差し込み、机の上の古い本を静かに照らしていた。</p>',
        '<p>彼は古い<ruby>画像<rt>がぞう</rt></ruby>を見て、<ruby>打<rt>う</rt></ruby>ち込んだ。それから本を読む。</p>',
    ].join('\n'),
};
const CHAPTER_URL = `${CHAPTER_ORIGIN}/chapter/1-1`;
const CHAPTER_SETTINGS = {
    writing: 'vertical', layout: 'paged', furigana: 'show', tapMode: 'lookup', tapZones: [[0, 0, 1, 1, 'menu']],
    fontFamily: 'serif', fontSize: 22, lineHeight: 1.8, margins: {top: 24, right: 24, bottom: 24, left: 24},
    insets: {top: 0, bottom: 0}, colors: {background: '#ffffff', text: '#000000', hint: '#888888'},
    textIndent: 1, justify: true, invertSwipe: false,
};
/** The document JpPageDocument builds, with Yomitan's scanner loaded after the page script (4.3). */
const CHAPTER_HTML = chapterDocument(CHAPTER, {charOffset: 0, fraction: 0, settings: CHAPTER_SETTINGS})
    .replace('</body>', `<script type="module" src="${ORIGIN}/__reikai/reader-scan.js"></script></body>`);

async function serveChapter(route, url) {
    const path = url.pathname;
    if (path === new URL(CHAPTER_URL).pathname) {
        return route.fulfill({status: 200, body: CHAPTER_HTML, contentType: 'text/html; charset=utf-8',
            headers: {'Content-Security-Policy': CHAPTER_CSP, 'Cache-Control': 'no-store'}});
    }
    const name = path.startsWith('/jp-reader/') ? path.slice('/jp-reader/'.length) : '';
    const body = /^[\w.-]+$/.test(name) ? await readFile(join(READER_ASSETS, name)).catch(() => null) : null;
    if (body === null) { return route.fulfill({status: 404, body: ''}); }
    return route.fulfill({status: 200, body, contentType: name.endsWith('.css') ? 'text/css' : 'text/javascript'});
}

// --- the app's side: the hub and its host ---------------------------------------------------------

/**
 * Yomitan's own race, not the stand-in's: the settings page's popup preview sends a setting to its
 * popup frame before that frame has connected, and drops the rejection (`void popup.setCustomCss`,
 * js/pages/settings/popup-preview-frame.js). Seen in 4 of 20 runs with pages slowed 6 times
 * (REIKAI_SMOKE_SLOWDOWN=6), never at normal speed; harmless in the app.
 */
const knownRace = (text) => /Failed to invoke action displaySetCustom(?:Outer)?Css: frame state invalid/.test(text);

const tripwire = [];
let ankiVersionAsked = false;
let backendReady;
const backendReadyPromise = new Promise((resolve) => { backendReady = resolve; });

/** What the canned AnkiConnect was given: media files and notes, in order. */
const anki = {media: [], notes: []};
/**
 * A canned AnkiConnect (the app answers it from AnkiDroid): a deck, a Lapis-like note type, nothing
 * added yet. Yomitan asks with API version 2, which AnkiConnect answers with the bare result.
 */
function ankiConnect(action, params) {
    switch (action) {
        case 'version': return 6;
        case 'deckNames': return ['Reikai'];
        case 'modelNames': return ['Lapis'];
        case 'modelFieldNames': return ['Expression', 'Sentence', 'Picture', 'MiscInfo'];
        case 'canAddNotes': return (params.notes ?? []).map(() => true);
        case 'canAddNotesWithErrorDetail': return (params.notes ?? []).map(() => ({canAdd: true}));
        case 'findNotes': case 'findCards': return [];
        case 'notesInfo': case 'cardsInfo': return [];
        case 'storeMediaFile': anki.media.push({filename: params.filename, data: params.data}); return params.filename;
        case 'addNote': anki.notes.push(params.note); return 1000 + anki.notes.length;
        case 'multi': return (params.actions ?? []).map((a) => ankiConnect(a.action, a.params ?? {}));
        default: say(`canned AnkiConnect: ${action} answered with null`); return null;
    }
}

/** The lookup sheet's picture for Anki's {screenshot} (the book's cover), by popup tab; the app's YomitanPopup. */
const covers = new Map();

const hub = new Hub({
    storage: new Map(),
    openPage: (how, url) => { say(`Yomitan asked to open (${how}) ${url}`); return null; },
    fetch: (request) => {
        const call = request.url.startsWith('http://127.0.0.1:8765') && request.method === 'POST' ? JSON.parse(request.body ?? '{}') : null;
        if (call !== null) {
            // Yomitan's POST in, a binary reply out: the device's path for Anki and audio.
            if (call.action === 'version') { ankiVersionAsked = call.version === 2; }
            const result = ankiConnect(call.action, call.params ?? {});
            return {status: 200, statusText: 'OK', headers: {'content-type': 'application/json'}, body: Buffer.from(JSON.stringify(result))};
        }
        say(`Yomitan fetched ${request.method} ${request.url} (offline here)`);
        return {failed: 'offline in the smoke test'};
    },
    capture: (tabId) => covers.get(tabId) ?? null,
    onBackendReady: () => backendReady(),
    onTripwire: (path, called, url) => {
        const entry = `${called ? 'called ' : ''}${path} (${url.replace(ORIGIN, '')})`;
        if (!tripwire.includes(entry)) { tripwire.push(entry); fail(`tripwire: ${entry}`); }
    },
    log: (level, text) => {
        if (level !== 'E') {
            say(`log ${level} ${text}`);
        } else if (knownRace(text)) {
            say(`ignored Yomitan's own settings-preview race: ${text.split('\n')[0]}`);
        } else {
            fail(`stand-in reported: ${text}`);
        }
    },
});

// --- one browser page per WebView -----------------------------------------------------------------

const STAND_IN = await readFile(join(ASSETS, 'jp-reikai/stand-in.js'), 'utf8');
const POPUP_HOST = await readFile(join(ASSETS, 'jp-reikai/popup-host.js'), 'utf8');
const MANIFEST = JSON.parse(await readFile(join(ASSETS, 'yomitan/manifest.json'), 'utf8'));

/** What WebView gives a page of the engine origin (or a reader's chapter origin) before any of its
 * scripts: no SharedWorker, the web message listener's object (addWebMessageListener), then the
 * stand-in (addDocumentStartJavaScript). */
const documentStart = (kind, origins = [ORIGIN]) => `(() => {
    if (!${JSON.stringify(origins)}.includes(location.origin)) { return; }
    delete globalThis.SharedWorker;
    const post = globalThis.__reikaiPost;
    const doc = Math.random().toString(36).slice(2) + Date.now().toString(36);
    let seq = 0;
    const target = new EventTarget();
    const hub = {
        onmessage: null,
        postMessage: (data) => { void post(doc, seq++, String(data)); },
        addEventListener: (type, fn, options) => target.addEventListener(type, fn, options),
        removeEventListener: (type, fn, options) => target.removeEventListener(type, fn, options),
    };
    Object.defineProperty(globalThis, '__reikaiReceive', {value: (to, list) => {
        if (to !== doc) { return false; }
        for (const item of list) {
            // A binary message arrives as {b64}; WebView hands the page an ArrayBuffer.
            const data = typeof item === 'string' ? item : Uint8Array.from(atob(item.b64), (c) => c.charCodeAt(0)).buffer;
            const event = new MessageEvent('message', {data});
            target.dispatchEvent(event);
            hub.onmessage?.(event);
        }
        return true;
    }});
    globalThis.reikaiHub = hub;
})();
${STAND_IN}
(${JSON.stringify({origin: ORIGIN, manifest: MANIFEST, kind, debug: false})});
`;

/** A document's end of the hub: messages queue and reach the page in order, a batch at a time. */
class PagePort {
    constructor(frame, doc) {
        this.frame = frame;
        this.doc = doc;
        this.queue = [];
        this.flushing = false;
        this.alive = true;
        /** Like a device whose WebView has WEB_MESSAGE_ARRAY_BUFFER: response bodies go as binary messages. */
        this.binary = true;
    }

    post(text) {
        if (!this.alive) { return false; }
        this.queue.push(text);
        if (!this.flushing) { void this.flush(); }
        return true;
    }

    postBytes(bytes) {
        return this.post({b64: Buffer.from(bytes).toString('base64')});
    }

    async flush() {
        this.flushing = true;
        while (this.queue.length > 0 && this.alive) {
            const batch = this.queue.splice(0);
            const delivered = await this.frame.evaluate(([to, list]) => globalThis.__reikaiReceive?.(to, list) ?? false, [this.doc, batch])
                .catch(() => false);
            if (!delivered) {
                // The document is gone (navigated away or closed): the hub forgets it, as it does on a failed post.
                this.alive = false;
                const hubDoc = hub.docs.get(this.doc);
                if (hubDoc) { hub.dead.push(hubDoc); hub.flushDead(); }
            }
        }
        this.flushing = false;
    }
}


/**
 * One WebView: a page with the hub's document start. `options`: origins the stand-in is injected into
 * (the engine's by default), `init` a script after it (another listener's object, a host), `bindings`
 * the functions that script posts to.
 */
async function openView(context, kind, path, {origins, init = '', bindings = {}} = {}) {
    const page = await context.newPage();
    for (const [name, fn] of Object.entries(bindings)) { await page.exposeBinding(name, fn); }
    const viewId = hub.registerView(kind);
    /** Per document: the next sequence number, and messages that arrived ahead of it. */
    const order = new Map();
    const ports = new Map();
    await page.exposeBinding('__reikaiPost', ({frame}, doc, seq, data) => {
        let state = order.get(doc);
        if (!state) { state = {next: 0, early: new Map()}; order.set(doc, state); }
        state.early.set(seq, data);
        while (state.early.has(state.next)) {
            const text = state.early.get(state.next);
            state.early.delete(state.next);
            state.next++;
            let port = ports.get(doc);
            if (!port) { port = new PagePort(frame, doc); ports.set(doc, port); }
            const isMainFrame = frame === page.mainFrame();
            hub.onMessage(viewId, new URL(frame.url()).origin, isMainFrame, doc, port, text);
        }
    });
    await page.addInitScript(documentStart(kind, origins) + init);
    if (SLOWDOWN > 1) {
        const cdp = await context.newCDPSession(page);
        await cdp.send('Emulation.setCPUThrottlingRate', {rate: SLOWDOWN});
    }
    page.on('pageerror', (error) => {
        if (!knownRace(error.message)) { fail(`uncaught error in ${kind}: ${error.stack ?? error.message}`); }
    });
    page.on('console', (message) => {
        if (message.type() === 'error') { say(`console error in ${kind}: ${message.text()}`); }
    });
    page.on('close', () => hub.forgetView(viewId));
    page.viewId = viewId;
    await page.goto(path.startsWith('https://') ? path : `${ORIGIN}/${path}`);
    return page;
}

const api = async (action, params) => JSON.parse(await hub.callBackend('api', JSON.stringify({action, params})) || 'null');

async function waitFor(what, fn, ms = 60_000) {
    const until = Date.now() + ms;
    for (;;) {
        const value = await fn();
        if (value) { return value; }
        if (Date.now() > until) { throw new Error(`timed out waiting for ${what}`); }
        await new Promise((resolve) => setTimeout(resolve, 250));
    }
}

// --- the Japanese reader's page and the lookup sheet's page (4.3) -----------------------------------

/** What the chapter page's listeners get (the app's jpReader and reikaiReader), and policy violations. */
const fromReader = [];
const READER_INIT = `
(() => {
    if (location.origin !== ${JSON.stringify(CHAPTER_ORIGIN)}) { return; }
    const listener = (name) => ({
        postMessage: (text) => { void globalThis.__readerPost(name, String(text)); },
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
    });
    globalThis.jpReader = listener('jpReader');
    globalThis.reikaiReader = listener('reikaiReader');
    document.addEventListener('securitypolicyviolation', (e) => {
        void globalThis.__readerPost('csp', JSON.stringify({t: 'csp', what: e.violatedDirective + ' ' + e.blockedURI}));
    });
})();`;
const readerBindings = {
    __readerPost: (_source, channel, text) => {
        const message = JSON.parse(text);
        fromReader.push({channel, message});
        if (channel === 'csp') { fail(`the chapter page's policy blocked ${message.what}`); }
    },
};
const readerSince = (at, channel) => fromReader.slice(at).filter((m) => m.channel === channel).map((m) => m.message);
const readerMessage = (at, channel, types, ms = 20_000) => waitFor(`the reader's ${channel} ${types}`,
    () => readerSince(at, channel).find((m) => types.split('|').includes(m.t)), ms);

/** The middle of `text`'s character `index` in the chapter, in client px (a finger's point). */
const charPoint = (page, text, index = 0) => page.evaluate(([wanted, i]) => {
    const walker = document.createTreeWalker(document.getElementById('jp-chapter'), NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
        const at = node.data.indexOf(wanted);
        if (at < 0) { continue; }
        const range = document.createRange();
        range.setStart(node, at + i);
        range.setEnd(node, at + i + 1);
        const r = range.getBoundingClientRect();
        return {x: (r.left + r.right) / 2, y: (r.top + r.bottom) / 2};
    }
    return null;
}, [text, index]);

/** The page's selection as text without ruby readings, or null when nothing is selected. */
const selectedText = (page) => page.evaluate(() => {
    const selection = getSelection();
    if (selection === null || selection.rangeCount === 0 || selection.isCollapsed) { return null; }
    const copy = selection.getRangeAt(0).cloneContents();
    copy.querySelectorAll('rt, rp').forEach((e) => e.remove());
    return copy.textContent;
});

const same = (actual, expected, what) => {
    if (JSON.stringify(actual) !== JSON.stringify(expected)) { fail(`${what}: ${JSON.stringify(actual)}, expected ${JSON.stringify(expected)}`); return false; }
    return true;
};

/** A finger's tap on `text` through jp-reader.js; resolves with the scanner's answer and the point. */
async function tapWord(page, text, index = 0) {
    const point = await charPoint(page, text, index);
    const at = fromReader.length;
    await page.touchscreen.tap(point.x, point.y);
    const message = await readerMessage(at, 'reikaiReader', 'found|empty|error');
    // What follows a tap in the browser (its mouse events) must leave the highlight alone.
    await page.waitForTimeout(300);
    const taps = readerSince(at, 'jpReader').filter((m) => m.t === 'tap');
    if (taps.length > 0) { fail(`a tap on ${text} that looked up also went to the app as a tap: ${JSON.stringify(taps)}`); }
    return {message, point};
}

/** A tap on `text` finds `query` in `sentence`, highlighted, with its rects under the finger (unless a reading was tapped). */
async function expectWord(page, text, index, query, sentence, offset, {underFinger = true} = {}) {
    const {message, point} = await tapWord(page, text, index);
    if (!same(message.t, 'found', `a tap on ${text}[${index}]`)) { return message; }
    same([message.type, message.query, message.sentence, message.writingMode], ['terms', query, {text: sentence, offset}, 'vertical-rl'],
        `a tap on ${text}[${index}]: type, query, sentence and writing mode`);
    const under = message.rects.some((r) => point.x >= r.left - 1 && point.x <= r.right + 1 && point.y >= r.top - 1 && point.y <= r.bottom + 1);
    if (!under && underFinger) { fail(`a tap on ${text}[${index}]: the word's rects ${JSON.stringify(message.rects)} miss the finger at ${JSON.stringify(point)}`); }
    same(await selectedText(page), query, `the highlight after a tap on ${text}[${index}]`);
    return message;
}

/** The lookup sheet's page (popup.html with popup-host.js), as YomitanPopup loads it. */
const fromPopup = [];
const POPUP_INIT = `
(() => {
    if (location.origin !== ${JSON.stringify(ORIGIN)} || globalThis.top !== globalThis) { return; }
    globalThis.reikaiPopup = {
        postMessage: (text) => { void globalThis.__popupPost(String(text)); },
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
    };
})();
${POPUP_HOST}
();`;
const popupBindings = {__popupPost: (_source, text) => { fromPopup.push(JSON.parse(text)); }};

/** PopupUrls.page and PopupUrls.state (PopupLookup.kt) for a lookup the reader's scanner found. */
function popupLookup(found, documentTitle) {
    const params = new URLSearchParams({type: 'terms', query: found.query});
    if (found.sentence.text.length > found.query.length) {
        params.set('full', found.sentence.text);
        params.set('offset', String(found.sentence.offset));
    }
    params.set('wildcards', 'off');
    return [`/popup.html?${params}`, {focusEntry: 0, url: CHAPTER_URL, documentTitle, pageTheme: 'light', sentence: found.sentence}];
}

/** Shows a lookup in the popup page and presses its first "add" button; resolves with the note added. */
async function addCard(popup, found, token, documentTitle) {
    const [url, state] = popupLookup(found, documentTitle);
    await popup.evaluate(([u, st, t]) => globalThis.__reikaiPopup.show(u, st, t), [url, state, token]);
    await waitFor(`the popup to show ${found.query}`, () => fromPopup.find((m) => m.t === 'shown' && m.token === token), 20_000);
    const notes = anki.notes.length;
    await waitFor(`an enabled "add" button for ${found.query}`, () => popup.evaluate(() => {
        const button = document.querySelector('#dictionary-entries .entry .action-button[data-action=save-note]');
        if (button === null || button.disabled || button.hidden) { return false; }
        button.click();
        return true;
    }), 20_000);
    await waitFor(`the note for ${found.query} at AnkiConnect`, () => anki.notes.length > notes, 20_000);
    // Yomitan's notice of errors, if one stays up after the card is added.
    await popup.waitForTimeout(600);
    const notice = await popup.evaluate(() => {
        const shown = [...document.querySelectorAll('#content-footer .footer-notification')].find((n) => !n.hidden);
        return shown ? shown.textContent.replace(/\s+/g, ' ').trim() : null;
    });
    if (notice !== null) { fail(`a notice stayed up after the card for ${found.query}: ${notice}`); }
    return anki.notes[anki.notes.length - 1];
}

/** A picture for Anki's {screenshot}: a 1x1 PNG standing for the book's cover. */
const COVER_BASE64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

// --- the run --------------------------------------------------------------------------------------

async function launch() {
    if (process.env.REIKAI_CHROME) { return chromium.launch({executablePath: process.env.REIKAI_CHROME}); }
    try {
        return await chromium.launch({channel: 'chrome'});
    } catch (chromeError) {
        try {
            return await chromium.launch();
        } catch (chromiumError) {
            throw new Error(`no browser: Google Chrome (${chromeError.message.split('\n')[0]}) and Playwright's Chromium (${chromiumError.message.split('\n')[0]})`);
        }
    }
}

if (!existsSync(DICTIONARY)) { throw new Error(`missing ${DICTIONARY}`); }
const browser = await launch();
say(`browser ${browser.version()}, Yomitan ${MANIFEST.version} from ${ASSETS}`);
let exitCode = 1;
try {
    const context = await browser.newContext();
    await context.route('**/*', serve);
    const workers = [];
    context.on('page', (page) => page.on('worker', (worker) => workers.push(new URL(worker.url()).pathname)));

    // The Japanese reader's chapter page opens before the engine runs (the engine starts in a reader
    // once the page is still): a tap on a word then waits, and is searched once the engine is ready.
    const readerContext = await browser.newContext({
        viewport: {width: 412, height: 915}, deviceScaleFactor: 2, hasTouch: true, isMobile: true, locale: 'ja-JP',
    });
    await readerContext.route('**/*', serve);
    const reader = await openView(readerContext, 'reader', CHAPTER_URL, {origins: [CHAPTER_ORIGIN], init: READER_INIT, bindings: readerBindings});
    await readerMessage(0, 'jpReader', 'ready');
    await waitFor('the reader\'s scanner to load', () => reader.evaluate(() => typeof globalThis.JpReader?.onTextTap === 'function' &&
        typeof globalThis.__reikaiReader?.clear === 'function'), 20_000);
    let at = fromReader.length;
    const early = await charPoint(reader, '打');
    await reader.touchscreen.tap(early.x, early.y);
    await readerMessage(at, 'reikaiReader', 'wait');
    same(readerSince(at, 'jpReader').filter((m) => m.t === 'tap'), [], 'a tap on a word before the engine runs goes to the app as a tap');
    say('reader: a tap on a word before the engine runs waits for it');

    await openView(context, 'engine', 'background.html');
    await Promise.race([backendReadyPromise, new Promise((_, reject) => setTimeout(() => reject(new Error('the backend never announced applicationBackendReady')), 30_000))]);
    say('the backend is ready');
    // The chapter page hears the engine's "ready", connects and searches the tap it kept (no dictionary yet).
    await readerMessage(at, 'reikaiReader', 'ready');
    same((await readerMessage(at, 'reikaiReader', 'found|empty|error')).t, 'empty', 'the kept tap, searched before any dictionary');
    say('reader: the scanner connected when the engine was ready and searched the kept tap');

    const settings = await openView(context, 'settings', 'settings.html');
    await settings.waitForSelector('#dictionary-import-url-button', {state: 'attached'});
    // settings-main.js sets data-loaded once every controller is prepared (the import button's
    // listener included); clicking earlier could do nothing on a slow runner.
    await waitFor('the settings page to finish preparing', () => settings.evaluate(() => document.documentElement.dataset.loaded === 'true'));
    await settings.evaluate((url) => {
        document.querySelector('#dictionary-import-url-text').value = url;
        document.querySelector('#dictionary-import-url-button').click();
    }, DICTIONARY_URL);
    say('importing the test dictionary from the settings page');
    const title = 'Test Dictionary';
    let progress = '';
    await waitFor('the imported dictionary', async () => {
        const [error, now] = await settings.evaluate(() => [
            document.querySelector('#dictionary-error')?.textContent?.trim() ?? '',
            [...new Set([...document.querySelectorAll('.dictionary-import-progress .progress-info, .progress-status')]
                .map((e) => e.textContent.trim()).filter(Boolean))].join(' | '),
        ]);
        if (error) { throw new Error(`Yomitan reported: ${error}`); }
        progress = now || progress;
        const info = await api('getDictionaryInfo');
        if (!info?.result?.some((d) => d.title === title)) { return false; }
        const options = await api('optionsGetFull');
        return options?.result?.profiles?.[0]?.options?.dictionaries?.some((d) => d.name === title && d.enabled);
    }).catch((e) => { throw new Error(`${e.message} (import progress: ${progress || 'none shown'})`); });
    say('imported and enabled');

    // The app's own lookup path (YomitanEngine.findTerms): deinflection included.
    const found = JSON.parse(await hub.callBackend('findTerms', JSON.stringify({text: '打ち込んだ本'})));
    const headwords = found.entries.flatMap((entry) => entry.headwords);
    say(`findTerms: length ${found.length}, ${headwords.map((h) => `${h.term}【${h.reading}】←${h.matched}`).join(', ')}`);
    if (found.length !== 5 || !headwords.some((h) => h.term === '打ち込む' && h.matched === '打ち込んだ')) {
        fail(`findTerms did not find 打ち込む for 打ち込んだ: ${JSON.stringify(found)}`);
    }

    // A dictionary picture, drawn by the search page's media worker from its own database worker.
    const search = await openView(context, 'search', `search.html?query=${encodeURIComponent('画像')}`);
    const drawn = await waitFor('the picture of 画像', () => search.evaluate(() => {
        const canvas = document.querySelector('canvas.gloss-image');
        if (!canvas || canvas.width === 0) { return false; }
        const blank = document.createElement('canvas');
        blank.width = canvas.width;
        blank.height = canvas.height;
        return canvas.toDataURL() !== blank.toDataURL() ? `${canvas.width}x${canvas.height}` : false;
    }), 30_000).catch((e) => { fail(`dictionary picture: ${e.message}`); return null; });
    if (drawn) { say(`the picture of 画像 is drawn (${drawn})`); }
    const expectedWorkers = ['/__reikai/workers/dictionary-worker-main.js', '/__reikai/workers/dictionary-database-worker-main.js'];
    for (const path of expectedWorkers) {
        if (!workers.includes(path)) { fail(`the fork's worker ${path} never started`); }
    }
    say(`workers started: ${[...new Set(workers)].join(', ')}`);

    // The network path the device uses for AnkiConnect and audio: Yomitan's own fetch, the
    // stand-in's routing, the hub's request and its binary (ArrayBuffer) reply.
    const full = (await api('optionsGetFull')).result;
    full.profiles[0].options.anki.enable = true;
    await api('setAllSettings', {value: full, source: 'reikai-smoke'});
    const version = await api('getAnkiConnectVersion');
    if (version?.result !== 6 || !ankiVersionAsked) {
        fail(`AnkiConnect through the app's network path: ${JSON.stringify(version)} (asked: ${ankiVersionAsked})`);
    } else {
        say('AnkiConnect version 6 came back through the stand-in and a binary reply');
    }

    // --- the Japanese reader (4.3): taps look words up in vertical text with ruby ---------------------
    const sentence = '彼は古い画像を見て、打ち込んだ。';
    const first = await expectWord(reader, '打', 0, '打ち込んだ', sentence, 10);
    say(`reader: 打 found ${first.query} in 「${first.sentence.text}」 at ${first.sentence.offset}, ${first.writingMode}, highlighted`);
    const failed = failures.length;
    await expectWord(reader, '打', 0, '打ち込んだ', sentence, 10);
    await expectWord(reader, '画像', 0, '画像', sentence, 4);
    const second = await expectWord(reader, '読む', 0, '読む', 'それから本を読む。', 6);
    // A tap inside a word finds the whole word: 像 is the segmenter's 画像, and 込, which it splits
    // off 打ち, joins the stem the dictionary's 打ち込んだ covers it from.
    await expectWord(reader, '画像', 1, '画像', sentence, 4);
    await expectWord(reader, '込んだ', 0, '打ち込んだ', sentence, 10);
    await expectWord(reader, '読む', 1, '読む', 'それから本を読む。', 6);
    if (failures.length === failed) {
        say('reader: again on the same word, a ruby base (画像), a later sentence (読む), and inside words (像, 込, む) found whole words');
    }
    const miss = await tapWord(reader, '朝', 0);
    same([miss.message.t, await selectedText(reader)], ['empty', null], 'a tap on a word the dictionary lacks: nothing found, no highlight');
    await expectWord(reader, '打', 0, '打ち込んだ', sentence, 10);
    await reader.evaluate(() => globalThis.__reikaiReader.clear());
    same(await selectedText(reader), null, 'the highlight after clear()');
    at = fromReader.length;
    await reader.touchscreen.tap(206, 6);
    same((await readerMessage(at, 'jpReader', 'tap')).action, 'menu', 'a tap in the margin');
    await reader.waitForTimeout(300);
    same(readerSince(at, 'reikaiReader'), [], 'the scanner after a tap in the margin');
    say('reader: nothing found for 朝, clear() removed the highlight, a margin tap went to the app');
    // A tap on a shown reading looks its word up (jp-reader.js hands on its first character).
    const onReading = await expectWord(reader, 'がぞう', 1, '画像', sentence, 4, {underFinger: false});
    if (onReading.query === '画像') { say('reader: a tap on the reading がぞう found 画像'); }
    await reader.evaluate(() => globalThis.__reikaiReader.clear());

    // --- Anki's {screenshot} is the book's cover (4.3) -------------------------------------------------
    const options = (await api('optionsGetFull')).result;
    // The app's phone defaults (MobileDefaults.kt): no popup nested in the results, so no Yomitan
    // Frontend in the popup page, and popup-host.js answers the backend before {screenshot}.
    Object.assign(options.profiles[0].options.scanning, {enablePopupSearch: true, popupNestingMaxDepth: 0});
    options.profiles[0].options.anki.cardFormats = [{
        name: 'Lapis', icon: 'big-circle', deck: 'Reikai', model: 'Lapis', type: 'term',
        fields: {
            Expression: {value: '{expression}', overwriteMode: 'coalesce'},
            Sentence: {value: '{cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}', overwriteMode: 'coalesce'},
            Picture: {value: '{screenshot}', overwriteMode: 'coalesce'},
            MiscInfo: {value: '{document-title}', overwriteMode: 'coalesce'},
        },
    }];
    await api('setAllSettings', {value: options, source: 'reikai-smoke'});
    const popup = await openView(context, 'popup', 'popup.html?reikai-load=1&reikai-theme=light', {init: POPUP_INIT, bindings: popupBindings});
    await waitFor('the popup page to be ready', () => fromPopup.find((m) => m.t === 'ready' && m.load === 1), 20_000);
    const bookTitle = '第一章 - 試験の本';
    covers.set(popup.viewId, `data:image/png;base64,${COVER_BASE64}`);
    const withCover = await addCard(popup, first, 1, bookTitle);
    const picture = anki.media.find((m) => /^yomitan_browser_screenshot_.*\.png$/.test(m.filename));
    if (same(picture?.data, COVER_BASE64, 'the picture stored for {screenshot}') &&
        same([withCover.fields.Expression, withCover.fields.Sentence, withCover.fields.Picture, withCover.fields.MiscInfo],
            ['打ち込む', '彼は古い画像を見て、<b>打ち込んだ</b>。', `<img src="${picture.filename}" />`, bookTitle], 'the card added with a cover')) {
        say(`anki: the card for ${withCover.fields.Expression} has the sentence, the title and the cover (${picture.filename})`);
    }
    covers.delete(popup.viewId);
    const mediaBefore = anki.media.length;
    const withoutCover = await addCard(popup, second, 2, bookTitle);
    if (same([withoutCover.fields.Expression, withoutCover.fields.Picture, anki.media.length], ['読む', '', mediaBefore], 'the card added without a cover')) {
        say('anki: without a cover the card is still added, its picture field empty, and no notice stays up');
    }
    await readerContext.close();
    await context.close();
    exitCode = failures.length === 0 ? 0 : 1;
} catch (e) {
    say(e.stack ?? String(e));
    fail(e.message ?? String(e));
} finally {
    await browser.close();
    clearTimeout(timer);
}
if (failures.length > 0) {
    console.log(`[smoke ${elapsed()}] FAILED (${failures.length}):\n  ${failures.join('\n  ')}`);
    exitCode = 1;
} else {
    console.log(`[smoke ${elapsed()}] passed: Yomitan ${MANIFEST.version} prepared, imported, looked up, drew a picture, reached AnkiConnect, looked words up in the Japanese reader and put the cover on a card, with an empty tripwire`);
}
process.exit(exitCode);
