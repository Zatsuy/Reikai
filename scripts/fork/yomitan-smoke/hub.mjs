/*
 * Reikai JP: the app's hub for the Yomitan smoke test (roadmap 3.6). GPL-3.0-or-later.
 *
 * A JavaScript copy of jp-yomitan's YomitanHub.kt, the browser's half of the extension stand-in: it
 * routes chrome.runtime and chrome.tabs messages and ports between Yomitan's documents (one browser
 * page per WebView), answers storage, tab and network requests, and settles every request whose
 * document went away. Same wire format (`<header JSON>\n<payload>`, see stand-in.js), same roles
 * and rules. Keep it in step with YomitanHub.kt: the smoke test proves the stand-in only as far as
 * this copy behaves like the real hub.
 *
 * `host`: {storage: Map, openPage(how, url), fetch(request) -> Promise<{status,...}|{failed}>,
 * onBackendReady(), onTripwire(path, called, url), log(level, text)}.
 */

export const ORIGIN = 'https://yomitan.reikai.invalid';
export const NO_RECEIVER = 'Could not establish connection. Receiving end does not exist.';
export const PORT_CLOSED = 'The message port closed before a response was received.';

/** YomitanHub.CONTENT_ACTIONS: what a chapter page's content script may send. */
const CONTENT_ACTIONS = new Set([
    'applicationReady', 'requestBackendReadySignal', 'heartbeat', 'frameInformationGet',
    'optionsGet', 'termsFind', 'kanjiFind', 'isTextLookupWorthy', 'getZoom', 'getEnvironmentInfo',
    'getStylesheetContent', 'injectStylesheet', 'logGenericErrorBackend', 'broadcastTab',
    'sendMessageToFrame', 'openCrossFramePort', 'getOrCreateSearchPopup', 'isTabSearchPopup',
]);

const actionOf = (payload) => {
    try { return JSON.parse(payload)?.action; } catch { return undefined; }
};

export class Hub {
    constructor(host) {
        this.host = host;
        this.views = new Map();
        /** @type {Map<string, object>} documents by key */
        this.docs = new Map();
        this.pending = new Map();
        this.ports = new Map();
        this.fetches = new Map();
        this.natives = new Map();
        this.dead = [];
        this.nextViewId = 1;
        this.nextRid = 1;
        this.nextNid = 1;
        /** chrome.storage.session */
        this.session = new Map();
    }

    registerView(kind) {
        const id = this.nextViewId++;
        this.views.set(id, {kind, nextFrameId: 1});
        return id;
    }

    get hasBackend() { return [...this.docs.values()].some((d) => d.role === 'BACKEND'); }

    /** Asks the backend to run `op` ('api' or 'findTerms'); resolves with the response JSON text. */
    callBackend(op, payload) {
        return new Promise((resolve, reject) => {
            const backend = [...this.docs.values()].find((d) => d.role === 'BACKEND');
            if (!backend) { reject(new Error('The engine is not running')); return; }
            const nid = this.nextNid++;
            this.natives.set(nid, {doc: backend, resolve, reject});
            this.post(backend, {t: 'nreq', nid, op}, payload);
            this.flushDead();
        });
    }

    /** A message from a document; `sourceOrigin` and `isMainFrame` come from the browser, not the page. */
    onMessage(viewId, sourceOrigin, isMainFrame, key, port, data) {
        const cut = data.indexOf('\n') < 0 ? data.length : data.indexOf('\n');
        let header;
        try { header = JSON.parse(data.slice(0, cut)); } catch { return; }
        const payload = cut < data.length ? data.slice(cut + 1) : '';
        if (typeof header?.t !== 'string') { return; }
        if (header.t === 'hello') {
            this.hello(viewId, sourceOrigin, isMainFrame, key, port, header);
        } else {
            const from = this.docs.get(key);
            if (from) { this.handle(from, header.t, header, payload); }
        }
        this.flushDead();
    }

    hello(viewId, sourceOrigin, isMainFrame, key, port, header) {
        const view = this.views.get(viewId);
        if (!view) { return; }
        const old = this.docs.get(key);
        if (old) { this.forget(old); }
        if (isMainFrame) { [...this.docs.values()].filter((d) => d.viewId === viewId).forEach((d) => this.forget(d)); }
        const role = sourceOrigin.replace(/\/$/, '') !== ORIGIN ? 'CONTENT' :
            (!isMainFrame ? 'FRAME' : (view.kind === 'engine' ? 'BACKEND' : 'PAGE'));
        const frameId = isMainFrame ? 0 : view.nextFrameId++;
        const tabId = role === 'BACKEND' ? -1 : viewId;
        const doc = {key, port, viewId, kind: view.kind, role, tabId, frameId, url: header.url ?? ''};
        doc.trusted = role === 'BACKEND' || (role === 'PAGE' && (view.kind === 'settings' || view.kind === 'search'));
        this.docs.set(key, doc);
        this.post(doc, {t: 'welcome', tabId, frameId, role: role.toLowerCase()});
    }

