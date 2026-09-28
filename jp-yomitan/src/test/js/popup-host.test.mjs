/*
 * Reikai JP: off-device tests of the lookup sheet's page host (assets/jp-reikai/popup-host.js).
 * GPL-3.0-or-later. Run: node --test jp-yomitan/src/test/js/*.test.mjs
 *
 * Each test loads the host into a fresh VM context holding just the web platform pieces it uses,
 * with a few stand-ins for Yomitan's popup page (its notices, its entries, its history writes).
 */
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import vm from 'node:vm';

const ORIGIN = 'https://yomitan.reikai.invalid';
const HOST = readFileSync(new URL('../../main/assets/jp-reikai/popup-host.js', import.meta.url), 'utf8');

/** An element whose `hidden` changes and children the host's observers see. */
class FakeElement {
    constructor() {
        this.observers = [];
        this._hidden = true;
        this.firstElementChild = null;
        this.clicks = 0;
    }
    get hidden() { return this._hidden; }
    set hidden(value) {
        const changed = value !== this._hidden;
        this._hidden = value;
        if (changed) { this.observers.forEach((fn) => fn()); }
    }
    click() { this.clicks++; }
}

function load({url = `${ORIGIN}/popup.html?reikai-load=2&reikai-theme=dark`} = {}) {
    const posted = [];
    const windowMessages = [];
    const listeners = new Set();
    const elements = {
        '#no-results': new FakeElement(),
        '#no-dictionaries': new FakeElement(),
        '#dictionary-entries': new FakeElement(),
        '#navigate-previous-button': new FakeElement(),
    };
    const root = new FakeElement();
    root.dataset = {};
    class FakeHistory {
        constructor() { this.state = null; this.url = url; this.writes = []; }
        replaceState(data, _title, next) { this.state = data; this.url = next; this.writes.push(data); }
        pushState(data, _title, next) { this.state = data; this.url = next; this.writes.push(data); }
    }
    class FakeMutationObserver {
        constructor(callback) { this.callback = callback; }
        observe(target) { target.observers.push(() => this.callback([])); }
    }
    const events = new EventTarget();
    const context = {
        location: new URL(url),
        reikaiPopup: {postMessage: (text) => posted.push(JSON.parse(text))},
        chrome: {runtime: {onMessage: {addListener: (fn) => listeners.add(fn)}}},
        document: {
            readyState: 'complete',
            documentElement: root,
            querySelector: (selector) => elements[selector] ?? null,
            addEventListener: () => undefined,
        },
        MutationObserver: FakeMutationObserver,
        History: FakeHistory,
        PopStateEvent: class extends Event { constructor(type, init) { super(type); this.state = init?.state; } },
        dispatchEvent: (event) => events.dispatchEvent(event),
        postMessage: (data, origin) => windowMessages.push({data, origin}),
        performance: {now: () => 0},
        requestAnimationFrame: (fn) => fn(),
        setTimeout: (fn, ms) => (ms === 0 ? fn() : undefined),
        crypto: {randomUUID: () => 'token-1'},
        URL, URLSearchParams, Event, EventTarget, Element: FakeElement,
    };
    context.globalThis = context;
    context.top = context;
    context.history = new FakeHistory();
    vm.createContext(context);
    vm.runInContext(`${HOST}\n();`, context);
    const deliver = (message) => [...listeners].forEach((fn) => fn(message, {}, () => undefined));
    return {page: context, posted, windowMessages, elements, root, deliver, events};
}

test('the page is ready, with its load number, once Yomitan shows its first notice', () => {
    const {posted, elements} = load();
    assert.deepEqual(posted, []);
    elements['#no-dictionaries'].hidden = false;
    assert.deepEqual(posted, [{t: 'ready', load: 2}]);
});

test('the empty first lookup already carries the page theme', () => {
    const {page} = load();
    assert.equal(page.history.state.state.pageTheme, 'dark');
    assert.equal(page.history.url, '/popup.html?type=terms&query=');
});

test('a lookup that ends on the notice already showing is still reported shown', () => {
    const {page, posted, elements} = load();
    elements['#no-dictionaries'].hidden = false; // ready
    const popstates = [];
    page.dispatchEvent = (event) => {
        popstates.push(event.type);
        // Yomitan shows "no dictionaries" again for the new lookup.
        elements['#no-dictionaries'].hidden = false;
    };
    page.__reikaiPopup.show('/popup.html?type=terms&query=%E7%8C%AB', {pageTheme: 'dark'}, 7);
    assert.deepEqual(popstates, ['popstate']);
    assert.deepEqual(posted.at(-1), {t: 'shown', token: 7, ms: 0});
});

test('a word looked up inside the popup keeps the reader\'s theme, address and title', () => {
    const {page} = load();
    page.__reikaiPopup.show('/popup.html?type=terms&query=x', {url: 'https://kakuyomu.jp/works/1', documentTitle: '第30話', pageTheme: 'dark'}, 1);
    // Yomitan's own lookup from a tapped word (display.js _onContentTextScannerSearchSuccess).
    const state = {focusEntry: 0, url: `${ORIGIN}/popup.html?type=terms&query=x`, documentTitle: 'Yomitan Search', pageTheme: 'light'};
    page.history.replaceState({id: 'a', state}, '', '/popup.html?type=terms&query=y');
    assert.equal(state.pageTheme, 'dark');
    assert.equal(state.url, 'https://kakuyomu.jp/works/1');
    assert.equal(state.documentTitle, '第30話');
});

test('from another app, with no page behind the lookup, the popup\'s own address is dropped', () => {
    const {page} = load();
    page.__reikaiPopup.show('/popup.html?type=terms&query=x', {pageTheme: 'dark'}, 1);
    const state = {url: `${ORIGIN}/popup.html?type=terms&query=x`, documentTitle: 'Yomitan Search'};
    page.history.pushState({id: 'b', state}, '', '/popup.html');
    assert.equal('url' in state, false);
    assert.equal('documentTitle' in state, false);
});

test('changed settings or dictionaries make the page stale', () => {
    const {posted, deliver} = load();
    deliver({action: 'applicationOptionsUpdated', params: {source: 'reikai-jp'}});
    deliver({action: 'applicationDatabaseUpdated', params: {type: 'dictionary', cause: 'import'}});
    deliver({action: 'applicationZoomChanged', params: {}});
    assert.deepEqual(posted, [{t: 'stale', load: 2}, {t: 'stale', load: 2}]);
});

test('the popup\'s frame endpoint is connected as a host page would', () => {
    const {windowMessages, deliver} = load();
    deliver({action: 'frameEndpointReady', params: {secret: 's3'}, frameId: 0});
    // Built inside the page's realm: compared as JSON.
    assert.deepEqual(JSON.parse(JSON.stringify(windowMessages)), [{
        data: {action: 'frameEndpointConnect', params: {secret: 's3', token: 'token-1', hostFrameId: 0}},
        origin: ORIGIN,
    }]);
});

test('the app hears when Yomitan\'s history gains or loses a lookup to go back to', () => {
    const {page, posted, root, elements} = load();
    root.dataset.hasNavigationPrevious = 'true';
    root.hidden = false; // any attribute change the observer sees
    root.dataset.hasNavigationPrevious = 'false';
    root.hidden = true;
    assert.deepEqual(posted, [{t: 'nav', back: true}, {t: 'nav', back: false}]);
    page.__reikaiPopup.back();
    assert.equal(elements['#navigate-previous-button'].clicks, 1);
});
