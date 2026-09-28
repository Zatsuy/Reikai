/*
 * Reikai JP: Yomitan's own text scanner in the Japanese reader's chapter page (roadmap 4.3).
 * Copyright (C) 2026 Reikai JP contributors. GPL-3.0-or-later.
 *
 * Served at https://yomitan.reikai.invalid/__reikai/reader-scan.js; a chapter document at
 * https://chapter.reikai.invalid loads it as a module after jp-reader.js (its Content-Security-Policy
 * allows scripts from Yomitan's origin, whose server answers with Access-Control-Allow-Origin). The app
 * injects the stand-in into the chapter page first, where it runs in content mode: Yomitan's code here
 * reaches the engine through the app's hub with a content script's rights only (CONTENT_ACTIONS).
 *
 * It runs the scanning half of Yomitan's content script and nothing of its popup (the lookup sheet is
 * the popup): Application.main, a TextSourceGenerator and a TextScanner whose settings are Yomitan's
 * scanning and sentence settings applied as Frontend applies them (frontend.js _updateOptionsInternal),
 * again whenever Yomitan says its settings changed. The scanner is never enabled, so its own mouse and
 * touch listeners never run: jp-reader.js decides what a touch is (a tap on a character, furigana to
 * reveal, a margin, a swipe, a long press) and calls JpReader.onTextTap(x, y) for a tap on a
 * character. This searches at that point through the scanner's public search(), so Yomitan finds the
 * longest word from that character (ruby readings skipped, okurigana followed through <ruby>), cuts
 * the sentence by its own rules and selects the word (its "select matched text" setting): the
 * highlight.
 *
 * To the app, through the web message listener `reikaiReader` (chapter origin, main frame), JSON:
 *   {t: 'ready'}   Yomitan answered and its settings are applied
 *   {t: 'wait'}    a tap on text came before that; it is searched once ready (within 10 s)
 *   {t: 'found', type ('terms'|'kanji'), query, sentence: {text, offset}, full?, rects, writingMode}
 *                  rects: the word's client rects in CSS px, [{left, top, right, bottom}]
 *   {t: 'empty'}   a tap on text where Yomitan found nothing
 *   {t: 'error', message}   the search failed (the engine went away mid-search)
 * It connects when it loads and, when the engine was not running then, when the engine says it is
 * ready (Yomitan's backend tells every tab, `applicationBackendReady`).
 * From the app (evaluateJavascript), `__reikaiReader`:
 *   start()        connects now, if not yet connected
 *   clear()        removes the highlight (the sheet closed); a selection the reader made since is kept
 *   setContext({url, title})   the page Yomitan's profile conditions see
 */
import {Application} from '/js/application.js';
import {TextSourceElement} from '/js/dom/text-source-element.js';
import {TextSourceGenerator} from '/js/dom/text-source-generator.js';
import {TextScanner} from '/js/language/text-scanner.js';

const channel = globalThis.reikaiReader;
const post = (message) => {
    try {
        channel?.postMessage(JSON.stringify(message));
    } catch (e) {
        // The app went away mid-message; nothing to do.
    }
};

/** How long a tap made before Yomitan was ready is still worth searching. */
const WAIT_MS = 10_000;

const context = {url: location.href, title: document.title};

const generator = new TextSourceGenerator();
/** @type {?TextScanner} */
let scanner = null;
/** @type {?import('/js/application.js').Application} */
let application = null;
/** What getRangeFromPoint needs of Yomitan's settings (TextScanner keeps its own copy private). */
const pointOptions = {deepContentScan: false, normalizeCssZoom: true, language: null, browser: null};

/** Whether Yomitan answered and its settings are applied. */
let ready = false;
/** @type {?Promise<void>} */
let starting = null;
/** A tap made before the scanner was ready: {x, y, at}. */
let early = null;
/** A tap made while a search was running, searched after it. */
let queued = null;
let busy = false;
/** Whether the running search has told the app its outcome. */
let answered = false;

const round = (value) => Math.round(value * 100) / 100;

const getSearchContext = () => ({
    optionsContext: {depth: 0, url: context.url},
    detail: {documentTitle: context.title},
});

/** Yomitan's settings for the scanner, as frontend.js _updateOptionsInternal applies them. */
async function applyOptions() {
    const options = await application.api.optionsGet(getSearchContext().optionsContext);
    const {scanning, sentenceParsing, general} = options;
    scanner.language = general.language;
    scanner.setOptions({
        deepContentScan: scanning.deepDomScan,
        normalizeCssZoom: scanning.normalizeCssZoom,
        selectText: scanning.selectText,
        delay: scanning.delay,
        scanLength: scanning.length,
        layoutAwareScan: scanning.layoutAwareScan,
        sentenceParsingOptions: sentenceParsing,
        scanWithoutMousemove: scanning.scanWithoutMousemove,
        scanResolution: scanning.scanResolution,
    });
    pointOptions.deepContentScan = scanning.deepDomScan;
    pointOptions.normalizeCssZoom = scanning.normalizeCssZoom;
    pointOptions.language = general.language;
}

