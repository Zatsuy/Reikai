/*
 * Reikai JP: drives Yomitan's own results page (popup.html) in the app's lookup sheet (roadmap 3.4).
 * Copyright (C) 2026 Reikai JP contributors. GPL-3.0-or-later.
 *
 * YomitanPopup.kt injects it at document start into the popup WebView's engine-origin main frame
 * as `(<this function>)()`, next to the stand-in. It uses only what a browser gives any page:
 * - Yomitan's page shows what its URL asks (`query`, and `full` with `offset`, which Anki's Sentence
 *   field falls back to), and keeps the rest of a lookup (the sentence, the page's address and title,
 *   the reader's light or dark theme) in `history.state`, which its DisplayHistory reads on
 *   `popstate`. `__reikaiPopup.show` replaces the history entry and fires `popstate`, as a browser's
 *   back button would, so a prepared page swaps lookups without loading.
 * - Standalone (no content page configures it), the page loads its settings only during its first
 *   lookup, after deciding whether any dictionary is enabled. So it starts with an empty lookup, and
 *   is "ready" once that has finished (Yomitan then shows "no dictionaries" or "no results"). Its
 *   theme is decided then too, from that lookup's state: the address's `#theme=dark` or `#theme=light`
 *   gives the page's theme, for Yomitan's default "match the page" popup theme.
 * - It tells the app (web message listener `reikaiPopup`) when it is ready, when a lookup is on
 *   screen (first result, or a notice) and when Yomitan's own close button is pressed.
 */
(function reikaiPopupHost() {
    'use strict';
    if (globalThis.top !== globalThis || location.pathname !== '/popup.html' || globalThis.__reikaiPopup) { return; }
    const channel = globalThis.reikaiPopup;
    if (typeof channel === 'undefined') { return; }
    const post = (message) => channel.postMessage(JSON.stringify(message));

    let ready = false;
    /** The lookup being shown: the app's token, and when it was asked for. */
    let token = 0;
    let startedAt = 0;
    let waiting = false;

    // The empty first lookup, read by Yomitan's first state change once its scripts run.
    const theme = /^#theme=(dark|light)$/.exec(location.hash)?.[1];
    history.replaceState({id: null, state: theme ? {pageTheme: theme} : null}, '', `${location.pathname}?type=terms&query=`);

    const shown = () => {
        if (!ready) {
            ready = true;
            post({t: 'ready'});
            return;
        }
        if (!waiting) { return; }
        waiting = false;
        const done = token;
        const ms = performance.now() - startedAt;
        // After the frame that paints it.
        requestAnimationFrame(() => setTimeout(() => post({t: 'shown', token: done, ms}), 0));
    };

    const swap = (url, state) => {
        history.replaceState({id: null, state}, '', url);
        dispatchEvent(new PopStateEvent('popstate', {state: history.state}));
    };

    globalThis.__reikaiPopup = {
        show(url, state, next) {
            token = next;
            startedAt = performance.now();
            waiting = true;
            swap(url, state);
        },
        clear() {
            waiting = false;
            swap(location.pathname, null);
        },
    };

    const watch = () => {
        const entries = document.querySelector('#dictionary-entries');
        if (entries !== null) {
            new MutationObserver(() => { if (entries.firstElementChild !== null) { shown(); } })
                .observe(entries, {childList: true});
        }
        for (const id of ['#no-results', '#no-dictionaries']) {
            const notice = document.querySelector(id);
            if (notice !== null) {
                new MutationObserver(() => { if (!notice.hidden) { shown(); } })
                    .observe(notice, {attributes: true, attributeFilter: ['hidden']});
            }
        }
        // Should a later Yomitan show neither notice for the empty lookup, the page is ready anyway.
        setTimeout(() => { if (!ready) { shown(); } }, 3000);
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
