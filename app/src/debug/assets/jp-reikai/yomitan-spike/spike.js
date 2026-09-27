/*
 * Reikai JP: the Yomitan spike's driver page (roadmap 2.1). GPL-3.0-or-later.
 *
 * Talks to Yomitan's backend exactly as Yomitan's own pages do (its API class over the stand-in's
 * chrome.runtime), imports dictionaries with Yomitan's own importer, and times lookups. Commands
 * arrive from adb through the app (`__reikaiOn('cmd')`); results go to logcat as RESULT lines.
 */
import {API} from '/js/comm/api.js';
import {getKebabCase} from '/js/data/anki-template-util.js';
import {DictionaryDatabase} from '/js/dictionary/dictionary-database.js';
import {DictionaryImporterMediaLoader} from '/js/dictionary/dictionary-importer-media-loader.js';
import {DictionaryImporter} from '/js/dictionary/dictionary-importer.js';
import {WebExtension} from '/js/extension/web-extension.js';

const out = document.getElementById('log');
const say = (text) => { out.textContent += `${text}\n`; console.log(text); };
const post = (header, payload = '') => globalThis.reikaiHub.postMessage(`${JSON.stringify(header)}\n${payload}`);
const mark = (name) => post({t: 'mark', name});
const result = (name, value) => { say(`${name}: ${JSON.stringify(value)}`); post({t: 'result', name}, JSON.stringify(value)); };

const api = new API(new WebExtension());

async function waitForBackend() {
    const t0 = performance.now();
    await new Promise((resolve) => {
        const onMessage = (message, _sender, callback) => {
            if (message?.action === 'applicationBackendReady') {
                chrome.runtime.onMessage.removeListener(onMessage);
                resolve();
            }
            callback?.();
            return false;
        };
        chrome.runtime.onMessage.addListener(onMessage);
        chrome.runtime.sendMessage({action: 'requestBackendReadySignal'}, () => void chrome.runtime.lastError);
    });
    mark('backend_ready');
    say(`backend ready (${Math.round(performance.now() - t0)} ms after the spike page asked)`);
}

async function importDictionary(file) {
    const t0 = performance.now();
    const response = await fetch(`/__dicts/${file}`);
    if (!response.ok) { throw new Error(`${file}: HTTP ${response.status}`); }
    const archive = await response.arrayBuffer();
    const tRead = performance.now();
    const options = await api.optionsGetFull();
    let lastStep = -1;
    // Yomitan's settings page runs this importer in a dedicated worker, but its zip library then
    // starts a worker of its own, and Android WebView never serves a worker started by a worker
    // (the request bypasses shouldInterceptRequest). The same class runs in a page, as Yomitan's
    // own tests run it; the spike page is invisible, so its main thread is free to do the work.
    const importer = new DictionaryImporter(new DictionaryImporterMediaLoader(), ({index, count}) => {
        if (index !== lastStep) { lastStep = index; say(`  ${file}: step ${index}/${count} at ${Math.round(performance.now() - t0)} ms`); }
    });
    const database = new DictionaryDatabase();
    await database.prepare();
    let summary;
    let errors;
    try {
        ({result: summary, errors} = await importer.importDictionary(
            database,
            archive,
            {prefixWildcardsSupported: options.global.database.prefixWildcardsSupported, yomitanVersion: chrome.runtime.getManifest().version},
        ));
    } finally {
        await database.close();
    }
    if (!summary) { throw new Error(`${file}: ${errors.map((e) => e.message).join('; ')}`); }
    // What Yomitan's settings page does after an import: enable the dictionary in every profile.
    const targets = options.profiles.map((profile, i) => ({
        action: 'push',
        path: `profiles[${i}].options.dictionaries`,
        items: [{
            name: summary.title, alias: summary.title, enabled: true, allowSecondarySearches: false,
            definitionsCollapsible: 'not-collapsible', partsOfSpeechFilter: true, useDeinflections: true, styles: summary.styles ?? '',
        }],
        scope: 'global',
        optionsContext: null,
    }));
    options.profiles.forEach((profile, i) => {
        if (summary.sequenced && profile.options.general.mainDictionary === '') {
            targets.push({action: 'set', path: `profiles[${i}].options.general.mainDictionary`, value: summary.title, scope: 'global', optionsContext: null});
        }
    });
    await api.modifySettings(targets, 'reikai-spike');
    await api.triggerDatabaseUpdated('dictionary', 'import');
    const ms = Math.round(performance.now() - t0);
    result('import', {file, title: summary.title, ms, readMs: Math.round(tRead - t0), bytes: archive.byteLength, errors: errors.length});
}

// Text a reader taps into: a lookup gets the text from the tapped character on, as Yomitan's scanner
// sends it (scanLength 16 by default).
const SAMPLE = 'その日の夕方、彼女は駅前の古い喫茶店で友達を待っていた。窓の外では雨が降り続いていて、' +
    '通りを歩く人々は皆傘をさしていた。注文したコーヒーはすっかり冷めてしまったが、彼女はまだ一口も飲んでいなかった。' +
    '約束の時間はとっくに過ぎていたのに、友達からは何の連絡も来ない。仕方なく鞄から読みかけの小説を取り出して、' +
    '続きを読み始めた。物語の主人公は、忘れられた村で不思議な出来事に巻き込まれていく少年だった。';