    handle(from, type, header, payload) {
        switch (type) {
            case 'bye': this.forget(from); break;
            case 'send': this.send(from, header, payload); break;
            case 'tabsend': this.tabSend(from, header, payload); break;
            case 'resp': this.respond(from, header, payload); break;
            case 'connect': this.connect(from, header); break;
            case 'pmsg': case 'pdisc': this.portMessage(from, type, header, payload); break;
            case 'req': this.request(from, header, payload); break;
            case 'fabort': {
                const id = `${from.key}#${header.fid}`;
                const cancel = this.fetches.get(id);
                this.fetches.delete(id);
                cancel?.();
                break;
            }
            case 'nresp': this.nativeResponse(from, header, payload); break;
            case 'trip': case 'called':
                if (typeof header.path === 'string') { this.host.onTripwire(header.path, type === 'called', from.url); }
                break;
            case 'log':
                this.host.log(header.level === 'error' ? 'E' : (header.level === 'warn' ? 'W' : 'I'), `[${from.url.replace(ORIGIN, '')}] ${payload}`);
                break;
        }
    }

    // --- chrome.runtime.sendMessage and chrome.tabs.sendMessage ------------------------------------

    send(from, header, payload) {
        const mid = header.mid;
        if (typeof mid !== 'number') { return; }
        if (from.role === 'CONTENT' && !CONTENT_ACTIONS.has(actionOf(payload))) {
            this.replyError(from, mid, 'Reikai JP does not let a web page send this message');
            return;
        }
        if (from.role === 'BACKEND' && header.action === 'applicationBackendReady') { this.host.onBackendReady(); }
        this.route(from, mid, payload, (d) => d !== from && d.role !== 'CONTENT');
    }

    tabSend(from, header, payload) {
        const {mid, tabId: tab, frameId: frame} = header;
        if (typeof mid !== 'number' || typeof tab !== 'number') { return; }
        if (from.role === 'CONTENT' && tab !== from.tabId) {
            this.replyError(from, mid, 'Reikai JP does not let a web page message another tab');
            return;
        }
        this.route(from, mid, payload, (d) => d !== from && d.tabId === tab && d.role !== 'BACKEND' &&
            (typeof frame !== 'number' || d.frameId === frame));
    }

    route(from, mid, payload, accept) {
        const targets = [...this.docs.values()].filter(accept);
        if (targets.length === 0) { this.replyError(from, mid, NO_RECEIVER); return; }
        const rid = this.nextRid++;
        this.pending.set(rid, {from, mid, targets: new Set(targets)});
        const msg = {t: 'msg', rid, sender: this.senderOf(from)};
        for (const target of targets) { this.post(target, msg, payload); }
    }

    respond(from, header, payload) {
        const p = this.pending.get(header.rid);
        if (!p || !p.targets.has(from)) { return; }
        if (header.has === true) {
            this.pending.delete(header.rid);
            this.post(p.from, {t: 'reply', mid: p.mid}, payload);
        } else {
            p.targets.delete(from);
            if (p.targets.size === 0) {
                this.pending.delete(header.rid);
                this.replyError(p.from, p.mid, PORT_CLOSED);
            }
        }
    }

    // --- ports (chrome.tabs.connect) ----------------------------------------------------------------

    connect(from, header) {
        const {pid, tabId: tab, frameId: frame} = header;
        if (typeof pid !== 'string' || typeof tab !== 'number') { return; }
        const target = [...this.docs.values()].find((d) => d !== from && d.tabId === tab && d.role !== 'BACKEND' &&
            (typeof frame !== 'number' || d.frameId === frame));
        if (!target || (from.role === 'CONTENT' && tab !== from.tabId) || this.ports.has(pid)) {
            this.post(from, {t: 'pdisc', pid, err: NO_RECEIVER});
            return;
        }
        this.ports.set(pid, {opener: from, target});
        this.post(target, {t: 'onconnect', pid, name: header.name ?? '', sender: this.senderOf(from)});
    }

    portMessage(from, type, header, payload) {
        const ends = this.ports.get(header.pid);
        if (!ends) { return; }
        const other = from === ends.opener ? ends.target : (from === ends.target ? ends.opener : null);
        if (!other) { return; }
        if (type === 'pdisc') { this.ports.delete(header.pid); }
        this.post(other, {t: type, pid: header.pid}, type === 'pmsg' ? payload : '');
    }

    // --- requests the app answers -------------------------------------------------------------------

    request(from, header, payload) {
        const {mid, op} = header;
        if (typeof mid !== 'number') { return; }
        const needsTrust = op !== 'tabs' && op !== 'open';
        if (from.role === 'CONTENT' || (needsTrust && !from.trusted)) {
            this.replyError(from, mid, `Reikai JP does not let this page use ${op}`);
            return;
        }
        switch (op) {
            case 'tabs': {
                const tabs = [...this.docs.values()].filter((d) => d.frameId === 0 && d.role !== 'BACKEND')
                    .map((d) => ({id: d.tabId, url: d.url}));
                this.reply(from, mid, JSON.stringify(tabs));
                break;
            }
            case 'sget': case 'sset': case 'sremove': case 'sclear':
                this.storage(from, mid, op, header.area, payload);
                break;
            case 'open': {
                let body = null;
                try { body = JSON.parse(payload); } catch { /* none */ }
                const tab = this.host.openPage(body?.how ?? 'tab', body?.url);
                this.reply(from, mid, tab === undefined || tab === null ? '' : JSON.stringify(tab));
                break;
            }
            case 'fetch': this.fetch(from, mid, payload); break;
            default: this.replyError(from, mid, `unknown request ${op}`);
        }
    }

