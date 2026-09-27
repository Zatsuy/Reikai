/*
 * Reikai JP: the browser-extension stand-in for Yomitan (roadmap 2.1 spike).
 * Copyright (C) 2026 Reikai JP contributors. GPL-3.0-or-later.
 *
 * Injected at document start into every frame of the Yomitan origin, before any of Yomitan's own
 * scripts. It provides the `chrome.*` functions Yomitan calls, backed by the app: messages between
 * documents go through the `reikaiHub` object (SpikeHub.kt), storage through localStorage. Any
 * extension API Yomitan touches that is not provided here is reported (the tripwire), so a new
 * Yomitan version fails loudly instead of silently.
 */
(() => {
    'use strict';
    if (globalThis.__reikaiStandIn) { return; }
    const hub = globalThis.reikaiHub;
    if (typeof hub === 'undefined') { return; }
    globalThis.__reikaiStandIn = true;

    const EXTENSION_ID = 'reikai-yomitan';
    const ORIGIN = location.origin;
    const path = location.pathname;
    const role = path === '/background.html' ? 'backend' : (path.startsWith('/__reikai/reader') ? 'content' : 'page');

    // --- transport -------------------------------------------------------------------------------

    /** @param {object} header @param {string} [payload] */
    const post = (header, payload = '') => hub.postMessage(`${JSON.stringify(header)}\n${payload}`);
    const log = (level, text) => post({t: 'log', level}, text);

    let tabId = -1;
    let frameId = 0;
    let nextMid = 1;
    /** @type {Map<number, (err: string|undefined, payload: string) => void>} */
    const waiting = new Map();
    /** @type {Map<string, (header: object, payload: string) => void>} */
    const handlers = new Map();

    hub.addEventListener('message', (event) => {
        const data = /** @type {string} */ (event.data);
        const cut = data.indexOf('\n');
        const header = JSON.parse(cut < 0 ? data : data.slice(0, cut));
        const payload = cut < 0 ? '' : data.slice(cut + 1);
        switch (header.t) {
            case 'welcome':
                tabId = header.tabId;
                frameId = header.frameId;
                break;
            case 'reply':
            case 'other': {
                const done = waiting.get(header.mid);
                waiting.delete(header.mid);
                if (done) { done(header.err, payload); }
                break;
            }
            case 'msg':
                deliver(header, payload);
                break;
            default: {
                const handler = handlers.get(header.t);
                if (handler) { handler(header, payload); }
            }
        }
    });
    post({t: 'hello', role, url: location.href});

    /** Sends a request the app answers; resolves with the raw payload. */
    const request = (header, payload = '') => new Promise((resolve, reject) => {
        const mid = nextMid++;
        waiting.set(mid, (err, reply) => (err ? reject(new Error(err)) : resolve(reply)));
        post({...header, mid}, payload);
    });
    globalThis.__reikaiRequest = request;
    globalThis.__reikaiOn = (type, handler) => handlers.set(type, handler);

    // --- chrome.runtime.lastError ----------------------------------------------------------------

    /** @type {{message: string}|undefined} */
    let lastError;
    const withLastError = (message, fn) => {
        lastError = message ? {message} : undefined;
        try { fn(); } finally { lastError = undefined; }
    };

    // --- events ----------------------------------------------------------------------------------

    const makeEvent = () => {
        const listeners = new Set();
        return {
            listeners,
            addListener: (fn) => { listeners.add(fn); },
            removeListener: (fn) => { listeners.delete(fn); },
            hasListener: (fn) => listeners.has(fn),
            hasListeners: () => listeners.size > 0,
            dispatch: (...args) => { for (const fn of [...listeners]) { fn(...args); } },
        };
    };
    const onMessage = makeEvent();

    // --- messaging -------------------------------------------------------------------------------

    const decode = (payload) => (payload === '' ? undefined : JSON.parse(payload));

    /** Sends a message; `callback(response)` or a promise, with chrome's lastError semantics. */
    const sendThrough = (header, message, callback) => {
        const mid = nextMid++;
        const promise = typeof callback === 'function' ? null : new Promise((resolve, reject) => {
            callback = (response) => (lastError ? reject(new Error(lastError.message)) : resolve(response));
        });
        waiting.set(mid, (err, payload) => withLastError(err, () => callback(err ? undefined : decode(payload))));
        post({...header, mid}, JSON.stringify(message));
        return promise ?? undefined;
    };

    const makeSender = (from) => {
        const sender = {id: EXTENSION_ID, url: from.url, origin: new URL(from.url).origin, frameId: from.frameId};
        if (from.tabId >= 0) {
            sender.tab = {id: from.tabId, index: 0, windowId: 1, active: true, incognito: false, url: from.url};
        }
        return sender;
    };

    /** Delivers an incoming message to this document's onMessage listeners. */
    const deliver = (header, payload) => {
        const message = decode(payload);
        const sender = makeSender(header.sender);
        let responded = false;
        let keepOpen = false;
        const sendResponse = (response) => {
            if (responded) { return; }
            responded = true;
            post({t: 'resp', rid: header.rid, has: true}, response === undefined ? '' : JSON.stringify(response));
        };
        for (const listener of [...onMessage.listeners]) {
            let result;
            try {
                result = listener(message, sender, sendResponse);
            } catch (e) {
                log('error', `onMessage listener threw: ${e?.stack ?? e}`);
                continue;
            }
            if (result === true) {
                keepOpen = true;
            } else if (result && typeof result.then === 'function') {
                keepOpen = true;
                result.then(
                    (value) => (value === undefined ? (responded || noResponse()) : sendResponse(value)),
                    (e) => sendResponse({error: {message: String(e?.message ?? e)}}),
                );
            }
        }
        const noResponse = () => {
            if (responded) { return; }
            responded = true;
            post({t: 'resp', rid: header.rid, has: false});
        };
        if (!keepOpen && !responded) { noResponse(); }
    };

    // --- ports (chrome.runtime.Port; Yomitan's backend opens them with tabs.connect) ------------

    /** @type {Map<string, object>} */
    const ports = new Map();
    const makePort = (pid, name, sender) => {
        const port = {
            name,
            sender,
            onMessage: makeEvent(),
            onDisconnect: makeEvent(),
            postMessage: (message) => {
                if (!ports.has(pid)) { throw new Error('Attempting to use a disconnected port object'); }
                post({t: 'pmsg', pid}, JSON.stringify(message));
            },
            disconnect: () => { if (ports.delete(pid)) { post({t: 'pdisc', pid}); } },
        };
        ports.set(pid, port);
        return port;
    };
    const connect = (targetTab, info = {}) => {
        const pid = `${tabId}.${frameId}.${nextMid++}.${Math.random().toString(36).slice(2)}`;
        const header = {t: 'connect', pid, tabId: targetTab, name: info.name ?? ''};
        if (typeof info.frameId === 'number') { header.frameId = info.frameId; }
        const port = makePort(pid, info.name ?? '', undefined);
        log('info', `port: connect ${pid} to tab ${targetTab} frame ${info.frameId} (${info.name})`);
        post(header);
        return port;
    };
    handlers.set('onconnect', (header) => {
        log('info', `port: onconnect ${header.pid} in frame ${frameId} (${runtime.onConnect.listeners.size} listeners)`);
        runtime.onConnect.dispatch(makePort(header.pid, header.name, makeSender(header.sender)));
    });
    handlers.set('pmsg', (header, payload) => {
        const port = ports.get(header.pid);
        if (port) { port.onMessage.dispatch(decode(payload), port); }
    });
    handlers.set('pdisc', (header) => {
        const port = ports.get(header.pid);
        if (!port) { return; }
        ports.delete(header.pid);
        log('info', `port: disconnect ${header.pid} ${header.err ?? ''}`);
        withLastError(header.err, () => port.onDisconnect.dispatch(port));
    });

    // --- storage (localStorage of this private origin, shared by all its documents) --------------

    const STORAGE_PREFIX = 'rk.storage.';
    const sessionValues = new Map();
    const makeArea = (read, write, remove, keys) => {
        const onChanged = makeEvent();
        const get = (query) => {
            const result = {};
            if (query === null || query === undefined) {
                for (const key of keys()) { result[key] = read(key); }
            } else if (typeof query === 'string') {
                const v = read(query); if (v !== undefined) { result[query] = v; }
            } else if (Array.isArray(query)) {
                for (const key of query) { const v = read(key); if (v !== undefined) { result[key] = v; } }
            } else {
                for (const [key, fallback] of Object.entries(query)) { const v = read(key); result[key] = v === undefined ? fallback : v; }
            }
            return result;
        };
        const cb = (args, value) => {
            const last = args[args.length - 1];
            if (typeof last === 'function') { withLastError(undefined, () => last(value)); return undefined; }
            return Promise.resolve(value);
        };
        return {
            get: (...args) => cb(args, get(typeof args[0] === 'function' ? null : args[0])),
            set: (items, ...rest) => { for (const [k, v] of Object.entries(items)) { write(k, v); } return cb(rest, undefined); },
            remove: (query, ...rest) => { for (const k of (Array.isArray(query) ? query : [query])) { remove(k); } return cb(rest, undefined); },
            clear: (...rest) => { for (const k of keys()) { remove(k); } return cb(rest, undefined); },
            getBytesInUse: (...args) => cb(args, 0),
            onChanged,
        };
    };
    const local = makeArea(
        (k) => { const v = localStorage.getItem(STORAGE_PREFIX + k); return v === null ? undefined : JSON.parse(v); },
        (k, v) => localStorage.setItem(STORAGE_PREFIX + k, JSON.stringify(v)),
        (k) => localStorage.removeItem(STORAGE_PREFIX + k),
        () => Object.keys(localStorage).filter((k) => k.startsWith(STORAGE_PREFIX)).map((k) => k.slice(STORAGE_PREFIX.length)),
    );
    const session = makeArea((k) => sessionValues.get(k), (k, v) => sessionValues.set(k, v), (k) => sessionValues.delete(k), () => [...sessionValues.keys()]);

    // --- the manifest (read synchronously: getManifest() is synchronous) --------------------------

    let manifest = {};
    try {
        const xhr = new XMLHttpRequest();
        xhr.open('GET', '/manifest.json', false);
        xhr.send();
        manifest = JSON.parse(xhr.responseText);
    } catch (e) {
        log('error', `manifest: ${e}`);
    }

    // --- helpers for the many functions that only need to exist ----------------------------------

    /** Returns `value` through a trailing callback, or as a promise. */
    const answer = (value) => (...args) => {
        const last = args[args.length - 1];
        if (typeof last === 'function') { withLastError(undefined, () => last(value)); return undefined; }
        return Promise.resolve(value);
    };
    const nothing = answer(undefined);

    const runtime = {
        id: EXTENSION_ID,
        get lastError() { return lastError; },
        getURL: (p) => `${ORIGIN}/${String(p).replace(/^\//, '')}`,
        getManifest: () => manifest,
        getPlatformInfo: answer({os: 'android', arch: 'arm64', nacl_arch: 'arm'}),
        getContexts: answer([]),
        sendMessage: (...args) => {
            // (message, callback) or (message, options, callback) or (extensionId, message, ...)
            const fnIndex = args.findIndex((a) => typeof a === 'function');
            const callback = fnIndex >= 0 ? args[fnIndex] : undefined;
            const rest = fnIndex >= 0 ? args.slice(0, fnIndex) : args;
            const message = rest.length >= 2 && typeof rest[0] === 'string' ? rest[1] : rest[0];
            return sendThrough({t: 'send'}, message, callback);
        },
        openOptionsPage: nothing,
        connectNative: () => { throw new Error('Native messaging is not available in Reikai JP'); },
        onMessage,
        onConnect: makeEvent(),
        onInstalled: makeEvent(),
        onStartup: makeEvent(),
    };

    const tabs = {
        sendMessage: (targetTab, message, ...rest) => {
            const callback = rest.find((a) => typeof a === 'function');
            const options = rest.find((a) => a && typeof a === 'object') ?? {};
            const header = {t: 'tabsend', tabId: targetTab};
            if (typeof options.frameId === 'number') { header.frameId = options.frameId; }
            return sendThrough(header, message, callback);
        },
        query: (...args) => {
            const callback = args.find((a) => typeof a === 'function');
            const tabs = request({t: 'tabs'}).then((payload) => JSON.parse(payload).map(({id, url}) => (
                {id, url, index: 0, windowId: 1, active: true, incognito: false}
            )));
            if (!callback) { return tabs; }
            void tabs.then((list) => withLastError(undefined, () => callback(list)));
            return undefined;
        },
        get: (id, ...rest) => {
            const callback = rest.find((a) => typeof a === 'function');
            const tab = request({t: 'tabs'}).then((payload) => {
                const found = JSON.parse(payload).find((t) => t.id === id);
                return found ? {id, url: found.url, index: 0, windowId: 1, active: true, incognito: false} : undefined;
            });
            if (!callback) { return tab; }
            void tab.then((value) => withLastError(value ? undefined : `No tab with id: ${id}.`, () => callback(value)));
            return undefined;
        },
        getCurrent: (...args) => answer(role === 'backend' ? undefined : {id: tabId, index: 0, windowId: 1, active: true, url: location.href})(...args),
        create: nothing,
        update: nothing,
        remove: nothing,
        getZoom: answer(1),
        captureVisibleTab: answer(undefined),
        connect,
        onZoomChange: makeEvent(),
    };

    const permissions = {
        getAll: answer({permissions: [...(manifest.permissions ?? [])], origins: ['<all_urls>']}),
        contains: answer(true),
        request: answer(true),
        remove: answer(true),
        onAdded: makeEvent(),
        onRemoved: makeEvent(),
    };

    const chromeObject = {
        runtime,
        storage: {local, session, onChanged: makeEvent()},
        permissions,
        tabs,
        windows: {create: nothing, get: answer(undefined), update: nothing, getCurrent: answer({id: 1})},
        action: {setBadgeText: nothing, setBadgeBackgroundColor: nothing, setTitle: nothing, setIcon: nothing},
        commands: {onCommand: makeEvent(), getAll: answer([])},
        contextMenus: {create: () => undefined, remove: nothing, removeAll: nothing, update: nothing, onClicked: makeEvent()},
        omnibox: {onInputEntered: makeEvent(), onInputChanged: makeEvent(), setDefaultSuggestion: () => undefined},
        declarativeNetRequest: {
            getSessionRules: answer([]), updateSessionRules: nothing,
            getDynamicRules: answer([]), updateDynamicRules: nothing,
        },
        scripting: {
            insertCSS: nothing, executeScript: answer([]),
            registerContentScripts: nothing, unregisterContentScripts: nothing,
            getRegisteredContentScripts: answer([]), updateContentScripts: nothing,
        },
        extension: {isAllowedFileSchemeAccess: answer(false), isAllowedIncognitoAccess: answer(false)},
    };

    // --- the tripwire: report any extension API that is used but not provided ---------------------

    /** Properties Yomitan tests for on purpose and must stay absent (it then takes another path). */
    const ABSENT_ON_PURPOSE = new Set(['chrome.offscreen', 'chrome.sidePanel', 'chrome.runtime.lastError']);
    const tripped = new Set();
    const wrapped = new WeakMap();
    const wrap = (target, name) => {
        let proxy = wrapped.get(target);
        if (!proxy) { proxy = makeProxy(target, name); wrapped.set(target, proxy); }
        return proxy;
    };
    const makeProxy = (target, name) => new Proxy(target, {
        get(obj, key, receiver) {
            if (typeof key === 'symbol' || key in obj) {
                const value = Reflect.get(obj, key, receiver);
                if (value && typeof value === 'object' && !Array.isArray(value) && key !== 'listeners' && Object.getPrototypeOf(value) === Object.prototype) {
                    return wrap(value, `${name}.${key}`);
                }
                return value;
            }
            const full = `${name}.${String(key)}`;
            if (!ABSENT_ON_PURPOSE.has(full) && key !== 'then' && !tripped.has(full)) {
                tripped.add(full);
                post({t: 'trip', path: full});
            }
            return undefined;
        },
    });
    globalThis.chrome = wrap(chromeObject, 'chrome');

    // --- web platform gaps ------------------------------------------------------------------------

    // The backend always opens a SharedWorker bridge when it runs as a page (Yomitan's Firefox path).
    // It only carries the channel that draws dictionary images; the spike records whether the WebView
    // has SharedWorker at all and otherwise gives the backend an inert one.
    const hasSharedWorker = typeof SharedWorker === 'function';
    if (role === 'backend') {
        log('info', `platform: SharedWorker=${hasSharedWorker} serviceWorker=${'serviceWorker' in navigator} BroadcastChannel=${typeof BroadcastChannel}`);
    }
    if (!hasSharedWorker) {
        globalThis.SharedWorker = class {
            constructor() {
                const channel = new MessageChannel();
                this.port = channel.port1;
                this.onerror = null;
            }
            addEventListener() {}
            removeEventListener() {}
        };
    }

    // Probe: do documents in separate WebViews of one app hear each other on a BroadcastChannel?
    if (typeof BroadcastChannel === 'function') {
        const probe = new BroadcastChannel('reikai-probe');
        probe.onmessage = (e) => log('info', `probe: ${path} heard BroadcastChannel from ${e.data}`);
        probe.postMessage(path);
    }

    // AnkiConnect: Yomitan's backend POSTs to its Anki server, which a page cannot reach (and a
    // WebView cannot intercept a POST body), so requests to that address go to the app instead.
    if (role === 'backend') {
        const pageFetch = globalThis.fetch.bind(globalThis);
        globalThis.fetch = async (input, init) => {
            const url = typeof input === 'string' ? input : input?.url;
            if (typeof url === 'string' && /^https?:\/\/(127\.0\.0\.1|localhost):8765\/?$/.test(url) && init?.method === 'POST') {
                const body = await request({t: 'anki'}, String(init.body));
                return new Response(body, {status: 200, headers: {'Content-Type': 'application/json'}});
            }
            return pageFetch(input, init);
        };
    }

    // Spike timing: when Yomitan's popup has drawn a lookup's results (roadmap 2.1 measures tap to popup).
    if (path === '/popup.html' || path === '/search.html') {
        // Yomitan replaces the entries in one step, so a new lookup shows as a new first entry.
        let lastFirst = null;
        const watch = () => {
            const entries = document.getElementById('dictionary-entries');
            if (!entries) { return; }
            new MutationObserver(() => {
                const first = entries.firstElementChild;
                if (first === null || first === lastFirst) { return; }
                lastFirst = first;
                requestAnimationFrame(() => requestAnimationFrame(() => {
                    post({t: 'result', name: 'popup_shown'}, JSON.stringify({at: performance.timeOrigin + performance.now(), entries: entries.childElementCount}));
                    // Where the first entry's "add note" button is, in the reader's CSS pixels.
                    const locateButton = (tries) => {
                        const button = first.querySelector('.action-button[data-action="save-note"]');
                        if (!button || button.hidden || button.offsetParent === null) {
                            if (tries > 0) { setTimeout(() => locateButton(tries - 1), 100); }
                            return;
                        }
                        const frame = window.frameElement?.getBoundingClientRect() ?? {left: 0, top: 0};
                        const r = button.getBoundingClientRect();
                        post({t: 'rects', name: 'popup_buttons'}, JSON.stringify({addNote: {x: frame.left + r.left + r.width / 2, y: frame.top + r.top + r.height / 2}}));
                    };
                    locateButton(30);
                }));
            }).observe(entries, {childList: true});
        };
        document.addEventListener('DOMContentLoaded', watch);
    }

    globalThis.addEventListener('error', (e) => log('error', `uncaught: ${e.message} at ${e.filename}:${e.lineno}`));
    globalThis.addEventListener('unhandledrejection', (e) => log('error', `unhandled rejection: ${e.reason?.stack ?? e.reason}`));
})();