async function bench(count = 200) {
    const optionsContext = {current: true};
    const starts = [];
    for (let i = 0; i < SAMPLE.length; ++i) {
        if (!/[、。\s]/.test(SAMPLE[i])) { starts.push(i); }
    }
    const times = [];
    let found = 0;
    let first = null;
    for (let n = 0; n < count; ++n) {
        const start = starts[n % starts.length];
        const text = SAMPLE.slice(start, start + 16);
        const t0 = performance.now();
        const {dictionaryEntries} = await api.termsFind(text, {}, optionsContext);
        const ms = performance.now() - t0;
        if (first === null) { first = {text, ms: Math.round(ms), entries: dictionaryEntries.length, top: dictionaryEntries[0]?.headwords?.[0]}; }
        times.push(ms);
        if (dictionaryEntries.length > 0) { ++found; }
    }
    times.sort((a, b) => a - b);
    const pct = (p) => Math.round(times[Math.min(times.length - 1, Math.floor(p * times.length))] * 10) / 10;
    result('bench', {count, found, p50: pct(0.5), p95: pct(0.95), p99: pct(0.99), max: pct(1), first});
}

async function info() {
    const dictionaries = await api.getDictionaryInfo();
    result('dictionaries', dictionaries.map((d) => ({title: d.title, counts: d.counts})));
    const tripwire = JSON.parse(await globalThis.__reikaiRequest({t: 'tripwire'}));
    result('tripwire', tripwire.paths);
    if (navigator.storage?.estimate) {
        const {usage, quota} = await navigator.storage.estimate();
        result('storage', {usageMB: Math.round(usage / 1e6), quotaMB: Math.round(quota / 1e6)});
    }
}

/** Points Yomitan's first card format at the Lapis note type, with the fields Lapis's README lists. */
async function ankiOptions(deck) {
    const options = await api.optionsGetFull();
    const jitendex = (await api.getDictionaryInfo()).find((d) => d.title.startsWith('Jitendex'));
    const field = (value) => ({value, overwriteMode: 'coalesce'});
    const fields = {
        Expression: field('{expression}'),
        ExpressionFurigana: field('{furigana-plain}'),
        ExpressionReading: field('{reading}'),
        ExpressionAudio: field('{audio}'),
        SelectionText: field('{popup-selection-text}'),
        MainDefinition: field(jitendex ? `{single-glossary-${getKebabCase(jitendex.title)}}` : '{glossary-first}'),
        DefinitionPicture: field(''),
        Sentence: field('{cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}'),
        SentenceFurigana: field(''),
        SentenceAudio: field(''),
        Picture: field(''),
        Glossary: field('{glossary}'),
        Hint: field(''),
        IsWordAndSentenceCard: field(''),
        IsClickCard: field(''),
        IsSentenceCard: field(''),
        IsAudioCard: field(''),
        PitchPosition: field('{pitch-accent-positions}'),
        PitchCategories: field('{pitch-accent-categories}'),
        Frequency: field('{frequencies}'),
        FreqSort: field('{frequency-harmonic-rank}'),
        MiscInfo: field('{document-title}'),
    };
    const targets = [];
    options.profiles.forEach((profile, i) => {
        const base = `profiles[${i}].options.anki`;
        const formats = profile.options.anki.cardFormats;
        formats[0] = {...formats[0], deck, model: 'Lapis', fields};
        targets.push({action: 'set', path: `${base}.enable`, value: true, scope: 'global', optionsContext: null});
        targets.push({action: 'set', path: `${base}.cardFormats`, value: formats, scope: 'global', optionsContext: null});
    });
    await api.modifySettings(targets, 'reikai-spike');
    result('anki-options', {deck, model: 'Lapis', mainDefinition: fields.MainDefinition.value});
}

async function run(command) {
    say(`> ${JSON.stringify(command)}`);
    try {
        switch (command.do) {
            case 'import':
                for (const file of command.files) { await importDictionary(file); }
                break;
            case 'bench':
                await bench(command.count);
                break;
            case 'info':
                await info();
                break;
            case 'probe': {
                // Finding 5: a worker started by a worker is never served in Android WebView.
                const said = await new Promise((resolve) => {
                    const out = [];
                    const worker = new Worker('/__reikai/probe-worker.js');
                    worker.onmessage = (e) => out.push(e.data);
                    worker.onerror = (e) => out.push(`worker error ${e.message}`);
                    setTimeout(() => { worker.terminate(); resolve(out); }, 5000);
                });
                result('probe', said);
                break;
            }
            case 'anki-options':
                await ankiOptions(command.deck);
                break;
            case 'lookup': {
                const {dictionaryEntries} = await api.termsFind(command.text, {}, {current: true});
                result('lookup', dictionaryEntries.slice(0, 3).map((e) => ({headwords: e.headwords.map((h) => `${h.term}【${h.reading}】`), frequencies: e.frequencies.length, pronunciations: e.pronunciations.length})));
                break;
            }
            default:
                say(`unknown command ${command.do}`);
        }
        result('done', command.do);
    } catch (e) {
        result('error', {command: command.do, message: String(e?.stack ?? e)});
    }
}

let queue = Promise.resolve();
globalThis.__reikaiOn('cmd', (_header, payload) => { queue = queue.then(() => run(JSON.parse(payload))); });

queue = waitForBackend().then(info);
