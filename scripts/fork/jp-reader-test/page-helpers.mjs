/*
 * Reikai JP: measuring tools the tests evaluate in the chapter page as window.__t, written apart from
 * jp-reader.js so they check it rather than repeat it. GPL-3.0-or-later.
 *
 * The counted characters come from ttu's own getParagraphNodes (window.ttuRef), one character at a
 * time; "visible" means a character's centre is inside the viewport, and a character's page is
 * found from its position in the body's scroll range, never from jp-reader.js's own bookkeeping.
 */
export const PAGE_HELPERS = `(() => {
  const chapter = document.getElementById('jp-chapter');
  const ONE = /^[0-9A-Z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚｦ-ﾝ\\p{Radical}\\p{Unified_Ideograph}]$/iu;
  const range = document.createRange();
  let list = null;

  // Every counted character in order: a text node and its code-unit span, or a gaiji picture.
  function chars() {
    if (list) return list;
    list = [];
    for (const node of ttuRef.getParagraphNodes(chapter)) {
      if (node.nodeType !== Node.TEXT_NODE) {
        list.push({ node, start: -1, end: -1 });
        continue;
      }
      const text = node.data;
      for (let i = 0; i < text.length;) {
        const size = text.codePointAt(i) > 0xffff ? 2 : 1;
        if (ONE.test(text.substr(i, size))) list.push({ node, start: i, end: i + size });
        i += size;
      }
    }
    return list;
  }

  function rect(i) {
    const c = chars()[i];
    if (!c) return null;
    if (c.start < 0) return c.node.getBoundingClientRect();
    range.setStart(c.node, c.start);
    range.setEnd(c.node, c.end);
    return [...range.getClientRects()].find((r) => r.width > 0 && r.height > 0) || null;
  }

  function inView(r) {
    if (!r) return false;
    const x = (r.left + r.right) / 2;
    const y = (r.top + r.bottom) / 2;
    return x >= 0 && x <= innerWidth && y >= 0 && y <= innerHeight;
  }

  function fullyInView(r) {
    return !!r && r.left >= 0 && r.right <= innerWidth && r.top >= 0 && r.bottom <= innerHeight;
  }

  // Characters before the first one whose centre is on screen (paged mode), by walking all of them.
  function firstVisible() {
    const n = chars().length;
    for (let i = 0; i < n; i++) if (inView(rect(i))) return i;
    return n;
  }

  function vertical() {
    return document.documentElement.classList.contains('jp-vertical');
  }

  // Paged: every character, reading and picture must lie inside the screen of the page it is on.
  function clipReport() {
    const v = vertical();
    const W = innerWidth;
    const H = innerHeight;
    const pitch = v ? H : W;
    const scroll = v ? document.body.scrollTop : document.body.scrollLeft;
    const bad = [];
    let checked = 0;
    function check(r, what) {
      if (!r || r.width === 0 || r.height === 0) return;
      checked += 1;
      const a0 = (v ? r.top : r.left) + scroll;
      const a1 = (v ? r.bottom : r.right) + scroll;
      const page = Math.floor(((a0 + a1) / 2) / pitch);
      const lo = page * pitch;
      const hi = lo + pitch;
      const c0 = v ? r.left : r.top;
      const c1 = v ? r.right : r.bottom;
      const cmax = v ? W : H;
      if (a0 < lo - 0.5 || a1 > hi + 0.5 || c0 < -0.5 || c1 > cmax + 0.5) {
        bad.push(what + ' ' + JSON.stringify([r.left, r.top, r.right, r.bottom].map(Math.round)) + ' page ' + page);
      }
    }
    const n = chars().length;
    for (let i = 0; i < n; i++) check(rect(i), 'char ' + i);
    for (const rt of chapter.querySelectorAll('rt')) for (const r of rt.getClientRects()) check(r, 'rt');
    for (const img of chapter.querySelectorAll('img')) check(img.getBoundingClientRect(), 'img');
    return { checked, badCount: bad.length, bad: bad.slice(0, 5) };
  }

  // The centre of a counted character fully on screen and outside ruby and upright runs.
  function charPoint() {
    const n = chars().length;
    for (let i = firstVisible(); i < n; i++) {
      const c = chars()[i];
      if (c.start < 0 || c.node.parentElement.closest('ruby, .jp-tcy')) continue;
      const r = rect(i);
      if (fullyInView(r)) return { i, x: (r.left + r.right) / 2, y: (r.top + r.bottom) / 2 };
    }
    return null;
  }

  function rubyIndex(k) {
    const ruby = chapter.querySelectorAll('ruby')[k];
    return chars().findIndex((c) => ruby.contains(c.node));
  }

  function rubyPoint(k) {
    const i = rubyIndex(k);
    const r = rect(i);
    return fullyInView(r) ? { i, x: (r.left + r.right) / 2, y: (r.top + r.bottom) / 2 } : null;
  }

  // Text of a range as read (readings left out).
  function rangeText(r) {
    const copy = r.cloneContents();
    copy.querySelectorAll('rt, rp').forEach((e) => e.remove());
    return copy.textContent.replace(/\\s/g, '');
  }

  function highlightText() {
    const h = CSS.highlights.get('jp-tts');
    if (!h) return null;
    return [...h].map(rangeText).join('');
  }

  function highlightVisible() {
    const h = CSS.highlights.get('jp-tts');
    if (!h) return false;
    const r = [...h][0];
    const first = [...r.getClientRects()].find((x) => x.width > 0 && x.height > 0);
    return inView(first);
  }

  window.__t = {
    count: () => chars().length,
    rect,
    firstVisible,
    charVisible: (n) => inView(rect(n)),
    clipReport,
    charPoint,
    rubyIndex,
    rubyPoint,
    highlightText,
    highlightVisible,
    rangeText,
  };
})();`;
