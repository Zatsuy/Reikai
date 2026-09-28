/*
 * Reikai JP: off-device tests of the Yomitan stand-in (assets/jp-reikai/stand-in.js) against a mock
 * of the app's hub. GPL-3.0-or-later. Run: node --test jp-yomitan/src/test/js/*.test.mjs
 *
 * Each test loads the stand-in into a fresh VM context holding just the web platform pieces it uses,
 * as WebView would inject it into a new document. The stand-in with Yomitan itself, in headless
 * Chrome, is scripts/fork/yomitan-smoke/ (roadmap 3.6).
 */
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import vm from 'node:vm';

const ORIGIN = 'https://yomitan.reikai.invalid';
const STAND_IN = readFileSync(new URL('../../main/assets/jp-reikai/stand-in.js', import.meta.url), 'utf8');
const MANIFEST = {version: '26.9.8.0', permissions: ['storage'], options_ui: {page: 'settings.html'}};

/** Loads the stand-in into a new document at `url` of a WebView of `kind`; returns the document and the hub mock. */
function load({url = `${ORIGIN}/settings.html`, kind = 'settings', isTop = true} = {}) {
    const sent = [];
    const hubTarget = new EventTarget();
    const hub = {
        postMessage: (text) => sent.push(text),
        addEventListener: (...args) => hubTarget.addEventListener(...args),
        /** The app's side: a message to the page, header plus payload, or raw bytes. */
        deliver: (header, payload = '') => hubTarget.dispatchEvent(Object.assign(new Event('message'), {data: typeof header === 'string' || header instanceof ArrayBuffer ? header : `${JSON.stringify(header)}\n${payload}`})),
        sent,
        headers: () => sent.map((m) => JSON.parse(m.slice(0, m.indexOf('\n') < 0 ? m.length : m.indexOf('\n')))),
        last: () => {
            const m = sent[sent.length - 1];
            const cut = m.indexOf('\n');
            return {header: JSON.parse(cut < 0 ? m : m.slice(0, cut)), payload: cut < 0 ? '' : m.slice(cut + 1)};
        },
    };
    const workers = [];
    class FakeWorker extends EventTarget {
        constructor(href, options) {
            super();
            this.href = new URL(String(href), url).href;
            this.options = options;
            this.posted = [];
            workers.push(this);
        }
        postMessage(message, transfer) { this.posted.push({message, transfer}); }
        terminate() { this.terminated = true; }
    }
    const windowEvents = new EventTarget();
    const context = {
        reikaiHub: hub,
        location: new URL(url),
        navigator: {userAgent: 'test'},
        Worker: FakeWorker,
        URL, Headers, Response, Request, Blob, EventTarget, Event, MessageChannel, DOMException, URLSearchParams,
        btoa, atob, setTimeout, clearTimeout, console,
        fetch: async () => new Response('same-origin'),
        addEventListener: (...args) => windowEvents.addEventListener(...args),
    };
    context.globalThis = context;
    context.top = isTop ? context : {};
    vm.createContext(context);
    vm.runInContext(`${STAND_IN}\n(${JSON.stringify({origin: ORIGIN, manifest: MANIFEST, kind, debug: false})});`, context);
    return {page: context, hub, workers};
}

/** Answers the last request the page made with `payload` (or an error). */
const answerLast = (hub, payload = '', err = undefined) => hub.deliver({t: 'reply', mid: hub.last().header.mid, err}, payload);

test('a new document says hello with its URL', () => {
    const {hub} = load();
    assert.deepEqual(hub.headers()[0], {t: 'hello', url: `${ORIGIN}/settings.html`});
});

test('getURL and getManifest use the fixed origin and the embedded manifest', () => {
    const {page} = load();
    assert.equal(page.chrome.runtime.getURL('/js/app/content-script-main.js'), `${ORIGIN}/js/app/content-script-main.js`);
    assert.equal(page.chrome.runtime.getManifest().version, '26.9.8.0');
});

test('sendMessage resolves with the reply and names the action for the hub', async () => {
    const {page, hub} = load();
    const reply = page.chrome.runtime.sendMessage({action: 'optionsGet', params: {}});
    assert.equal(hub.last().header.action, 'optionsGet');
    answerLast(hub, JSON.stringify({result: 1}));
    assert.deepEqual(JSON.parse(JSON.stringify(await reply)), {result: 1});
});

test('an error reply reaches a callback as lastError', () => {
    const {page, hub} = load();
    let seen;
    page.chrome.runtime.sendMessage({action: 'x'}, () => { seen = page.chrome.runtime.lastError?.message; });
    answerLast(hub, '', 'The message port closed before a response was received.');
    assert.equal(seen, 'The message port closed before a response was received.');
});

