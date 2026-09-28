/*
 * Reikai JP: sample chapters and the chapter document for the jp-reader tests. GPL-3.0-or-later.
 *
 * The Japanese sentences are written for these tests. A sentence is plain text with two marks:
 * {漢字|かんじ} is a word with its reading (ruby), [G] a gaiji picture (a character drawn as an image).
 * Each chapter comes with what the page must report for it: the ttu character count, worked out
 * here from the text rather than the page, and upstream's read-aloud paragraphs.
 */

export const ORIGIN = 'https://chapter.reikai.invalid';

// The ttu count's character class (ttu-ebook-reader get-character-count.ts, BSD-3-Clause).
const NOT_JAPANESE = /[^0-9A-Z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]+/gimu;
export const countText = (text) => Array.from(text.replace(NOT_JAPANESE, '')).length;

const SENTENCES = [
    '朝の光が窓から差し込み、机の上の古い本を静かに照らしていた。',
    '彼女は駅までの道を急ぎながら、昨日の約束を思い出していた。',
    '雨上がりの商店街には、焼きたてのパンの匂いが漂っている。',
    '「もう少しだけ待ってくれないか」と彼は小さな声で言った。',
    '山の向こうに沈む夕日が、川の水面を赤く染めていく。',
    '図書館の奥の棚で、誰も読まない{詩集|ししゅう}を見つけた。',
    '猫は{縁側|えんがわ}で丸くなり、午後の日差しの中で眠っていた。',
    '電車の窓に映る自分の顔が、少しだけ疲れて見えた。',
    '祖母の台所からは、いつも{出汁|だし}の香りがしていた。',
    '冬の夜空には、数えきれないほどの星が輝いている。',
    '「本当にそれでいいの!?」と妹は首をかしげた。',
    '港町の坂道を上ると、遠くに白い{灯台|とうだい}が見えてくる。',
    '彼は手紙を三度読み返してから、そっと引き出しにしまった。',
    '教室の黒板には、消し忘れた数式が残っていた。',
    '{風鈴|ふうりん}の音が、夏の終わりを告げるように鳴った。',
    '12時の鐘が鳴ると、広場の鳩が一斉に飛び立った。',
    '2026年の春、私は初めてこの町を訪れた。',
    '「行くぞ!!」と兄が叫び、僕たちは坂を駆け下りた。',
    '古い時計は7分遅れていたが、誰も直そうとはしなかった。',
    '100円玉を握りしめて、子どもたちは{駄菓子屋|だがしや}へ向かった。',
    '「え、嘘でしょ?!」と友人は目を丸くした。',
    '夜更けの台所で、湯気の立つ茶碗を両手で包んだ。',
    '峠の茶屋では、[G]という古い字の看板が風に揺れていた。',
    '川沿いの桜並木は、三月の終わりに一斉に咲き始める。',
    '彼女の笑い声は、静かな廊下の奥まで響いていった。',
    '窓の外では、雪が音もなく降り積もっていた。',
    '市場の人々は、朝早くから威勢のいい声を上げている。',
    '旅の終わりに、私は小さな{硝子|ガラス}の鳥を買った。',
    '夏祭りの夜、遠くで花火の音が低く響いた。',
    '机の引き出しには、書きかけの日記が眠っている。',
    '霧の深い朝、船はゆっくりと港を離れていった。',
    '3人の子どもが、公園の砂場で城を作っていた。',
    '彼はコーヒーを一口飲んで、窓の外の通りを眺めた。',
    '畑の向こうで、トラクターがのんびりと土を耕している。',
    '古本屋の主人は、埃をかぶった地図を大切そうに広げた。',
    '「明日も晴れるといいね」と、彼女は空を見上げた。',
    '路地裏の小さな店で、ＡＢＣと書かれた古い看板を見つけた。',
    '駅前のベンチで、老人が静かに新聞を読んでいた。',
    '秋の風が吹くと、庭の柿の実が色づき始める。',
    '遠い国から届いた葉書には、見知らぬ切手が貼られていた。',
];

