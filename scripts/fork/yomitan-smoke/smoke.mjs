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

const HERE = fileURLToPath(new URL('.', import.meta.url));
const ROOT = join(HERE, '../../..');
const argAssets = process.argv.indexOf('--assets');
const ASSETS = argAssets > 0 ? process.argv[argAssets + 1] : join(ROOT, 'jp-yomitan/src/main/assets');
const DICTIONARY = join(HERE, 'test-dictionary.zip');
const DICTIONARY_URL = `${ORIGIN}/__reikai/check/test-dictionary.zip`;
const TIMEOUT_MS = 120_000;
const SLOWDOWN = Number(process.env.REIKAI_SMOKE_SLOWDOWN ?? 1);

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

const hub = new Hub({
    storage: new Map(),
    openPage: (how, url) => { say(`Yomitan asked to open (${how}) ${url}`); return null; },
    fetch: (request) => {
        const anki = request.url.startsWith('http://127.0.0.1:8765') && request.method === 'POST' ? JSON.parse(request.body ?? '{}') : null;
        if (anki?.action === 'version') {
            // A canned AnkiConnect, to prove the app's network path: Yomitan's POST in, a binary reply
            // out. Yomitan asks with API version 2, which AnkiConnect answers with the bare result.
            ankiVersionAsked = anki.version === 2;
            return {status: 200, statusText: 'OK', headers: {'content-type': 'application/json'}, body: Buffer.from('6')};
        }
        say(`Yomitan fetched ${request.method} ${request.url}${anki ? ` (${anki.action})` : ''} (offline here)`);
        return {failed: 'offline in the smoke test'};
    },
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
const MANIFEST = JSON.parse(await readFile(join(ASSETS, 'yomitan/manifest.json'), 'utf8'));

/** What WebView gives a page of the engine origin before any of its scripts: no SharedWorker, the
 * web message listener's object (addWebMessageListener), then the stand-in (addDocumentStartJavaScript). */
const documentStart = (kind) => `(() => {
    if (location.origin !== ${JSON.stringify(ORIGIN)}) { return; }
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


async function openView(context, kind, path) {
    const page = await context.newPage();
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
    await page.addInitScript(documentStart(kind));
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
    await page.goto(`${ORIGIN}/${path}`);
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

    await openView(context, 'engine', 'background.html');
    await Promise.race([backendReadyPromise, new Promise((_, reject) => setTimeout(() => reject(new Error('the backend never announced applicationBackendReady')), 30_000))]);
    say('the backend is ready');

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
    console.log(`[smoke ${elapsed()}] passed: Yomitan ${MANIFEST.version} prepared, imported, looked up, drew a picture and reached AnkiConnect, with an empty tripwire`);
}
process.exit(exitCode);