test('storage.local.get decodes the stored JSON texts and fills defaults', async () => {
    const {page, hub} = load();
    const value = page.chrome.storage.local.get({options: null, missing: 5});
    assert.deepEqual(hub.last().header, {t: 'req', op: 'sget', area: 'local', mid: hub.last().header.mid});
    answerLast(hub, JSON.stringify({options: JSON.stringify('{"version":71}')}));
    assert.deepEqual({...await value}, {options: '{"version":71}', missing: 5});
});

test('a page imports dictionaries through the fork entry that keeps zip.js in the worker', () => {
    const {page, workers} = load();
    void new page.Worker('/js/dictionary/dictionary-worker-main.js', {type: 'module'});
    assert.equal(workers[0].href, `${ORIGIN}/__reikai/workers/dictionary-worker-main.js`);
});

test('the backend gets an inert database worker, since pages draw their own pictures', () => {
    const {page, workers} = load({url: `${ORIGIN}/background.html`, kind: 'engine'});
    const worker = new page.Worker('/js/dictionary/dictionary-database-worker-main.js', {type: 'module'});
    worker.postMessage({action: 'drawMedia'});
    assert.equal(workers.length, 0);
});

test('a chapter page cannot start engine-origin workers', () => {
    const {page, workers} = load({url: 'https://kakuyomu.jp/works/1', kind: 'reader'});
    void new page.Worker(`${ORIGIN}/js/display/media-drawing-worker.js`, {type: 'module'});
    assert.equal(workers.length, 0);
});

test('the first picture starts a page database worker holding the media worker\'s port', async () => {
    const {page, workers} = load({url: `${ORIGIN}/search.html`, kind: 'search'});
    const media = new page.Worker(`${ORIGIN}/js/display/media-drawing-worker.js`, {type: 'module'});
    const {port1} = new MessageChannel();
    (await page.navigator.serviceWorker.ready).active.postMessage({action: 'connectToDatabaseWorker'}, [port1]);
    assert.equal(workers.length, 1);

    media.postMessage({action: 'drawMedia', params: {requests: []}}, []);

    assert.equal(workers[1].href, `${ORIGIN}/__reikai/workers/dictionary-database-worker-main.js`);
    assert.equal(workers[1].posted[0].message.action, 'connectToDatabaseWorker');
    assert.equal(workers[1].posted[0].transfer[0], port1);
    port1.close();
});

test('a cross-origin fetch goes to the app and returns its binary answer', async () => {
    const {page, hub} = load();
    const response = page.fetch('https://jisho.org/search/%E7%8C%AB', {headers: {accept: 'text/html'}});
    await new Promise((resolve) => setTimeout(resolve, 0));
    const {header, payload} = hub.last();
    assert.equal(header.op, 'fetch');
    assert.deepEqual(JSON.parse(payload), {url: 'https://jisho.org/search/%E7%8C%AB', method: 'GET', headers: {accept: 'text/html'}});
    hub.deliver({t: 'reply', mid: header.mid, status: 200, statusText: 'OK', headers: {'content-type': 'text/html'}, bin: true});
    hub.deliver(new TextEncoder().encode('<html>猫</html>').buffer);
    assert.equal(await (await response).text(), '<html>猫</html>');
});

test('the backend answers the app\'s findTerms with only lengths and headwords', async () => {
    const {page, hub} = load({url: `${ORIGIN}/background.html`, kind: 'engine'});
    page.chrome.runtime.onMessage.addListener((message, _sender, respond) => {
        respond({result: {originalTextLength: 3, dictionaryEntries: [{headwords: [{term: '食べる', reading: 'たべる', sources: [{originalText: '食べた', isPrimary: true}]}], definitions: ['x']}]}});
        return false;
    });
    hub.deliver({t: 'nreq', nid: 9, op: 'findTerms'}, JSON.stringify({text: '食べた本'}));
    await new Promise((resolve) => setTimeout(resolve, 0));
    const {header, payload} = hub.last();
    assert.deepEqual(header, {t: 'nresp', nid: 9});
    assert.deepEqual(JSON.parse(payload), {length: 3, entries: [{headwords: [{term: '食べる', reading: 'たべる', matched: '食べた'}]}]});
});

test('an extension API the stand-in lacks trips the wire once', () => {
    const {page, hub} = load();
    void page.chrome.sidePanel;
    void page.chrome.bookmarks;
    void page.chrome.bookmarks;
    assert.deepEqual(hub.headers().filter((h) => h.t === 'trip'), [{t: 'trip', path: 'chrome.bookmarks'}]);
});