    storage(from, mid, op, area, payload) {
        let body = null;
        try { body = payload === '' ? null : JSON.parse(payload); } catch { /* none */ }
        if (area !== 'local' && area !== 'session') { this.replyError(from, mid, `unknown storage area ${area}`); return; }
        const store = area === 'local' ? this.host.storage : this.session;
        const keys = () => (Array.isArray(body) ? body.filter((k) => typeof k === 'string') : []);
        switch (op) {
            case 'sget': {
                const wanted = Array.isArray(body) ? new Set(keys()) : null;
                const values = {};
                for (const [k, v] of store) { if (!wanted || wanted.has(k)) { values[k] = v; } }
                this.reply(from, mid, JSON.stringify(values));
                return;
            }
            case 'sset':
                for (const [k, v] of Object.entries(body ?? {})) { store.set(k, String(v)); }
                break;
            case 'sremove': for (const k of keys()) { store.delete(k); } break;
            case 'sclear': store.clear(); break;
        }
        this.reply(from, mid, '');
    }

    fetch(from, mid, payload) {
        let body = null;
        try { body = JSON.parse(payload); } catch { /* none */ }
        if (!body || typeof body.url !== 'string') { this.replyError(from, mid, 'bad fetch request'); return; }
        const id = `${from.key}#${mid}`;
        let cancelled = false;
        this.fetches.set(id, () => { cancelled = true; });
        Promise.resolve(this.host.fetch(body)).then((result) => {
            if (cancelled || !this.fetches.delete(id) || this.docs.get(from.key) !== from) { return; }
            if (result.failed) {
                this.replyError(from, mid, result.failed);
            } else {
                const head = {t: 'reply', mid, status: result.status, statusText: result.statusText ?? '',
                    url: result.url ?? body.url, headers: result.headers ?? {}};
                const bytes = Buffer.from(result.body ?? []);
                if (from.port.binary) {
                    // WEB_MESSAGE_ARRAY_BUFFER: the body follows its header as one ArrayBuffer message.
                    head.bin = true;
                    if (this.post(from, head)) { this.postBytes(from, bytes); }
                } else {
                    this.post(from, head, bytes.toString('base64'));
                }
            }
            this.flushDead();
        });
    }

    nativeResponse(from, header, payload) {
        const call = this.natives.get(header.nid);
        if (!call || call.doc !== from) { return; }
        this.natives.delete(header.nid);
        if (typeof header.err === 'string') { call.reject(new Error(header.err)); } else { call.resolve(payload); }
    }

    // --- documents that went away --------------------------------------------------------------------

    forget(doc) {
        if (this.docs.get(doc.key) !== doc) { return; }
        this.docs.delete(doc.key);
        for (const [rid, p] of [...this.pending]) { if (p.from === doc) { this.pending.delete(rid); } }
        for (const [rid, p] of [...this.pending]) {
            if (p.targets.delete(doc) && p.targets.size === 0) {
                this.pending.delete(rid);
                this.replyError(p.from, p.mid, PORT_CLOSED);
            }
        }
        for (const [pid, ends] of [...this.ports]) {
            const other = doc === ends.opener ? ends.target : (doc === ends.target ? ends.opener : null);
            if (!other) { continue; }
            this.ports.delete(pid);
            this.post(other, {t: 'pdisc', pid});
        }
        for (const [id, cancel] of [...this.fetches]) {
            if (id.startsWith(`${doc.key}#`)) { this.fetches.delete(id); cancel(); }
        }
        for (const [nid, call] of [...this.natives]) {
            if (call.doc !== doc) { continue; }
            this.natives.delete(nid);
            call.reject(new Error('The engine stopped before it answered'));
        }
    }

    /** Every document of the view vanished (its page closed or navigated away). */
    forgetView(viewId) {
        [...this.docs.values()].filter((d) => d.viewId === viewId).forEach((d) => this.forget(d));
        this.flushDead();
    }

    flushDead() {
        while (this.dead.length > 0) { this.forget(this.dead.shift()); }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    senderOf(from) { return {tabId: from.tabId, frameId: from.frameId, url: from.url}; }

    reply(to, mid, payload) { this.post(to, {t: 'reply', mid}, payload); }

    replyError(to, mid, err) { this.post(to, {t: 'reply', mid, err}); }

    post(to, header, payload = '') {
        if (this.docs.get(to.key) !== to) { return false; }
        const ok = to.port.post(`${JSON.stringify(header)}\n${payload}`);
        if (!ok) { this.dead.push(to); }
        return ok;
    }

    postBytes(to, bytes) {
        if (this.docs.get(to.key) !== to) { return false; }
        const ok = to.port.postBytes(bytes);
        if (!ok) { this.dead.push(to); }
        return ok;
    }
}
