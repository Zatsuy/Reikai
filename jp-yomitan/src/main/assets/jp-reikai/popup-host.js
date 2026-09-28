/*
 * Reikai JP: drives Yomitan's own results page (popup.html) in the app's lookup sheet (roadmap 3.4).
 * Copyright (C) 2026 Reikai JP contributors. GPL-3.0-or-later.
 *
 * YomitanPopup.kt injects it at document start into the popup WebView's engine-origin main frame
 * as `(<this function>)()`, after the stand-in. It uses only what a browser gives any page:
 * - Yomitan's page shows what its URL asks (`query`, and `full` with `offset`, which Anki's Sentence
 *   field falls back to), and keeps the rest of a lookup (the sentence, the page's address and title,
 *   the reader's light or dark theme) in `history.state`, which its DisplayHistory reads on
 *   `popstate`. `__reikaiPopup.show` replaces the history entry and fires `popstate`, as a browser's
 *   back button would, so a prepared page swaps lookups without loading.
 * - Standalone (no content page configures it), the page loads its settings only during its first
 *   lookup, after deciding whether any dictionary is enabled. So it starts with an empty lookup, and
 *   is "ready" once that has finished (Yomitan then shows "no dictionaries" or "no results"). Its
 *   theme is decided then too, from that lookup's state: the address's `reikai-theme` (dark or light)
 *   gives the page's theme, for Yomitan's default "match the page" popup theme; `reikai-load` tells
 *   this load's "ready" from an earlier one's.
 * - Lookups Yomitan makes inside the popup (a word tapped in a definition, with its "search in the
 *   popup" setting) say their page is light and is the popup itself, and a word picked from the
 *   parsed sentence above the results names no page at all: every history state the page writes
 *   keeps the reader's theme, and the reader's address and title for Anki's {url} and
 *   {document-title}.
 * - Yomitan tells every tab when its settings or dictionaries change; a standalone popup has no page
 *   to pass that on, so the app is told ("stale") and loads the page again when it is not in use.
 * - Yomitan's popup waits for the page that hosts it to connect (`frame-endpoint.js`) and until then
 *   logs "Invalid action" for every other window message (its card template renderer's): this
 *   connects as that page would.
 * - It tells the app (web message listener `reikaiPopup`) when it is ready, when a lookup is on
 *   screen (first result, or a notice), whether Yomitan's history has a previous lookup to go back to,
 *   and when Yomitan's own close button is pressed. "ready", "stale" and "nav" carry the load number,
 *   so the app ignores what an earlier load said after it moved on.
 */