function onFound({type, sentence, textSource}) {
    answered = true;
    const query = textSource.text();
    /** @type {{[key: string]: unknown}} */
    const message = {
        t: 'found',
        type,
        query,
        sentence: {text: sentence.text, offset: sentence.offset},
        rects: [...textSource.getRects()].map(({left, top, right, bottom}) => ({
            left: round(left), top: round(top), right: round(right), bottom: round(bottom),
        })),
        writingMode: textSource.getWritingMode(),
    };
    // A picture's alt text (a gaiji): the whole text, as Frontend passes it (frontend.js _showContent).
    if (textSource instanceof TextSourceElement && textSource.fullContent !== query) {
        message.full = textSource.fullContent;
    }
    post(message);
}

function onEmpty() {
    answered = true;
    post({t: 'empty'});
}

function onError({error}) {
    answered = true;
    post({t: 'error', message: String(error?.message ?? error)});
}

/** Searches at client point (x, y); the scanner reports the outcome through its events. */
async function searchAt(x, y) {
    // A new tap replaces the word shown, also when it is the same word (the scanner would otherwise
    // ignore a search starting where its current one starts).
    scanner.clearSelection();
    answered = false;
    const textSource = generator.getRangeFromPoint(x, y, pointOptions);
    if (textSource === null) {
        onEmpty();
        return;
    }
    try {
        await scanner.search(textSource, null, false, false);
    } finally {
        textSource.cleanup();
    }
    if (!answered) { onEmpty(); }
}

async function tap(x, y) {
    if (busy) {
        queued = {x, y};
        return;
    }
    busy = true;
    try {
        /** @type {?{x: number, y: number}} */
        let point = {x, y};
        while (point !== null) {
            queued = null;
            try {
                await searchAt(point.x, point.y);
            } catch (e) {
                onError({error: e});
            }
            point = queued;
        }
    } finally {
        busy = false;
    }
}

/**
 * jp-reader.js calls this for a tap on a character in "look up" mode; true when the tap is taken (the
 * page then posts no `tap`).
 */
function onTextTap(x, y) {
    if (!ready) {
        early = {x, y, at: performance.now()};
        post({t: 'wait'});
        void start();
        return true;
    }
    void tap(x, y);
    return true;
}

function start() {
    if (ready) { return Promise.resolve(); }
    if (starting !== null) { return starting; }
    starting = Application.main(false, async (app) => {
        application = app;
        const textScanner = new TextScanner({
            api: app.api,
            node: window,
            getSearchContext,
            searchTerms: true,
            searchKanji: true,
            textSourceGenerator: generator,
        });
        textScanner.on('searchSuccess', onFound);
        textScanner.on('searchEmpty', onEmpty);
        textScanner.on('searchError', onError);
        scanner = textScanner;
        try {
            await applyOptions();
        } catch (e) {
            scanner = null;
            throw e;
        }
        app.on('optionsUpdated', () => { applyOptions().catch(() => { /* the next change tries again */ }); });
        ready = true;
    }).then(
        () => {
            // Application.main logs a failure inside this function and resolves anyway.
            starting = null;
            if (!ready) { return; }
            post({t: 'ready'});
            const pending = early;
            early = null;
            if (pending !== null && performance.now() - pending.at < WAIT_MS) { void tap(pending.x, pending.y); }
        },
        () => {
            // The engine is not running (nothing answered): tried again when it says it is ready.
            starting = null;
        },
    );
    return starting;
}

globalThis.__reikaiReader = {
    start,
    clear() {
        early = null;
        queued = null;
        if (!ready) { return; }
        const current = scanner.getCurrentTextSource();
        const selection = window.getSelection();
        const range = selection !== null && selection.rangeCount > 0 ? selection.getRangeAt(0) : null;
        const ours = current !== null && range !== null && current.range !== undefined &&
            range.compareBoundaryPoints(Range.START_TO_START, current.range) === 0 &&
            range.compareBoundaryPoints(Range.END_TO_END, current.range) === 0;
        if (current === null || ours) {
            scanner.clearSelection();
        } else {
            // The reader selected something else since (a long press): keep it, forget the word.
            scanner.setCurrentTextSource(null);
        }
    },
    setContext({url, title} = {}) {
        if (typeof url === 'string') { context.url = url; }
        if (typeof title === 'string') { context.title = title; }
        if (ready) { applyOptions().catch(() => { /* kept as they were */ }); }
    },
};

const reader = globalThis.JpReader ?? (globalThis.JpReader = {});
reader.onTextTap = onTextTap;

// A first try while the engine is not running fails; Yomitan's backend tells every tab when it is ready.
globalThis.chrome?.runtime?.onMessage?.addListener((message) => {
    if (message?.action === 'applicationBackendReady' && !ready) { void start(); }
    return false;
});
void start();
