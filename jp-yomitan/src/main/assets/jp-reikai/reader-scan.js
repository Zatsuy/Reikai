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
 * character (for a tap on a shown reading, at its word's first character). This searches there
 * through the scanner's public search(), so Yomitan finds the longest word (ruby readings skipped,
 * okurigana followed through <ruby>), cuts the sentence by its own rules and selects the word (its
 * "select matched text" setting): the highlight.
 *
 * A tap inside a word looks up the whole word, as a long-press does in the other readers
 * (JapaneseText.selectWord): the search starts where the word the browser's Japanese segmenter finds
 * there begins, or, for a lone kanji or kana it split off a stem with a kanji in it ("打ち|込"), where
 * that stem begins, when the dictionary's word from there covers it; otherwise at the tapped
 * character itself, as Yomitan does on a desktop.
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
let layoutAwareScan = false;

/** How far around the tapped character a word is looked for. */
const WORD_REACH = 16;
const segmenter = typeof Intl.Segmenter === 'function' ? new Intl.Segmenter('ja', {granularity: 'word'}) : null;
/** Kanji, kana and the marks inside words (JapaneseText.isWordChar). */
const WORD_CHAR = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}々〆〇ーｰヵヶ]/u;
const KANA = /[\p{Script=Hiragana}\p{Script=Katakana}]/u;
const KANJI = /\p{Script=Han}/u;

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
/**
 * While a search from a word's start is tried: the length its word must exceed to be taken (it must
 * reach the tapped character, or past a split-off piece); null for the last try, which is always taken.
 * @type {?number}
 */
let mustExceed = null;

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
    layoutAwareScan = scanning.layoutAwareScan;
}

/**
 * Where to search from for a tap at `source` (collapsed before the tapped character): tries of
 * `{back, exceed}`, `back` characters before the tapped one, taken when the word found is longer than
 * `exceed`; the last try is the tapped character itself.
 */
function searchStarts(source) {
    const tries = [];
    if (segmenter !== null) {
        const around = source.clone();
        const before = around.setStartOffset(WORD_REACH, layoutAwareScan);
        around.setEndOffset(WORD_REACH, true, layoutAwareScan);
        const chars = [...around.text()];
        if (before < chars.length && WORD_CHAR.test(chars[before])) {
            let runStart = before;
            while (runStart > 0 && WORD_CHAR.test(chars[runStart - 1])) { --runStart; }
            let runEnd = before + 1;
            while (runEnd < chars.length && WORD_CHAR.test(chars[runEnd])) { ++runEnd; }
            // The segmenter's pieces of the run, in characters from the run's start.
            const pieces = [];
            let at = 0;
            for (const {segment} of segmenter.segment(chars.slice(runStart, runEnd).join(''))) {
                const piece = [...segment];
                pieces.push({start: at, end: at + piece.length, piece});
                at += piece.length;
            }
            const tapped = before - runStart;
            const own = pieces.find((p) => p.start <= tapped && tapped < p.end);
            if (own) {
                // A piece split off a stem with a kanji: okurigana (all kana) or a lone kanji the
                // segmenter does not know as part of a word; kana after kana is left alone.
                const split = own.piece.every((c) => KANA.test(c)) || (own.piece.length === 1 && KANJI.test(own.piece[0]));
                const stem = split ? pieces.find((p) => p.end === own.start) : undefined;
                if (stem && stem.piece.some((c) => KANJI.test(c))) {
                    tries.push({back: tapped - stem.start, exceed: own.end - stem.start - 1});
                }
                if (own.start < tapped) { tries.push({back: tapped - own.start, exceed: tapped - own.start}); }
            }
        }
    }
    tries.push({back: 0, exceed: null});
    return tries;
}

function onFound({type, sentence, textSource}) {
    const query = textSource.text();
    if (mustExceed !== null && [...query].length <= mustExceed) { return; }
    answered = true;
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

/** The scanner found nothing: a try from a word's start gives way to the next one. */
function onScannerEmpty() {
    if (mustExceed === null) { onEmpty(); }
}

function onError({error}) {
    answered = true;
    post({t: 'error', message: String(error?.message ?? error)});
}

/** Searches at client point (x, y); the scanner reports the outcome through its events. */
async function searchAt(x, y) {
    answered = false;
    mustExceed = null;
    // A new tap replaces the word shown, also when it is the same word (the scanner would otherwise
    // ignore a search starting where its current one starts).
    scanner.clearSelection();
    const textSource = generator.getRangeFromPoint(x, y, pointOptions);
    if (textSource === null) {
        onEmpty();
        return;
    }
    try {
        for (const {back, exceed} of searchStarts(textSource)) {
            const from = textSource.clone();
            if (back > 0) { from.setStartOffset(back, layoutAwareScan); }
            mustExceed = exceed;
            await scanner.search(from, null, false, false);
            if (answered) { return; }
            // A word that ended before the tapped character: its highlight goes before the next try.
            scanner.clearSelection();
        }
    } finally {
        mustExceed = null;
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
        textScanner.on('searchEmpty', onScannerEmpty);
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
