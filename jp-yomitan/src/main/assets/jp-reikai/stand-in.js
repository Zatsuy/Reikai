/*
 * Reikai JP: the browser-extension stand-in for Yomitan (roadmap 3.1).
 * Copyright (C) 2026 Reikai JP contributors. GPL-3.0-or-later.
 *
 * Yomitan's own files run unmodified in the app's WebViews; this script is what a browser would give
 * them. YomitanScripts.kt injects it at document start into every frame of the engine origin (and of
 * a reader's chapter origin, Phase 4) as `(<this function>)(config)`, before any of Yomitan's
 * scripts, never into workers. It provides the `chrome.*` API Yomitan uses, backed by the app
 * through the `reikaiHub` web message listener (YomitanHub.kt).
 *
 * Wire format, both ways: one string per message, `<header JSON>\n<payload>`; the app parses only the
 * header. A response body may follow its header as one binary (ArrayBuffer) message.
 *   page -> app: hello, bye, send, tabsend, resp, connect, pmsg, pdisc, req (storage, tabs, open,
 *                fetch), fabort, nresp, trip, called, log
 *   app -> page: welcome, msg, reply, onconnect, pmsg, pdisc, fres, nreq
 * The app decides what each document may do from facts the page cannot fake (which WebView, which
 * origin, main frame or not); the `mode` below only shapes this document's local behaviour.
 *
 * `config`: {origin, manifest, kind ('engine' | 'settings' | 'search' | 'popup' | 'reader'), debug}.
 */