(function reikaiPopupHost() {
    'use strict';
    if (globalThis.top !== globalThis || location.pathname !== '/popup.html' || globalThis.__reikaiPopup) { return; }
    const channel = globalThis.reikaiPopup;
    if (typeof channel === 'undefined') { return; }
    const post = (message) => channel.postMessage(JSON.stringify(message));

    const params = new URLSearchParams(location.search);
    const load = Number.parseInt(params.get('reikai-load') ?? '0', 10) || 0;
    const theme = /^(dark|light)$/.exec(params.get('reikai-theme') ?? '')?.[0] ?? null;

    let ready = false;
    /** The lookup being shown: the app's token, and when it was asked for. */
    let token = 0;
    let startedAt = 0;
    let waiting = false;
    /** Where the app's lookup on screen came from ({url, documentTitle}), or null. */
    let source = null;

    // Every history state the page writes (Yomitan's DisplayHistory keeps the very object) keeps the
    // reader's theme and, for a lookup made inside the popup (which names the popup, or no page:
    // display.js _onQueryParserSearch), the reader's page.
    const popupAddress = `${location.origin}/popup.html`;
    const adopt = (data) => {
        const state = data?.state;
        if (state === null || typeof state !== 'object') { return; }
        if (theme !== null) { state.pageTheme = theme; }
        const inPopup = typeof state.url !== 'string' || state.url.startsWith(popupAddress);
        if (source !== null && inPopup) {
            for (const key of ['url', 'documentTitle']) {
                if (typeof source[key] === 'string') { state[key] = source[key]; } else { delete state[key]; }
            }
        }
    };
    for (const name of ['pushState', 'replaceState']) {
        const native = History.prototype[name];
        History.prototype[name] = function reikaiHistory(data, ...rest) {
            adopt(data);
            return native.call(this, data, ...rest);
        };
    }

    // The empty first lookup, read by Yomitan's first state change once its scripts run.
    history.replaceState({id: null, state: {}}, '', `${location.pathname}?type=terms&query=`);

    const shown = () => {
        if (!ready) {
            ready = true;
            post({t: 'ready', load});
            return;
        }
        if (!waiting) { return; }
        waiting = false;
        const done = token;
        const ms = performance.now() - startedAt;
        // After the frame that paints it.
        requestAnimationFrame(() => setTimeout(() => post({t: 'shown', token: done, ms}), 0));
    };

    /** Yomitan's notices, hidden before each lookup so that showing one again is seen. */
    const NOTICES = ['#no-results', '#no-dictionaries'];
    const swap = (url, state) => {
        for (const id of NOTICES) {
            const notice = document.querySelector(id);
            if (notice !== null) { notice.hidden = true; }
        }
        history.replaceState({id: null, state}, '', url);
        dispatchEvent(new PopStateEvent('popstate', {state: history.state}));
    };

    globalThis.__reikaiPopup = {
        show(url, state, next) {
            token = next;
            startedAt = performance.now();
            waiting = true;
            source = {url: state?.url, documentTitle: state?.documentTitle};
            swap(url, state);
        },
        clear() {
            waiting = false;
            source = null;
            swap(location.pathname, {});
        },
        /** Yomitan's own "previous definition" button. */
        back() {
            document.querySelector('#navigate-previous-button')?.click();
        },
    };

    const onExtensionMessage = (message) => {
        const action = message?.action;
        if (action === 'applicationOptionsUpdated' || action === 'applicationDatabaseUpdated') {
            post({t: 'stale', load});
        } else if (action === 'frameEndpointReady' && typeof message.params?.secret === 'string') {
            const connect = {action: 'frameEndpointConnect', params: {secret: message.params.secret, token: crypto.randomUUID(), hostFrameId: 0}};
            postMessage(connect, location.origin);
        }
        return false;
    };
    globalThis.chrome?.runtime?.onMessage?.addListener(onExtensionMessage);

    const watch = () => {
        const entries = document.querySelector('#dictionary-entries');
        if (entries !== null) {
            new MutationObserver(() => { if (entries.firstElementChild !== null) { shown(); } })
                .observe(entries, {childList: true});
        }
        for (const id of NOTICES) {
            const notice = document.querySelector(id);
            if (notice !== null) {
                new MutationObserver(() => { if (!notice.hidden) { shown(); } })
                    .observe(notice, {attributes: true, attributeFilter: ['hidden']});
            }
        }
        // Should a later Yomitan show neither notice for the empty lookup, the page is ready anyway.
        setTimeout(() => { if (!ready) { shown(); } }, 3000);
        // Whether Yomitan's history has a lookup to go back to (the back gesture then goes there).
        let back = false;
        new MutationObserver(() => {
            const now = document.documentElement.dataset.hasNavigationPrevious === 'true';
            if (now !== back) {
                back = now;
                post({t: 'nav', back, load});
            }
        }).observe(document.documentElement, {attributes: true, attributeFilter: ['data-has-navigation-previous']});
        // Yomitan's close button asks a content page that is not there; the app closes the sheet.
        document.addEventListener('click', (e) => {
            if (e.target instanceof Element && e.target.closest('#close-button') !== null) { post({t: 'close'}); }
        }, true);
    };
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', watch, {once: true});
    } else {
        watch();
    }
})