/** A deterministic generator (mulberry32), so every run lays out the same chapters. */
export function random(seed) {
    let a = seed >>> 0;
    return () => {
        a = (a + 0x6d2b79f5) >>> 0;
        let t = a;
        t = Math.imul(t ^ (t >>> 15), t | 1);
        t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

const escapeHtml = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** One sentence as HTML, as counted text (+ gaiji) and as the text read aloud. */
function renderSentence(sentence, rubyStyle) {
    let html = '';
    let counted = '';
    let spoken = '';
    let gaiji = 0;
    const parts = sentence.split(/(\{[^}]+\}|\[G\])/).filter(Boolean);
    for (const part of parts) {
        if (part === '[G]') {
            html += '<img class="gaiji" src="/img/gaiji.svg" alt="">';
            gaiji += 1;
        } else if (part.startsWith('{')) {
            const [base, reading] = part.slice(1, -1).split('|');
            html += rubyStyle
                ? `<ruby>${base}<rp>(</rp><rt>${reading}</rt><rp>)</rp></ruby>`
                : `<ruby><rb>${base}</rb><rt>${reading}</rt></ruby>`;
            counted += base;
            spoken += base;
        } else {
            html += escapeHtml(part);
            counted += part;
            spoken += part;
        }
    }
    return {html, counted, spoken, gaiji};
}

/**
 * A generated chapter of at least `target` counted characters: paragraphs of one to four
 * sentences, every ninth one long, a blank line now and then, a picture after `pictureAt`.
 */
function generated(id, title, target, seed, {pictureAt = -1} = {}) {
    const next = random(seed);
    const blocks = [`<h1 class="jp-title">${title}</h1>`];
    const paragraphs = [title];
    let counted = countText(title);
    let sentence = Math.floor(next() * SENTENCES.length);
    let index = 0;
    let pictured = false;
    while (counted < target) {
        index += 1;
        if (!pictured && pictureAt >= 0 && counted >= pictureAt) {
            blocks.push('<p><img src="/img/picture.svg" alt=""></p>');
            pictured = true;
            continue;
        }
        if (index % 13 === 0) {
            blocks.push('<p><br></p>');
            continue;
        }
        const size = index % 9 === 0 ? 18 + Math.floor(next() * 12) : 1 + Math.floor(next() * 4);
        let html = '';
        let spoken = '';
        for (let i = 0; i < size; i++) {
            sentence = (sentence + 1 + Math.floor(next() * 3)) % SENTENCES.length;
            const rendered = renderSentence(SENTENCES[sentence], (index + i) % 2 === 0);
            html += rendered.html;
            spoken += rendered.spoken;
            counted += countText(rendered.counted) + rendered.gaiji;
        }
        blocks.push(`<p>${html}</p>`);
        paragraphs.push(spoken.replace(/\s+/g, ' ').trim());
    }
    return {id, html: blocks.join('\n'), chars: counted, paragraphs};
}

/** Hand-made: every rule of ttu's count in one place. */
function countingChapter() {
    const html = [
        '<h1 class="jp-title">数え方の試験</h1>',
        '<p>𠮟る々〆〇〻ヶｱｲｳﾞﾟＡＢＣａｂｃabcXYZ０１２789ー・…、。「」！？!?♪〜😀한жéK○◯</p>',
        '<p>見える<span hidden>隠れた文字</span><span aria-hidden="true">飾り</span><span aria-hidden="false">偽物</span>終わり</p>',
        '<p><ruby>漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby>と<ruby><rb>仮名</rb><rt>かな</rt></ruby></p>',
        '<p>字<img class="gaiji" src="/img/gaiji.svg" alt="">と<img class="x-gaiji-line" src="/img/gaiji.svg" alt="">と' +
            '<img class="gaiji" hidden src="/img/gaiji.svg" alt="">と<img src="/img/gaiji.svg" alt="">絵</p>',
        '<!-- 注釈は数えない -->',
        '<p>12時と2026年と100円と!?と!!!と3.14と<ruby>12<rt>じゅうに</rt></ruby>月</p>',
    ].join('\n');
    // Worked out by hand from ttu's rule, line by line:
    //  title 数え方の試験 = 6
    //  𠮟る々〆〇〻ヶ = 7; ｱｲｳ = 3 (ﾞﾟ are outside ｦ-ﾝ); ＡＢＣａｂｃ = 6; abcXYZ = 6; ０１２ = 3; 789 = 3;
    //    ー = 1; ・…、。「」！？!?♪〜😀한жé = 0; K (Kelvin sign, folds to k) = 1; ○◯ = 2  -> 32
    //  見える + 終わり = 6 (hidden and aria-hidden, even "false", are left out)
    //  漢字と仮名 = 5 (readings and the rp brackets are not counted)
    //  字 と と と 絵 = 5, gaiji and x-gaiji-line = 2 (the hidden gaiji and the plain picture 0) -> 7
    //  12 時 と 2026 年 と 100 円 と と と 3 14 と 12(ruby base) 月 = 2+1+1+4+1+1+3+1+1+1+1+1+2+1+2+1 = 24
    const chars = 6 + 32 + 6 + 5 + 7 + 24;
    const paragraphs = [
        '数え方の試験',
        '𠮟る々〆〇〻ヶｱｲｳﾞﾟＡＢＣａｂｃabcXYZ０１２789ー・…、。「」！？!?♪〜😀한жéK○◯',
        // aria-hidden hides from the count, not from the page, so read-aloud keeps it.
        '見える飾り偽物終わり',
        '漢字と仮名',
        '字ととと絵',
        '12時と2026年と100円と!?と!!!と3.14と12月',
    ];
    return {id: 9004, html, chars, paragraphs};
}

export const CHAPTERS = {
    short: generated(9001, '短い章', 120, 7),
    medium: generated(9002, '第一章 港町の朝', 4000, 11),
    long: generated(9003, '第二章 長い旅', 20000, 23, {pictureAt: 9000}),
    counting: countingChapter(),
};

/** The chapter document exactly as the contract describes it (JpPageViewport serves the same). */
export function chapterDocument(chapter, init, {inlineStyles = true, script = true} = {}) {
    const s = init.settings;
    const classes = [
        s.writing === 'horizontal' ? 'jp-horizontal' : 'jp-vertical',
        s.layout === 'scroll' ? 'jp-scroll' : 'jp-paged',
        `jp-furi-${s.furigana}`,
    ].join(' ');
    const json = JSON.stringify({chapterId: chapter.id, ...init}).replace(/</g, '\\u003c');
    const style = inlineStyles ? ` style="--jp-font-size: ${s.fontSize}px"` : '';
    const fontFace = inlineStyles ? '<style id="jp-font-face"></style>' : '';
    return `<!DOCTYPE html>
<html lang="ja" class="${classes}"${style}>
<head><link rel="stylesheet" href="/jp-reader/jp-reader.css">${fontFace}
<script type="application/json" id="jp-init">${json}</script></head>
<body><main id="jp-chapter">${chapter.html}</main>
${script ? '<script src="/jp-reader/jp-reader.js"></script>' : ''}</body></html>`;
}

/** A character drawn as a picture (gaiji), and an illustration of 600 x 900. */
export const IMAGES = {
    'gaiji.svg': '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32">' +
        '<rect x="4" y="4" width="24" height="24" fill="none" stroke="black" stroke-width="3"/>' +
        '<path d="M8 16h16M16 8v16" stroke="black" stroke-width="3"/></svg>',
    'picture.svg': '<svg xmlns="http://www.w3.org/2000/svg" width="600" height="900" viewBox="0 0 600 900">' +
        '<rect width="600" height="900" fill="#cde"/><circle cx="300" cy="450" r="200" fill="#468"/></svg>',
};