(function reikaiStandIn(config) {
    'use strict';
    if (globalThis.__reikaiStandIn) { return; }
    const hub = globalThis.reikaiHub;
    if (typeof hub === 'undefined') { return; }
    globalThis.__reikaiStandIn = true;

    const ORIGIN = config.origin;
    const EXTENSION_ID = 'reikai-yomitan';
    const isEngineOrigin = location.origin === ORIGIN;
    const isTop = globalThis.top === globalThis;
    /** @type {'backend'|'page'|'frame'|'content'} */
    const mode = !isEngineOrigin ? 'content' : (!isTop ? 'frame' : (config.kind === 'engine' ? 'backend' : 'page'));

    // --- transport -------------------------------------------------------------------------------

    const post = (header, payload = '') => hub.postMessage(`${JSON.stringify(header)}\n${payload}`);
    const log = (level, text) => post({t: 'log', level}, String(text));

    let tabId = -1;
    let frameId = 0;
    let nextMid = 1;
    /** @type {Map<number, (err: string|undefined, payload: string|ArrayBuffer, header: object) => void>} */
    const waiting = new Map();
    /** @type {Map<string, (header: object, payload: string) => void>} */
    const handlers = new Map();
    /** A header whose body arrives as the next, binary message. */
    let binaryHeader = null;

    const settle = (header, payload) => {
        const done = waiting.get(header.mid);
        waiting.delete(header.mid);
        if (done) { done(header.err, payload, header); }
    };

    hub.addEventListener('message', (event) => {
        const data = event.data;
        if (typeof data !== 'string') {
            const header = binaryHeader;
            binaryHeader = null;
            if (header) { settle(header, data); }
            return;
        }
        const cut = data.indexOf('\n');
        const header = JSON.parse(cut < 0 ? data : data.slice(0, cut));
        const payload = cut < 0 ? '' : data.slice(cut + 1);
        switch (header.t) {
            case 'welcome':
                tabId = header.tabId;
                frameId = header.frameId;
                break;
            case 'reply':
                if (header.bin) { binaryHeader = header; } else { settle(header, payload); }
                break;
            case 'msg':
                deliver(decode(payload), makeSender(header.sender), (has, value) => {
                    post({t: 'resp', rid: header.rid, has}, has && value !== undefined ? JSON.stringify(value) : '');
                });
                break;
            default: {
                const handler = handlers.get(header.t);
                if (handler) { handler(header, payload); }
            }
        }
    });
    const hello = () => post({t: 'hello', url: location.href});
    hello();
    addEventListener('pagehide', () => post({t: 'bye'}));
    addEventListener('pageshow', (event) => { if (event.persisted) { hello(); } });

    /** Sends a request the app answers; resolves with the raw payload (a string or an ArrayBuffer). */
    const request = (header, payload = '') => new Promise((resolve, reject) => {
        const mid = nextMid++;
        waiting.set(mid, (err, reply, replyHeader) => (err ? reject(new Error(err)) : resolve({payload: reply, header: replyHeader})));
        post({...header, mid}, payload);
    });

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

    // --- things that exist only so Yomitan finds them; each reports its first use ------------------

    const calledOnce = new Set();
    const called = (path) => {
        if (calledOnce.has(path)) { return; }
        calledOnce.add(path);
        post({t: 'called', path});
    };
    /** Returns `value` through a trailing callback, or as a promise. */
    const answer = (value, path) => (...args) => {
        if (path) { called(path); }
        const last = args[args.length - 1];
        if (typeof last === 'function') { withLastError(undefined, () => last(value)); return undefined; }
        return Promise.resolve(value);
    };
    const noop = (path) => answer(undefined, path);

    // --- messaging -------------------------------------------------------------------------------

    const decode = (payload) => (payload === '' ? undefined : JSON.parse(payload));

    /** Sends a message; `callback(response)` or a promise, with chrome's lastError semantics. */
    const sendThrough = (header, message, callback) => {
        const mid = nextMid++;
        const promise = typeof callback === 'function' ? null : new Promise((resolve, reject) => {
            callback = (response) => (lastError ? reject(new Error(lastError.message)) : resolve(response));
        });
        waiting.set(mid, (err, payload) => withLastError(err, () => callback(err ? undefined : decode(payload))));
        const action = message && typeof message.action === 'string' ? message.action : undefined;
        post({...header, mid, action}, JSON.stringify(message));
        return promise ?? undefined;
    };

    const makeSender = (from) => {
        const sender = {id: EXTENSION_ID, url: from.url, origin: new URL(from.url).origin, frameId: from.frameId};
        if (from.tabId >= 0) {
            sender.tab = {id: from.tabId, index: 0, windowId: 1, active: true, incognito: false, url: from.url};
        }
        return sender;
    };

    /**
     * Hands a message to this document's onMessage listeners; `respond(true, value)` once with the
     * first response, or `respond(false)` when no listener will answer.
     */
    const deliver = (message, sender, respond) => {
        let responded = false;
        let keepOpen = false;
        const sendResponse = (response) => {
            if (responded) { return; }
            responded = true;
            respond(true, response);
        };
        const noResponse = () => {
            if (responded) { return; }
            responded = true;
            respond(false);
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
                    (value) => (value === undefined ? noResponse() : sendResponse(value)),
                    (e) => sendResponse({error: {message: String(e?.message ?? e)}}),
                );
            }
        }
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
        post(header);
        return port;
    };
    handlers.set('onconnect', (header) => {
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
        withLastError(header.err, () => port.onDisconnect.dispatch(port));
    });

    // --- storage: both areas live in the app (local is backed up with its settings) ---------------

    const makeArea = (area) => {
        const call = (op, value, args) => {
            const last = args[args.length - 1];
            const result = request({t: 'req', op, area}, value === undefined ? '' : JSON.stringify(value))
                .then(({payload}) => (payload === '' ? undefined : JSON.parse(payload)));
            if (typeof last !== 'function') { return result; }
            result.then(
                (value2) => withLastError(undefined, () => last(value2)),
                (e) => withLastError(String(e?.message ?? e), () => last(undefined)),
            );
            return undefined;
        };
        return {
            // chrome.storage.X.get(null | string | string[] | {key: default})
            get: (...args) => {
                const query = typeof args[0] === 'function' ? null : (args[0] ?? null);
                const keys = query === null ? null : (typeof query === 'string' ? [query] : (Array.isArray(query) ? query : Object.keys(query)));
                const last = args[args.length - 1];
                const result = request({t: 'req', op: 'sget', area}, JSON.stringify(keys)).then(({payload}) => {
                    const stored = JSON.parse(payload);
                    const out = {};
                    for (const [key, text] of Object.entries(stored)) { out[key] = JSON.parse(text); }
                    if (query !== null && typeof query === 'object' && !Array.isArray(query)) {
                        for (const [key, fallback] of Object.entries(query)) { if (!(key in out)) { out[key] = fallback; } }
                    }
                    return out;
                });
                if (typeof last !== 'function') { return result; }
                result.then((value) => withLastError(undefined, () => last(value)), (e) => withLastError(String(e?.message ?? e), () => last(undefined)));
                return undefined;
            },
            set: (items, ...rest) => {
                const encoded = {};
                for (const [key, value] of Object.entries(items)) { encoded[key] = JSON.stringify(value); }
                return call('sset', encoded, rest);
            },
            remove: (keys, ...rest) => call('sremove', Array.isArray(keys) ? keys : [keys], rest),
            clear: (...rest) => call('sclear', undefined, rest),
            getBytesInUse: answer(0),
            onChanged: makeEvent(),
        };
    };

    // --- opening pages: the app decides (settings screen, search screen, browser, or nothing) -----

    const open = (how, url) => request({t: 'req', op: 'open'}, JSON.stringify({how, url: url === undefined ? undefined : new URL(url, `${ORIGIN}/`).href}))
        .then(({payload}) => (payload === '' ? undefined : JSON.parse(payload)));
    const withCallback = (promise, args) => {
        const last = args[args.length - 1];
        if (typeof last !== 'function') { return promise; }
        promise.then((value) => withLastError(undefined, () => last(value)), (e) => withLastError(String(e?.message ?? e), () => last(undefined)));
        return undefined;
    };

    // --- the chrome.* object ---------------------------------------------------------------------

    const manifest = config.manifest;

    const runtime = {
        id: EXTENSION_ID,
        get lastError() { return lastError; },
        getURL: (p) => `${ORIGIN}/${String(p).replace(/^\//, '')}`,
        getManifest: () => manifest,
        getPlatformInfo: answer({os: 'android', arch: 'arm64', nacl_arch: 'arm'}),
        getContexts: answer([], 'chrome.runtime.getContexts'),
        sendMessage: (...args) => {
            // (message, callback) or (message, options, callback) or (extensionId, message, ...)
            const fnIndex = args.findIndex((a) => typeof a === 'function');
            const callback = fnIndex >= 0 ? args[fnIndex] : undefined;
            const rest = fnIndex >= 0 ? args.slice(0, fnIndex) : args;
            const message = rest.length >= 2 && typeof rest[0] === 'string' ? rest[1] : rest[0];
            return sendThrough({t: 'send'}, message, callback);
        },
        openOptionsPage: (...args) => withCallback(open('options'), args),
        connectNative: () => { called('chrome.runtime.connectNative'); throw new Error('Native messaging is not available in Reikai JP'); },
        onMessage,
        onConnect: makeEvent(),
        onInstalled: makeEvent(),
        onStartup: makeEvent(),
    };

    const tabFrom = ({id, url}) => ({id, url, index: 0, windowId: 1, active: true, incognito: false});
    const listTabs = () => request({t: 'req', op: 'tabs'}).then(({payload}) => JSON.parse(payload).map(tabFrom));

    const tabs = {
        sendMessage: (targetTab, message, ...rest) => {
            const callback = rest.find((a) => typeof a === 'function');
            const options = rest.find((a) => a && typeof a === 'object') ?? {};
            const header = {t: 'tabsend', tabId: targetTab};
            if (typeof options.frameId === 'number') { header.frameId = options.frameId; }
            return sendThrough(header, message, callback);
        },
        query: (...args) => withCallback(listTabs(), args),
        get: (id, ...rest) => {
            const callback = rest.find((a) => typeof a === 'function');
            const tab = listTabs().then((list) => list.find((t) => t.id === id));
            if (!callback) { return tab.then((t) => t ?? Promise.reject(new Error(`No tab with id: ${id}.`))); }
            void tab.then((value) => withLastError(value ? undefined : `No tab with id: ${id}.`, () => callback(value)));
            return undefined;
        },
        getCurrent: (...args) => answer(mode === 'backend' ? undefined : tabFrom({id: tabId, url: location.href}))(...args),
        create: (props, ...rest) => withCallback(open('tab', props?.url), rest),
        update: (...args) => {
            const props = args.find((a) => a && typeof a === 'object');
            return withCallback(open('update', props?.url), args);
        },
        remove: noop('chrome.tabs.remove'),
        getZoom: answer(1),
        captureVisibleTab: answer(undefined, 'chrome.tabs.captureVisibleTab'),
        connect,
        onZoomChange: makeEvent(),
    };

    const windows = {
        create: (data, ...rest) => {
            const url = Array.isArray(data?.url) ? data.url[0] : data?.url;
            return withCallback(open('window', url), rest);
        },
        get: answer(undefined, 'chrome.windows.get'),
        update: noop('chrome.windows.update'),
        getCurrent: answer({id: 1}),
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
        storage: {local: makeArea('local'), session: makeArea('session'), onChanged: makeEvent()},
        permissions,
        tabs,
        windows,
        action: {
            setBadgeText: noop(), setBadgeBackgroundColor: noop(), setTitle: noop(), setIcon: noop(),
        },
        commands: {onCommand: makeEvent(), getAll: answer([])},
        contextMenus: {
            create: () => undefined, remove: noop(), removeAll: noop(), update: noop(), onClicked: makeEvent(),
        },
        omnibox: {onInputEntered: makeEvent(), onInputChanged: makeEvent(), setDefaultSuggestion: () => undefined},
        declarativeNetRequest: {
            // The app's network routing already strips cookies (request-builder.js's purpose for them).
            getSessionRules: answer([]), updateSessionRules: noop(),
            getDynamicRules: answer([]), updateDynamicRules: noop(),
        },
        scripting: {
            insertCSS: noop('chrome.scripting.insertCSS'), executeScript: answer([], 'chrome.scripting.executeScript'),
            registerContentScripts: noop(), unregisterContentScripts: noop(),
            getRegisteredContentScripts: answer([]), updateContentScripts: noop(),
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

    // --- workers ---------------------------------------------------------------------------------

    const NativeWorker = globalThis.Worker;
    const IMPORT_WORKER = '/js/dictionary/dictionary-worker-main.js';
    const DATABASE_WORKER = '/js/dictionary/dictionary-database-worker-main.js';
    const MEDIA_WORKER = '/js/display/media-drawing-worker.js';

    /** A worker that is never started: accepts everything, does nothing. */
    const inertWorker = (path) => {
        const target = new EventTarget();
        return Object.assign(target, {
            onmessage: null,
            onerror: null,
            postMessage: () => called(path),
            terminate: () => undefined,
        });
    };

    /**
     * Dictionary pictures. A page draws them in Yomitan's media worker, which reads the pictures
     * through a MessagePort to a database worker. In Chrome that port reaches the backend's database
     * worker through the service worker; a WebView has none. So each page runs its own copy of
     * Yomitan's database worker (same origin, same IndexedDB), started on its first picture, stopped
     * after a minute without one or when the database is deleted (it would block the deletion).
     */
    const mediaDatabase = (() => {
        const IDLE_MS = 60_000;
        /** @type {Worker|null} */
        let databaseWorker = null;
        /** @type {Worker|null} */
        let mediaWorker = null;
        /** The media worker's port to a database, until a database worker takes it. */
        let heldPort = null;
        let started = false;
        let idleTimer = 0;
        const stop = () => {
            clearTimeout(idleTimer);
            databaseWorker?.terminate();
            databaseWorker = null;
        };
        const start = () => {
            started = true;
            databaseWorker = new NativeWorker('/__reikai/workers/dictionary-database-worker-main.js', {type: 'module'});
            databaseWorker.addEventListener('message', (event) => {
                // The prelude closed the database for a deletion or an upgrade elsewhere.
                if (event.data?.reikai === 'db-closed') { stop(); }
            });
            databaseWorker.addEventListener('error', (event) => log('error', `database worker: ${event.message}`));
            let port = heldPort;
            heldPort = null;
            if (port === null) {
                // The media worker's old port died with the previous database worker: give it a new one.
                const channel = new MessageChannel();
                NativeWorker.prototype.postMessage.call(mediaWorker, {action: 'connectToDatabaseWorker'}, [channel.port2]);
                port = channel.port1;
            }
            databaseWorker.postMessage({action: 'connectToDatabaseWorker'}, [port]);
        };
        return {
            /** navigator.serviceWorker's stand-in received the page's port for the media worker. */
            connect: (port) => {
                // A port arriving after a first picture has nobody left on its other end.
                if (started) { port.close(); } else { heldPort = port; }
            },
            setMediaWorker: (worker) => { mediaWorker = worker; },
            /** A drawMedia request is about to reach the media worker. */
            touch: () => {
                if (databaseWorker === null && mediaWorker !== null) { start(); }
                clearTimeout(idleTimer);
                idleTimer = setTimeout(stop, IDLE_MS);
            },
        };
    })();

    if (typeof NativeWorker === 'function') {
        const ReikaiWorker = function Worker(url, options) {
            if (!new.target) { throw new TypeError("Failed to construct 'Worker': Please use the 'new' operator."); }
            const target = new URL(String(url), location.href);
            if (target.origin !== location.origin) {
                // A chapter page (Phase 4): Yomitan's scripts ask for engine-origin workers, which a
                // page of another origin can never start.
                return inertWorker(`Worker ${target.pathname}`);
            }
            if (target.origin === ORIGIN) {
                if (mode === 'backend' && target.pathname === DATABASE_WORKER) {
                    // Pages draw their pictures through their own database worker (above).
                    return inertWorker('Worker backend database worker');
                }
                if (target.pathname === IMPORT_WORKER) {
                    // A worker never starts a worker in WebView, and zip.js would: this entry makes
                    // zip.js inflate inside the import worker itself.
                    target.pathname = '/__reikai/workers/dictionary-worker-main.js';
                }
            }
            const worker = new NativeWorker(target.href, options);
            if (target.origin === ORIGIN && target.pathname === MEDIA_WORKER) {
                mediaDatabase.setMediaWorker(worker);
                worker.postMessage = (message, transfer) => {
                    if (message?.action === 'drawMedia') { mediaDatabase.touch(); }
                    NativeWorker.prototype.postMessage.call(worker, message, transfer);
                };
            }
            return worker;
        };
        ReikaiWorker.prototype = NativeWorker.prototype;
        globalThis.Worker = ReikaiWorker;
    }

    // Yomitan's pages hand the backend a port for their media worker through the service worker, as
    // in Chrome ('serviceWorker' in navigator must stay true for that path); this stand-in takes it.
    if (mode === 'page' || mode === 'frame') {
        const registration = {
            active: {
                postMessage: (message, transfer) => {
                    if (message?.action === 'connectToDatabaseWorker' && transfer?.[0]) {
                        mediaDatabase.connect(transfer[0]);
                    } else {
                        called(`navigator.serviceWorker ${message?.action}`);
                    }
                },
            },
        };
        const container = {
            ready: Promise.resolve(registration),
            controller: null,
            register: () => Promise.reject(new Error('Service workers are not available in Reikai JP')),
            getRegistration: () => Promise.resolve(undefined),
            getRegistrations: () => Promise.resolve([]),
            startMessages: () => undefined,
            addEventListener: () => undefined,
            removeEventListener: () => undefined,
        };
        Object.defineProperty(navigator, 'serviceWorker', {value: container, configurable: true});
    }

    // The backend opens a SharedWorker bridge when it runs as a page (Yomitan's Firefox path); a
    // WebView has no SharedWorker, and the bridge is not needed (pages reach the backend above).
    if (typeof SharedWorker !== 'function') {
        globalThis.SharedWorker = class SharedWorker extends EventTarget {
            constructor() {
                super();
                this.port = new MessageChannel().port1;
                this.onerror = null;
            }
        };
    }

    // --- network: what a page cannot fetch itself goes through the app -----------------------------

    // Cross-origin requests (dictionaries' audio sources, AnkiConnect at localhost:8765) fail in a
    // WebView without CORS, and a WebView cannot read a POST body; the app runs them (without cookies)
    // when this document may use the network, and refuses them otherwise.
    if (mode === 'backend' || mode === 'page') {
        const pageFetch = globalThis.fetch.bind(globalThis);
        const toBase64 = (bytes) => {
            let text = '';
            for (let i = 0; i < bytes.length; i += 0x8000) { text += String.fromCharCode(...bytes.subarray(i, i + 0x8000)); }
            return btoa(text);
        };
        const encodeBody = async (body, headers) => {
            if (body === undefined || body === null) { return {}; }
            if (typeof body === 'string') { return {body}; }
            if (body instanceof URLSearchParams) {
                if (!headers['content-type']) { headers['content-type'] = 'application/x-www-form-urlencoded;charset=UTF-8'; }
                return {body: body.toString()};
            }
            if (body instanceof Blob) { return {bodyBase64: toBase64(new Uint8Array(await body.arrayBuffer()))}; }
            if (body instanceof ArrayBuffer) { return {bodyBase64: toBase64(new Uint8Array(body))}; }
            if (ArrayBuffer.isView(body)) { return {bodyBase64: toBase64(new Uint8Array(body.buffer, body.byteOffset, body.byteLength))}; }
            throw new TypeError('Reikai JP cannot send this kind of request body');
        };
        globalThis.fetch = async (input, init = {}) => {
            const isRequest = typeof Request === 'function' && input instanceof Request;
            const url = new URL(isRequest ? input.url : String(input), location.href);
            if (url.origin === ORIGIN || (url.protocol !== 'http:' && url.protocol !== 'https:')) {
                return pageFetch(input, init);
            }
            const headers = {};
            new Headers(init.headers ?? (isRequest ? input.headers : undefined)).forEach((value, key) => { headers[key] = value; });
            const method = (init.method ?? (isRequest ? input.method : 'GET')).toUpperCase();
            const body = await encodeBody(init.body, headers);
            const signal = init.signal ?? (isRequest ? input.signal : undefined);
            if (signal?.aborted) { throw signal.reason ?? new DOMException('The user aborted a request.', 'AbortError'); }
            const mid = nextMid++;
            const reply = new Promise((resolve, reject) => {
                waiting.set(mid, (err, payload, header) => (err ? reject(new TypeError(`Failed to fetch (${err})`)) : resolve({payload, header})));
                signal?.addEventListener('abort', () => {
                    if (!waiting.delete(mid)) { return; }
                    post({t: 'fabort', fid: mid});
                    reject(signal.reason ?? new DOMException('The user aborted a request.', 'AbortError'));
                }, {once: true});
            });
            post({t: 'req', op: 'fetch', mid}, JSON.stringify({url: url.href, method, headers, ...body}));
            const {payload, header} = await reply;
            const bytes = typeof payload === 'string' ? Uint8Array.from(atob(payload), (c) => c.charCodeAt(0)) : payload;
            const nullBody = header.status === 204 || header.status === 205 || header.status === 304 || method === 'HEAD';
            const response = new Response(nullBody ? null : bytes, {status: header.status, statusText: header.statusText ?? '', headers: header.headers ?? {}});
            Object.defineProperty(response, 'url', {value: header.url ?? url.href});
            return response;
        };
    }

    // --- requests from the app itself (Kotlin's YomitanEngine.api and findTerms) -------------------

    if (mode === 'backend') {
        const nativeSender = {id: EXTENSION_ID, url: `${ORIGIN}/__reikai/native`, origin: ORIGIN, frameId: 0};
        const callBackend = (message) => new Promise((resolve) => {
            deliver(message, nativeSender, (has, value) => resolve(has ? value : undefined));
        });
        handlers.set('nreq', (header, payload) => {
            const reply = (value, err) => post({t: 'nresp', nid: header.nid, err}, value === undefined ? '' : JSON.stringify(value));
            let body;
            try { body = JSON.parse(payload); } catch (e) { reply(undefined, `bad request: ${e}`); return; }
            if (header.op === 'api') {
                callBackend({action: body.action, params: body.params}).then((response) => reply(response));
            } else if (header.op === 'findTerms') {
                // Only what native code needs: the longest match's length and its headwords.
                const params = {text: body.text, details: {}, optionsContext: body.optionsContext ?? {current: true}};
                callBackend({action: 'termsFind', params}).then((response) => {
                    if (!response || response.error) { reply(undefined, response?.error?.message ?? 'no response'); return; }
                    const {dictionaryEntries, originalTextLength} = response.result;
                    const limit = body.limit ?? 5;
                    reply({
                        length: originalTextLength,
                        entries: dictionaryEntries.slice(0, limit).map((entry) => ({
                            headwords: entry.headwords.map(({term, reading, sources}) => ({
                                term,
                                reading,
                                matched: sources.find((s) => s.isPrimary)?.originalText ?? sources[0]?.originalText ?? '',
                            })),
                        })),
                    });
                });
            } else {
                reply(undefined, `unknown request ${header.op}`);
            }
        });
    }

    globalThis.addEventListener('error', (e) => log('error', `uncaught: ${e.message} at ${e.filename}:${e.lineno}`));
    globalThis.addEventListener('unhandledrejection', (e) => log('error', `unhandled rejection: ${e.reason?.stack ?? e.reason}`));
})
