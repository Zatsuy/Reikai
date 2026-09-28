/*
 * Reikai JP: the Japanese reading mode's page script (roadmap 4.1). GPL-3.0-or-later.
 *
 * Lays one chapter out as pages (or a continuous scroll) in vertical or horizontal Japanese text,
 * keeps the reader's place to the character, and talks to JpPageViewport through the contract in
 * docs/fork/research/phase4-design-2026-09.md ("The page contract"): settings from the jp-init JSON
 * and JpReader.applySettings, messages {t, ...} out through the WebMessageListener object jpReader,
 * calls in through window.JpReader. Tested headless by scripts/fork/jp-reader-test/run.mjs.
 *
 * Borrowed, with thanks:
 * - chimahon (https://github.com/Chimahon/chimahon, GPL-3.0), whose novel/reader.js ports Hoshi
 *   Reader's paging to an Android WebView, and Hoshi Reader (https://github.com/Manhhao/Hoshi-Reader,
 *   Copyright (c) 2026 Manhhao, SPDX-License-Identifier: GPL-3.0-or-later): the body as the
 *   multi-column scroller, one column a page, stepped by scrollTop in vertical text and scrollLeft in
 *   horizontal text; the root viewport locked at 0 and a page snapped back when something else
 *   scrolls the body; the binary search over a partly visible text node's characters; waiting for
 *   fonts and pictures before measuring.
 * - ッツ Ebook Reader (https://github.com/ttu-ttu/ebook-reader, BSD-3-Clause, Copyright (c) 2026,
 *   ッツ Reader Authors. All rights reserved.): the character count (get-character-count.ts,
 *   get-paragraph-nodes.ts, is-element-gaiji.ts; the copied parts are marked below and keep that
 *   notice; ttu's full notice is in NOTICE.txt beside this file and in LICENSES/Reader-NOTICE.md), the
 *   furigana modes and their tap (reactive-elements.ts), and the "intended" position that only the
 *   reader's own moves change so that repeated re-layouts never drift (book-reader-paginated.svelte).
 * - The read-aloud paragraphs follow the rule of upstream Reikai's novel-web/reader.js.
 */
(function () {
  'use strict';

  if (window.JpReader && window.JpReader.__started) return;

  // A tap is short and still; a swipe is mostly sideways and at least this long (CSS px).
  var TAP_MAX_MS = 400;
  var TAP_SLOP_PX = 10;
  var SWIPE_MIN_PX = 40;
  // Scroll mode: a pull at least this long past an end of the chapter asks for the next one (edge).
  var EDGE_PULL_PX = 80;
  // How long the first layout waits for a chapter's pictures and fonts before measuring anyway.
  var IMAGE_WAIT_MS = 3000;
  var FONT_WAIT_MS = 3000;
  // A scroll is settled when no scroll event came for this long.
  var SETTLE_MS = 150;
  // A picture larger than this (natural px) is a block of its own.
  var BLOCK_IMAGE_PX = 256;
  var TURN_SCROLL_SHARE = 0.9;
  var HIGHLIGHT_NAME = 'jp-tts';

  var root = document.documentElement;
  var body = document.body;
  var chapter = document.getElementById('jp-chapter') || body;
  var RANGE = document.createRange();

  // region messages

  // Every message names this document (jp-init's doc), so the app can tell a late one from a document it
  // has already replaced from the new document's own.
  function post(msg) {
    var channel = window.jpReader;
    if (!channel || typeof channel.postMessage !== 'function') return;
    if (init && typeof init.doc === 'string') msg.doc = init.doc;
    try {
      channel.postMessage(JSON.stringify(msg));
    } catch (e) {
      // The app went away mid-message; nothing to do.
    }
  }

  // endregion

  // region settings

  var DEFAULTS = {
    writing: 'vertical',
    layout: 'paged',
    furigana: 'show',
    tapMode: 'lookup',
    tapZones: [],
    fontFamily: 'serif',
    fontSize: 20,
    lineHeight: 1.8,
    margins: { top: 16, right: 16, bottom: 16, left: 16 },
    insets: { top: 0, bottom: 0 },
    colors: { background: '#ffffff', text: '#000000', hint: '#888888' },
    textIndent: 1,
    justify: true,
    invertSwipe: false,
  };
  var FURIGANA = ['show', 'partial', 'full', 'toggle', 'hide'];
  // Changing only these leaves the layout as it is.
  var PAINT_ONLY = ['colors', 'tapMode', 'tapZones', 'invertSwipe'];

  function num(value, fallback) {
    var n = Number(value);
    return value !== null && value !== undefined && isFinite(n) ? n : fallback;
  }

  function sides(value, fallback) {
    var v = value || {};
    var out = {};
    for (var key in fallback) out[key] = Math.max(0, num(v[key], fallback[key]));
    return out;
  }

  /* A CSS colour string as is; an Android ARGB int converted. */
  function colour(value, fallback) {
    if (typeof value === 'number' && isFinite(value)) {
      var v = value >>> 0;
      return 'rgba(' + ((v >>> 16) & 255) + ',' + ((v >>> 8) & 255) + ',' + (v & 255) + ',' +
        (((v >>> 24) & 255) / 255).toFixed(3) + ')';
    }
    return typeof value === 'string' && value ? value : fallback;
  }

  function normalise(input, base) {
    var s = input || {};
    var b = base || DEFAULTS;
    var colors = s.colors || {};
    return {
      writing: s.writing === 'horizontal' || s.writing === 'vertical' ? s.writing : b.writing,
      layout: s.layout === 'scroll' || s.layout === 'paged' ? s.layout : b.layout,
      furigana: FURIGANA.indexOf(s.furigana) >= 0 ? s.furigana : b.furigana,
      tapMode: s.tapMode === 'zones' || s.tapMode === 'lookup' ? s.tapMode : b.tapMode,
      tapZones: Array.isArray(s.tapZones) ? s.tapZones : b.tapZones,
      fontFamily: typeof s.fontFamily === 'string' && s.fontFamily ? s.fontFamily : b.fontFamily,
      fontSize: Math.max(4, num(s.fontSize, b.fontSize)),
      lineHeight: Math.max(0.5, num(s.lineHeight, b.lineHeight)),
      margins: sides(s.margins || b.margins, b.margins),
      insets: sides(s.insets || b.insets, b.insets),
      colors: {
        background: colour(colors.background, b.colors.background),
        text: colour(colors.text, b.colors.text),
        hint: colour(colors.hint, b.colors.hint),
      },
      textIndent: num(s.textIndent, b.textIndent),
      justify: typeof s.justify === 'boolean' ? s.justify : b.justify,
      invertSwipe: typeof s.invertSwipe === 'boolean' ? s.invertSwipe : b.invertSwipe,
    };
  }

  function layoutKey(s) {
    var copy = {};
    for (var key in s) if (PAINT_ONLY.indexOf(key) < 0) copy[key] = s[key];
    return JSON.stringify(copy);
  }

  var init = readInit();
  var settings = normalise(init.settings);

  function readInit() {
    var el = document.getElementById('jp-init');
    if (!el) return {};
    try {
      return JSON.parse(el.textContent || '{}') || {};
    } catch (e) {
      return {};
    }
  }

  // endregion

  // region character count (ttu's rule)

  /*
   * Copied from ttu-ebook-reader, apps/web/src/lib/functions/get-character-count.ts:
   * @license BSD-3-Clause
   * Copyright (c) 2026, ッツ Reader Authors
   * All rights reserved.
   */
  var isNotJapaneseRegex =
    /[^0-9A-Z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]+/gimu;
  // The same class for one character, to find which characters of a text node count.
  var isJapaneseChar = /^[0-9A-Z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]$/iu;

  function countText(text) {
    return text ? Array.from(text.replace(isNotJapaneseRegex, '')).length : 0;
  }

  /* Copied from ttu's is-element-gaiji.ts and is-node-gaiji.ts (notice above). */
  function isGaiji(node) {
    return node instanceof HTMLImageElement &&
      Array.from(node.classList).some(function (className) { return className.indexOf('gaiji') >= 0; });
  }

  /* ttu's get-paragraph-nodes.ts filter (notice above): not an rt, not hidden, not aria-hidden. */
  function isCountable(node) {
    if (node.nodeName === 'RT') return false;
    if (node instanceof HTMLElement &&
      (node.attributes.getNamedItem('aria-hidden') || node.attributes.getNamedItem('hidden'))) {
      return false;
    }
    return true;
  }

  // The chapter as counted: text nodes and gaiji pictures that hold counted characters, in order.
  var units = [];
  var unitStart = [];
  var unitCount = [];
  var total = 0;
  var offsetCache = { unit: -1, offsets: null };

  function buildUnits() {
    units = [];
    unitStart = [];
    unitCount = [];
    total = 0;
    offsetCache = { unit: -1, offsets: null };
    collect(chapter);

    function collect(node) {
      if (!node.hasChildNodes() || !isCountable(node)) return;
      for (var child = node.firstChild; child; child = child.nextSibling) {
        if (child.nodeType === Node.TEXT_NODE) {
          add(child, countText(child.data));
        } else if (isGaiji(child)) {
          if (isCountable(child)) add(child, 1);
        } else {
          collect(child);
        }
      }
    }

    function add(node, count) {
      if (count <= 0) return;
      units.push(node);
      unitStart.push(total);
      unitCount.push(count);
      total += count;
    }
  }

  /* Code-unit offsets of the counted characters of unit u, the last one asked for kept. */
  function offsetsOf(u) {
    if (offsetCache.unit === u) return offsetCache.offsets;
    var node = units[u];
    var offsets = [];
    if (node.nodeType === Node.TEXT_NODE) {
      var text = node.data;
      for (var i = 0; i < text.length;) {
        var size = text.codePointAt(i) > 0xffff ? 2 : 1;
        if (isJapaneseChar.test(text.substr(i, size))) offsets.push(i);
        i += size;
      }
    } else {
      offsets.push(0);
    }
    offsetCache = { unit: u, offsets: offsets };
    return offsets;
  }

  /* The unit holding character n (0-based) of the chapter, by binary search over the counts. */
  function unitOfChar(n) {
    var lo = 0;
    var hi = units.length - 1;
    while (lo < hi) {
      var mid = (lo + hi + 1) >> 1;
      if (unitStart[mid] <= n) lo = mid; else hi = mid - 1;
    }
    return lo;
  }

  function usable(rect) {
    return rect && (rect.width > 0 || rect.height > 0) ? rect : null;
  }

  function firstRect(rects) {
    for (var i = 0; i < rects.length; i++) {
      if (rects[i].width > 0 && rects[i].height > 0) return rects[i];
    }
    return rects.length ? usable(rects[0]) : null;
  }

  /* The client rect of the k-th counted character of unit u, or null when it is not drawn. */
  function charRect(u, k) {
    var node = units[u];
    if (node.nodeType !== Node.TEXT_NODE) return firstRect(node.getClientRects());
    var start = offsetsOf(u)[k];
    RANGE.setStart(node, start);
    RANGE.setEnd(node, start + (node.data.codePointAt(start) > 0xffff ? 2 : 1));
    var rect = firstRect(RANGE.getClientRects());
    if (rect) return rect;
    return node.parentElement ? usable(node.parentElement.getBoundingClientRect()) : null;
  }

  // endregion

  // region load-time changes to the chapter

  // One or two half-width digits, or !! !? ?!, standing alone (a three-digit number stays sideways).
  var TCY = /(?<![0-9A-Za-z.,])[0-9]{1,2}(?![0-9A-Za-z.,])|(?<![!?])(?:!!|!\?|\?!)(?![!?])/g;

  /* Wraps upright runs in span.jp-tcy once; the stylesheet sets them upright only in vertical text. */
  function wrapTateChuYoko() {
    var found = [];
    var walker = document.createTreeWalker(chapter, NodeFilter.SHOW_TEXT, {
      acceptNode: function (node) {
        var parent = node.parentElement;
        if (!parent || parent.closest('ruby, rt, rp, script, style, .jp-tcy, [class*="tcy"]')) {
          return NodeFilter.FILTER_REJECT;
        }
        TCY.lastIndex = 0;
        return TCY.test(node.data) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
      },
    });
    while (walker.nextNode()) found.push(walker.currentNode);
    for (var i = 0; i < found.length; i++) {
      var node = found[i];
      var text = node.data;
      var fragment = document.createDocumentFragment();
      var from = 0;
      var match;
      TCY.lastIndex = 0;
      while ((match = TCY.exec(text)) !== null) {
        if (match.index > from) fragment.appendChild(document.createTextNode(text.slice(from, match.index)));
        var span = document.createElement('span');
        span.className = 'jp-tcy';
        span.textContent = match[0];
        fragment.appendChild(span);
        from = match.index + match[0].length;
      }
      if (from < text.length) fragment.appendChild(document.createTextNode(text.slice(from)));
      node.parentNode.replaceChild(fragment, node);
    }
  }

  // How far into the chapter its own title is looked for, in characters other than white space.
  var OWN_TITLE_SPAN = 300;
  // A title's parts, as sources join a part's name and the episode's: "第一部 - 第1話：…".
  var TITLE_PARTS = /\s+[-‐–—―|｜]\s+/;

  /*
   * Most sources open a chapter with its own heading, which the app's title heading would repeat, so
   * that one goes when the chapter's opening already carries the title (its last part, the episode's
   * name, when the source joins several). A chapter without one keeps it.
   */
  function dropRepeatedTitle() {
    var heading = chapter.querySelector('.jp-title');
    if (!heading) return;
    var squeeze = function (text) { return (text || '').replace(/\s+/g, ''); };
    var parts = (heading.textContent || '').split(TITLE_PARTS);
    var title = squeeze(parts[parts.length - 1]);
    if (Array.from(title).length < 2) return;
    var opening = '';
    for (var node = heading.nextSibling; node && opening.length < OWN_TITLE_SPAN; node = node.nextSibling) {
      opening += squeeze(node.textContent);
    }
    if (opening.slice(0, OWN_TITLE_SPAN).indexOf(title) >= 0) heading.remove();
  }

  function ensureViewportMeta() {
    if (document.querySelector('meta[name="viewport"]')) return;
    var meta = document.createElement('meta');
    meta.name = 'viewport';
    meta.content = 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no';
    (document.head || root).appendChild(meta);
  }

  function classifyImage(img) {
    if (isGaiji(img)) return;
    var large = img.naturalWidth > BLOCK_IMAGE_PX || img.naturalHeight > BLOCK_IMAGE_PX;
    img.classList.toggle('jp-block-img', large);
  }

  /* Resolves once every picture has loaded or failed, or after IMAGE_WAIT_MS. */
  function imagesSettled() {
    var images = Array.prototype.slice.call(chapter.querySelectorAll('img'));
    var waits = images.map(function (img) {
      img.loading = 'eager';
      if (img.complete) {
        classifyImage(img);
        return null;
      }
      return new Promise(function (resolve) {
        function done() {
          classifyImage(img);
          resolve();
        }
        img.addEventListener('load', done, { once: true });
        img.addEventListener('error', resolve, { once: true });
      });
    }).filter(Boolean);
    // A picture that arrives after the first layout lays the chapter out again.
    images.forEach(function (img) {
      img.addEventListener('load', function () {
        if (!ready) return;
        classifyImage(img);
        scheduleRelayout();
      });
    });
    if (!waits.length) return Promise.resolve();
    return Promise.race([Promise.all(waits), delay(IMAGE_WAIT_MS)]);
  }

  function fontsSettled() {
    if (!document.fonts) return Promise.resolve();
    var loads = [];
    try {
      loads.push(document.fonts.load(settings.fontSize + 'px ' + settings.fontFamily, 'あ漢'));
    } catch (e) {
      // An unparsable family list: the ready promise below still waits for the rest.
    }
    var all = Promise.all(loads).catch(noop).then(function () { return document.fonts.ready; });
    return Promise.race([all, delay(FONT_WAIT_MS)]).catch(noop);
  }

  function delay(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  function noop() {}

  // endregion

  // region layout

  /*
   * Everything the last layout measured: whether it is paged and vertical, the viewport, the page
   * pitch (the scroll distance between two pages), the page count and the reading edge E that the
   * scroll mode aligns a line to.
   */
  var geo = null;
  var ready = false;
  // The character the reader means to be at; only their own moves change it (ttu).
  var intended = 0;
  var intendedDirty = false;
  // Paged: the page shown (0-based). Scroll: where our last own scroll went, to tell it from theirs.
  var page = 0;
  var expectedScroll = null;
  var lastKey = '';

  function applyClasses() {
    var classes = root.classList;
    var vertical = settings.writing === 'vertical';
    var paged = settings.layout === 'paged';
    classes.toggle('jp-vertical', vertical);
    classes.toggle('jp-horizontal', !vertical);
    classes.toggle('jp-paged', paged);
    classes.toggle('jp-scroll', !paged);
    FURIGANA.forEach(function (mode) { classes.toggle('jp-furi-' + mode, settings.furigana === mode); });
  }

  function applyPaint() {
    var style = root.style;
    style.setProperty('--jp-bg', settings.colors.background);
    style.setProperty('--jp-text', settings.colors.text);
    style.setProperty('--jp-hint', settings.colors.hint);
    var rule = highlightRule();
    if (rule) rule.style.setProperty('background-color', 'color-mix(in srgb, ' + settings.colors.hint + ' 40%, transparent)');
  }

  var highlightRuleCache;

  function highlightRule() {
    if (highlightRuleCache !== undefined) return highlightRuleCache;
    highlightRuleCache = null;
    for (var i = 0; i < document.styleSheets.length && !highlightRuleCache; i++) {
      var rules;
      try {
        rules = document.styleSheets[i].cssRules;
      } catch (e) {
        continue;
      }
      for (var j = 0; j < rules.length; j++) {
        if (rules[j].selectorText && rules[j].selectorText.indexOf('::highlight(' + HIGHLIGHT_NAME + ')') >= 0) {
          highlightRuleCache = rules[j];
          break;
        }
      }
    }
    return highlightRuleCache;
  }

  /* Sets the custom properties the stylesheet lays the chapter out from; returns the viewport. */
  function applyVars() {
    var s = settings;
    var W = window.innerWidth;
    var H = window.innerHeight;
    var T = s.insets.top + s.margins.top;
    var B = s.insets.bottom + s.margins.bottom;
    var L = s.margins.left;
    var R = s.margins.right;
    var vertical = s.writing === 'vertical';
    var style = root.style;
    style.setProperty('--jp-font-family', s.fontFamily);
    style.setProperty('--jp-font-size', s.fontSize + 'px');
    style.setProperty('--jp-line-height', String(s.lineHeight));
    style.setProperty('--jp-indent', s.textIndent + 'em');
    style.setProperty('--jp-align', s.justify ? 'justify' : 'start');
    style.setProperty('--jp-pad-top', T + 'px');
    style.setProperty('--jp-pad-right', R + 'px');
    style.setProperty('--jp-pad-bottom', B + 'px');
    style.setProperty('--jp-pad-left', L + 'px');
    style.setProperty('--jp-inset-bottom', s.insets.bottom + 'px');
    style.setProperty('--jp-page-w', W + 'px');
    style.setProperty('--jp-page-h', H + 'px');
    // One column a page: wider than the text area, so exactly one fits; the gap makes up the page.
    style.setProperty('--jp-col-w', (vertical ? H : W) + 'px');
    style.setProperty('--jp-col-gap', (vertical ? T + B : L + R) + 'px');
    style.setProperty('--jp-img-max-w', Math.max(1, W - L - R - 2) + 'px');
    style.setProperty('--jp-img-max-h', Math.max(1, H - T - B - 2) + 'px');
    applyPaint();
    return { W: W, H: H, T: T, B: B, L: L, R: R };
  }

  /* Lays out with the current settings and measures; forces one synchronous layout. */
  function measure() {
    var view = applyVars();
    var vertical = settings.writing === 'vertical';
    var paged = settings.layout === 'paged';
    var g = {
      paged: paged,
      vertical: vertical,
      W: view.W,
      H: view.H,
      T: view.T,
      R: view.R,
      insetTop: settings.insets.top,
      insetBottom: settings.insets.bottom,
      pitch: vertical ? view.H : view.W,
      pages: 1,
      maxScroll: 0,
      originX: 0,
      originY: 0,
    };
    if (paged) {
      lockRoot();
      // Chromium counts the body's end padding in its scroll range, so the range is whole pages.
      var extent = vertical ? body.scrollHeight : body.scrollWidth;
      g.pages = Math.max(1, Math.ceil((extent - 1) / g.pitch));
      var client = vertical ? body.clientHeight : body.clientWidth;
      g.maxScroll = Math.max(0, extent - client);
      var origin = body.getBoundingClientRect();
      g.originX = origin.left;
      g.originY = origin.top;
    } else {
      // A screen along the scroll: vertical text scrolls sideways, horizontal text downward.
      g.pitch = vertical ? view.W : view.H;
      g.maxScroll = Math.max(0, vertical ? root.scrollWidth - view.W : root.scrollHeight - view.H);
      g.pages = Math.max(1, Math.ceil((g.maxScroll + g.pitch - 1) / g.pitch));
    }
    geo = g;
    lastKey = layoutKey(settings);
  }

  function lockRoot() {
    if (window.scrollX !== 0 || window.scrollY !== 0) window.scrollTo(0, 0);
  }

  // Paged: the body's scroll offset along the page axis.
  function pagedScroll() {
    return geo.vertical ? body.scrollTop : body.scrollLeft;
  }

  function setPage(p) {
    page = Math.max(0, Math.min(geo.pages - 1, p));
    var target = Math.min(page * geo.pitch, geo.maxScroll);
    if (geo.vertical) body.scrollTop = target; else body.scrollLeft = target;
  }

  // Scroll mode: how far along the reading direction the root is scrolled (vertical text scrolls to
  // negative scrollX in Chromium, so the distance is its magnitude).
  function scrollProgress() {
    return geo.vertical ? Math.abs(window.scrollX) : window.scrollY;
  }

  function scrollToProgress(value, isMove) {
    var target = Math.max(0, Math.min(geo.maxScroll, value));
    if (isMove) {
      intendedDirty = true;
      expectedScroll = null;
    } else {
      expectedScroll = target;
    }
    if (geo.vertical) window.scrollTo(-target, 0); else window.scrollTo(0, target);
  }

  /* The page (paged) a client rect is on, by its centre. */
  function pageOfRect(rect) {
    var centre = geo.vertical
      ? (rect.top + rect.bottom) / 2 - geo.originY + body.scrollTop
      : (rect.left + rect.right) / 2 - geo.originX + body.scrollLeft;
    return Math.max(0, Math.min(geo.pages - 1, Math.floor(centre / geo.pitch)));
  }

  // The scroll mode's reading edge: the text area's top in horizontal text, its right in vertical.
  function readingEdge() {
    return geo.vertical ? geo.W - geo.R : geo.T;
  }

  // Scroll mode: how much of the chapter one screen shows along the scroll.
  function screenExtent() {
    return geo.vertical ? geo.W : geo.H - geo.insetTop - geo.insetBottom;
  }

  /*
   * Scroll mode: how far to scroll so the line box holding rect starts at the reading edge (the
   * glyph's own box sits half the leading inside it). Under a pixel is no move at all.
   */
  function lineDelta(rect, node) {
    var el = node && node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
    var lineHeight = el ? parseFloat(getComputedStyle(el).lineHeight) : NaN;
    var extent = geo.vertical ? rect.width : rect.height;
    var half = isFinite(lineHeight) ? Math.max(0, (lineHeight - extent) / 2) : 0;
    var delta = geo.vertical ? readingEdge() - (rect.right + half) : rect.top - half - readingEdge();
    return Math.abs(delta) < 1 ? 0 : delta;
  }

  /* Whether a character drawn at rect comes before the screen. */
  function rectBefore(rect) {
    if (!rect) return true;
    if (geo.paged) return pageOfRect(rect) < page;
    return geo.vertical
      ? (rect.left + rect.right) / 2 > readingEdge()
      : (rect.top + rect.bottom) / 2 < readingEdge();
  }

  /* Characters before the first one on screen (ttu's rule), by binary search over units then characters. */
  function firstVisibleChar() {
    if (!units.length) return 0;
    var lo = 0;
    var hi = units.length - 1;
    var found = units.length;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (rectBefore(charRect(mid, unitCount[mid] - 1))) lo = mid + 1;
      else {
        found = mid;
        hi = mid - 1;
      }
    }
    if (found === units.length) return total;
    var count = unitCount[found];
    var first = count;
    lo = 0;
    hi = count - 1;
    while (lo <= hi) {
      var k = (lo + hi) >> 1;
      if (rectBefore(charRect(found, k))) lo = k + 1;
      else {
        first = k;
        hi = k - 1;
      }
    }
    return unitStart[found] + Math.min(first, count);
  }

  /* Moves to character n without calling it a move of the reader's: pages snap, a scroll aligns its line. */
  function landOn(n) {
    if (!geo) return;
    if (!units.length || n <= 0 || n >= total) {
      var end = units.length && n >= total;
      if (geo.paged) setPage(end ? geo.pages - 1 : 0);
      else scrollToProgress(end ? geo.maxScroll : 0, false);
      return;
    }
    var u = unitOfChar(n);
    var rect = charRect(u, n - unitStart[u]);
    if (geo.paged) {
      setPage(rect ? pageOfRect(rect) : 0);
      return;
    }
    if (rect) scrollToProgress(scrollProgress() + lineDelta(rect, units[u]), false);
  }

  function computeState() {
    if (!geo) {
      return {
        charOffset: intended, anchor: intended, chars: total, fraction: total ? intended / total : 0,
        page: 1, pages: 1, fits: false, endSeen: false,
      };
    }
    var charOffset = firstVisibleChar();
    var current;
    var pages = geo.pages;
    var endSeen;
    if (geo.paged) {
      if (Math.abs(pagedScroll() - Math.min(page * geo.pitch, geo.maxScroll)) > 1) {
        page = Math.max(0, Math.min(pages - 1, Math.round(pagedScroll() / geo.pitch)));
      }
      current = page + 1;
      endSeen = page >= pages - 1;
    } else {
      var at = scrollProgress();
      endSeen = at >= geo.maxScroll - 2;
      current = endSeen ? pages : Math.min(pages, Math.floor(at / geo.pitch) + 1);
    }
    return {
      charOffset: charOffset,
      anchor: intended,
      chars: total,
      fraction: total ? charOffset / total : 0,
      page: current,
      pages: pages,
      fits: pages <= 1,
      endSeen: endSeen,
    };
  }

  /*
   * The state now; a pending move of the reader's becomes the intended position, which the state
   * reports as its anchor: the place to keep, since a page laid out another way (a rotation, another
   * font size, a new document) lands on the page holding it, while keeping the first character on
   * screen instead would slip back up to a page each time.
   */
  function settleState() {
    var state = computeState();
    if (intendedDirty && geo) {
      intended = state.charOffset;
      intendedDirty = false;
    }
    state.anchor = intended;
    return state;
  }

  var posPending = false;
  var lastPosted = '';

  /* Posts pos after the frame that shows the change, computed then (lazily). */
  function schedulePos() {
    if (posPending) return;
    posPending = true;
    requestAnimationFrame(function () {
      setTimeout(function () {
        posPending = false;
        postPos(true);
      }, 0);
    });
  }

  function postPos(always) {
    if (!ready) return;
    var state = settleState();
    var key = JSON.stringify(state);
    if (!always && key === lastPosted) return;
    lastPosted = key;
    post({ t: 'pos', pos: state });
  }

  /*
   * Asks for the next or previous chapter. The place reached is posted first: the app replaces this
   * document on an edge, so a pos still waiting for its frame (a scroll that has not settled, the end
   * that auto-scroll just reached) would never arrive, and the chapter would keep an older place and
   * never be seen to its end.
   */
  function postEdge(forward) {
    postPos(false);
    post({ t: 'edge', forward: forward });
  }

  /* Lays the chapter out again at the intended position, which it leaves as it is. */
  function relayout() {
    relayoutPending = false;
    if (!ready) return;
    if (intendedDirty && geo) settleState();
    measure();
    landOn(intended);
    schedulePos();
  }

  var relayoutPending = false;

  function scheduleRelayout() {
    if (relayoutPending) return;
    relayoutPending = true;
    requestAnimationFrame(relayout);
  }

  // endregion

  // region moves

  function turn(dir) {
    if (!ready || !geo) return;
    var forward = Number(dir) > 0;
    if (geo.paged) {
      var target = page + (forward ? 1 : -1);
      if (target < 0 || target >= geo.pages) {
        postEdge(forward);
        return;
      }
      setPage(target);
      intendedDirty = true;
      schedulePos();
      return;
    }
    var at = scrollProgress();
    if (forward ? at >= geo.maxScroll - 1 : at <= 1) {
      postEdge(forward);
      return;
    }
    var step = Math.max(1, Math.round(screenExtent() * TURN_SCROLL_SHARE));
    scrollToProgress(at + (forward ? step : -step), true);
    schedulePos();
  }

  function seekChar(n) {
    var target = Math.max(0, Math.min(total, Math.round(num(n, 0))));
    intended = target;
    intendedDirty = false;
    if (!ready) return;
    landOn(target);
    schedulePos();
  }

  function seekFraction(f) {
    var fraction = Math.max(0, Math.min(1, num(f, 0)));
    if (!total) {
      intended = 0;
      intendedDirty = false;
      if (!ready || !geo) return;
      if (geo.paged) setPage(Math.round(fraction * (geo.pages - 1)));
      else scrollToProgress(fraction * geo.maxScroll, false);
      schedulePos();
      return;
    }
    seekChar(Math.round(fraction * total));
  }

  function applySettings(input) {
    // A move not yet settled is read from the layout it was made in, before anything changes.
    if (ready && geo && intendedDirty) settleState();
    var next = normalise(input, settings);
    var furiganaChanged = next.furigana !== settings.furigana;
    settings = next;
    if (furiganaChanged) {
      var revealed = chapter.querySelectorAll('ruby.jp-reveal-rt');
      for (var i = 0; i < revealed.length; i++) revealed[i].classList.remove('jp-reveal-rt');
    }
    applyClasses();
    if (!ready) {
      applyVars();
      return;
    }
    if (layoutKey(settings) === lastKey && geo &&
      geo.W === window.innerWidth && geo.H === window.innerHeight) {
      applyPaint();
      return;
    }
    relayout();
    // A family not loaded yet lays out again once it is.
    if (document.fonts && document.fonts.status === 'loading') {
      document.fonts.ready.then(function () { scheduleRelayout(); });
    }
  }

  // Scroll mode only; px is pixels a 60 Hz frame, upstream's auto-scroll unit. endAt: when the scroll
  // reached the chapter's end (0 while it has not).
  var auto = { raf: 0, rate: 0, last: 0, carry: 0, reported: 0, endAt: 0 };

  function autoScroll(px) {
    var rate = num(px, 0) * 60;
    if (!rate || !ready || !geo || geo.paged) {
      stopAutoScroll();
      return;
    }
    auto.rate = rate;
    if (auto.raf) return;
    auto.last = 0;
    auto.carry = 0;
    auto.endAt = 0;
    auto.reported = performance.now();
    auto.raf = requestAnimationFrame(autoStep);
  }

  function autoStep(now) {
    auto.raf = 0;
    if (!geo || geo.paged) return;
    if (auto.last) {
      auto.carry += auto.rate * (now - auto.last) / 1000;
      var dpr = window.devicePixelRatio || 1;
      var step = Math.trunc(auto.carry * dpr) / dpr;
      if (step) {
        auto.carry -= step;
        var at = scrollProgress();
        if (step > 0 && at >= geo.maxScroll - 1) {
          // The last screen is still being read: the next chapter comes after as long as scrolling
          // that screen away would take, as if the scroll went on into it (upstream's seamless
          // chapters), rather than the moment the chapter's last line comes into view.
          auto.carry = 0;
          if (!auto.endAt) {
            auto.endAt = now;
            postPos(false);
          } else if (now - auto.endAt >= screenExtent() / auto.rate * 1000) {
            postEdge(true);
            return;
          }
        } else {
          auto.endAt = 0;
          scrollToProgress(at + step, true);
        }
      }
    }
    auto.last = now;
    if (now - auto.reported > 1000) {
      auto.reported = now;
      postPos(false);
    }
    auto.raf = requestAnimationFrame(autoStep);
  }

  function stopAutoScroll() {
    if (!auto.raf) return;
    cancelAnimationFrame(auto.raf);
    auto.raf = 0;
    schedulePos();
  }

  // endregion

  // region scroll events

  var settleTimer = 0;

  function onRootScroll() {
    if (!geo) return;
    if (geo.paged) {
      if (window.scrollX !== 0 || window.scrollY !== 0) {
        lockRoot();
        requestAnimationFrame(lockRoot);
      }
      return;
    }
    clearTimeout(settleTimer);
    settleTimer = setTimeout(onScrollSettled, SETTLE_MS);
  }

  function onScrollSettled() {
    if (!ready || !geo || geo.paged) return;
    var at = scrollProgress();
    if (expectedScroll === null || Math.abs(at - expectedScroll) > 1) intendedDirty = true;
    expectedScroll = null;
    postPos(false);
  }

  /* Something other than a page turn scrolled the body (focus, find, a selection): back to the page. */
  function onBodyScroll() {
    if (!geo || !geo.paged) return;
    var at = pagedScroll();
    var expected = Math.min(page * geo.pitch, geo.maxScroll);
    if (Math.abs(at - expected) <= 1) return;
    var nearest = Math.round(at / geo.pitch);
    if (Math.abs(at - nearest * geo.pitch) <= 1 && nearest < geo.pages) {
      page = nearest;
      intendedDirty = true;
      schedulePos();
      return;
    }
    if (geo.vertical) body.scrollTop = expected; else body.scrollLeft = expected;
  }

  // endregion

  // region taps and swipes

  /* A Range over the character at client point (x, y), or null when the point is on none. */
  function charRangeAt(x, y) {
    var hit = document.elementFromPoint(x, y);
    if (hit && isGaiji(hit) && chapter.contains(hit)) {
      var picture = document.createRange();
      picture.selectNode(hit);
      return picture;
    }
    var node = null;
    var offset = 0;
    if (document.caretPositionFromPoint) {
      var position = document.caretPositionFromPoint(x, y);
      if (position) {
        node = position.offsetNode;
        offset = position.offset;
      }
    } else if (document.caretRangeFromPoint) {
      var caret = document.caretRangeFromPoint(x, y);
      if (caret) {
        node = caret.startContainer;
        offset = caret.startOffset;
      }
    }
    if (!node || node.nodeType !== Node.TEXT_NODE || !chapter.contains(node)) return null;
    if (node.parentElement && node.parentElement.closest('rt, rp')) return null;
    var text = node.data;
    var tries = [offset, offset - 1];
    for (var t = 0; t < tries.length; t++) {
      var start = tries[t];
      if (start < 0 || start >= text.length) continue;
      var code = text.charCodeAt(start);
      if (code >= 0xdc00 && code <= 0xdfff && start > 0) start -= 1;
      var end = start + (text.codePointAt(start) > 0xffff ? 2 : 1);
      if (/\s/.test(text.slice(start, end))) continue;
      var range = document.createRange();
      range.setStart(node, start);
      range.setEnd(node, end);
      var rects = range.getClientRects();
      for (var i = 0; i < rects.length; i++) {
        var r = rects[i];
        if (x >= r.left - 0.5 && x <= r.right + 0.5 && y >= r.top - 0.5 && y <= r.bottom + 0.5) return range;
      }
    }
    return null;
  }

  /*
   * A tap on a reading that is shown (rt): the middle of its word's first character, where a lookup
   * starts, or null when the point is on no reading.
   */
  function readingBaseAt(x, y) {
    var hit = document.elementFromPoint(x, y);
    var rt = hit && hit.closest ? hit.closest('rt') : null;
    var ruby = rt ? rt.closest('ruby') : null;
    if (!ruby || !chapter.contains(ruby)) return null;
    var walker = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT);
    for (var node = walker.nextNode(); node; node = walker.nextNode()) {
      if (node.parentElement && node.parentElement.closest('rt, rp')) continue;
      var text = node.data;
      for (var i = 0; i < text.length; i++) {
        if (/\s/.test(text.charAt(i))) continue;
        var range = document.createRange();
        range.setStart(node, i);
        range.setEnd(node, i + (text.codePointAt(i) > 0xffff ? 2 : 1));
        var r = firstRect(range.getClientRects());
        return r ? { x: (r.left + r.right) / 2, y: (r.top + r.bottom) / 2 } : null;
      }
    }
    return null;
  }

  function zoneAction(fx, fy) {
    var zones = settings.tapZones || [];
    for (var i = 0; i < zones.length; i++) {
      var z = zones[i];
      if (!z || z.length < 5) continue;
      if (fx >= z[0] && fx <= z[2] && fy >= z[1] && fy <= z[3]) {
        return z[4] === 'menu' || z[4] === 'back' || z[4] === 'forward' ? z[4] : 'none';
      }
    }
    return 'none';
  }

  /* ttu's furigana tap: reveals a hidden reading (toggle hides it again); true when it acted. */
  function revealFurigana(target) {
    var mode = settings.furigana;
    if (mode !== 'partial' && mode !== 'full' && mode !== 'toggle') return false;
    var ruby = target && target.closest ? target.closest('ruby') : null;
    if (!ruby || !chapter.contains(ruby)) return false;
    if (mode === 'toggle') {
      ruby.classList.toggle('jp-reveal-rt');
      return true;
    }
    if (ruby.classList.contains('jp-reveal-rt')) return false;
    ruby.classList.add('jp-reveal-rt');
    return true;
  }

  function onTap(x, y, target) {
    if (target && target.closest && target.closest('a[href]')) return;
    if (revealFurigana(target)) return;
    var fx = x / window.innerWidth;
    var fy = y / window.innerHeight;
    // A tap on a shown reading looks its word up, from the word's first character.
    var at = settings.tapMode !== 'lookup' ? null : charRangeAt(x, y) ? { x: x, y: y } : readingBaseAt(x, y);
    if (at) {
      var hook = window.JpReader && window.JpReader.onTextTap;
      var handled = false;
      if (typeof hook === 'function') {
        try {
          handled = hook(at.x, at.y) === true;
        } catch (e) {
          handled = false;
        }
      }
      if (!handled) post({ t: 'tap', x: fx, y: fy, action: 'none' });
      return;
    }
    post({ t: 'tap', x: fx, y: fy, action: zoneAction(fx, fy) });
  }

  function onSwipe(dx) {
    var forward = geo.vertical ? dx > 0 : dx < 0;
    if (settings.invertSwipe) forward = !forward;
    turn(forward ? 1 : -1);
  }

  var gesture = null;
  // Scroll mode: where a one-finger touch began and whether the scroll was at an end then.
  var pull = null;

  function installInput() {
    // Scroll mode scrolls natively (the browser's own fling), which cancels the pointer; touch events
    // still end, so a pull past an end of the chapter is read from them.
    document.addEventListener('touchstart', function (e) {
      pull = null;
      if (!ready || !geo || geo.paged || e.touches.length !== 1) return;
      var at = scrollProgress();
      pull = {
        x: e.touches[0].clientX,
        y: e.touches[0].clientY,
        atStart: at <= 1,
        atEnd: at >= geo.maxScroll - 1,
      };
    }, { capture: true, passive: true });

    document.addEventListener('touchend', function (e) {
      var p = pull;
      pull = null;
      if (!p || !ready || !geo || geo.paged || e.touches.length) return;
      var touch = e.changedTouches && e.changedTouches[0];
      if (!touch) return;
      // Forward is a finger moving right in vertical text and up in horizontal text.
      var along = geo.vertical ? touch.clientX - p.x : p.y - touch.clientY;
      var across = geo.vertical ? touch.clientY - p.y : touch.clientX - p.x;
      if (Math.abs(along) < EDGE_PULL_PX || Math.abs(along) < 2 * Math.abs(across)) return;
      if (along > 0 && p.atEnd) postEdge(true);
      else if (along < 0 && p.atStart) postEdge(false);
    }, { capture: true, passive: true });

    document.addEventListener('touchcancel', function () { pull = null; }, { capture: true, passive: true });

    document.addEventListener('pointerdown', function (e) {
      post({ t: 'touch' });
      if (!e.isPrimary) {
        if (gesture) gesture.multi = true;
        return;
      }
      if (e.pointerType === 'mouse' && e.button !== 0) return;
      gesture = { id: e.pointerId, x: e.clientX, y: e.clientY, at: e.timeStamp, moved: false, multi: false };
    }, { capture: true, passive: true });

    document.addEventListener('pointermove', function (e) {
      if (!gesture || e.pointerId !== gesture.id) return;
      if (Math.abs(e.clientX - gesture.x) > TAP_SLOP_PX || Math.abs(e.clientY - gesture.y) > TAP_SLOP_PX) {
        gesture.moved = true;
      }
    }, { capture: true, passive: true });

    document.addEventListener('pointercancel', function (e) {
      if (gesture && e.pointerId === gesture.id) gesture = null;
    }, { capture: true, passive: true });

    document.addEventListener('pointerup', function (e) {
      var g = gesture;
      if (!g || e.pointerId !== g.id) return;
      gesture = null;
      if (g.multi || !ready || !geo) return;
      var dx = e.clientX - g.x;
      var dy = e.clientY - g.y;
      if (geo.paged && Math.abs(dx) >= SWIPE_MIN_PX && Math.abs(dx) > Math.abs(dy)) {
        onSwipe(dx);
        return;
      }
      if (g.moved || Math.abs(dx) > TAP_SLOP_PX || Math.abs(dy) > TAP_SLOP_PX) return;
      if (e.timeStamp - g.at > TAP_MAX_MS) return;
      onTap(e.clientX, e.clientY, e.target);
    }, { capture: true, passive: true });

    // An in-chapter link (a footnote) turns to its target instead of scrolling the body off a page.
    document.addEventListener('click', function (e) {
      var link = e.target && e.target.closest ? e.target.closest('a[href^="#"]') : null;
      if (!link || !chapter.contains(link) || !geo) return;
      var id = decodeURIComponent(link.getAttribute('href').slice(1));
      var target = id && (document.getElementById(id) || document.getElementsByName(id)[0]);
      e.preventDefault();
      if (!target) return;
      var rect = firstRect(target.getClientRects()) || target.getBoundingClientRect();
      if (geo.paged) setPage(pageOfRect(rect));
      else scrollToProgress(scrollProgress() + lineDelta(rect, target), true);
      intendedDirty = true;
      schedulePos();
    }, true);

    // Vertical text scrolls sideways; a wheel or touchpad moving down reads on (leftward).
    document.addEventListener('wheel', function (e) {
      if (!ready || !geo || geo.paged || !geo.vertical || e.ctrlKey) return;
      if (Math.abs(e.deltaY) <= Math.abs(e.deltaX)) return;
      var unit = e.deltaMode === 1 ? settings.fontSize * settings.lineHeight : e.deltaMode === 2 ? geo.W : 1;
      e.preventDefault();
      window.scrollBy({ left: -e.deltaY * unit, top: 0, behavior: 'instant' });
    }, { passive: false });

    window.addEventListener('scroll', onRootScroll, { passive: true });
    body.addEventListener('scroll', onBodyScroll, { passive: true });
    window.addEventListener('resize', scheduleRelayout);
    if (document.fonts && document.fonts.addEventListener) {
      document.fonts.addEventListener('loadingdone', function () { if (ready) scheduleRelayout(); });
    }
  }

  // endregion

  // region read-aloud

  /*
   * Upstream's read-aloud rule: a paragraph is a non-blank line of what the page shows (innerText,
   * with the readings hidden for that one read), whitespace collapsed, trimmed, U+FFFC removed. Each
   * gets a Range over its text nodes, found by matching its non-space characters in order.
   */
  var paraModel = null;

  function paragraphModel() {
    if (paraModel) return paraModel;
    var OBJECT_REPLACEMENT = String.fromCharCode(0xfffc);
    var shown;
    // Readings hidden for this one synchronous read, which never paints.
    root.classList.add('jp-readings-hidden');
    try {
      shown = chapter.innerText || '';
    } finally {
      root.classList.remove('jp-readings-hidden');
    }
    var texts = shown.split('\n').map(function (line) {
      return line.split(OBJECT_REPLACEMENT).join('').replace(/\s+/g, ' ').trim();
    }).filter(function (line) { return line.length > 0; });

    var chars = [];
    var nodes = [];
    var offsets = [];
    var walker = document.createTreeWalker(chapter, NodeFilter.SHOW_TEXT, {
      acceptNode: function (node) {
        var parent = node.parentElement;
        return parent && parent.closest('rt, rp, script, style') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      },
    });
    while (walker.nextNode()) {
      var node = walker.currentNode;
      var value = node.data;
      for (var i = 0; i < value.length; i++) {
        if (/\s/.test(value[i])) continue;
        chars.push(value[i]);
        nodes.push(node);
        offsets.push(i);
      }
    }
    var flat = chars.join('');
    var cursor = 0;
    var starts = texts.map(function (text) {
      var key = text.replace(/\s/g, '');
      var at = flat.indexOf(key, cursor);
      if (at < 0) return -1;
      cursor = at + key.length;
      return at;
    });

    function over(first, last) {
      var range = document.createRange();
      range.setStart(nodes[first], offsets[first]);
      range.setEnd(nodes[last], offsets[last] + 1);
      return range;
    }

    var ranges = texts.map(function (text, i) {
      var length = text.replace(/\s/g, '').length;
      return starts[i] < 0 || !length ? null : over(starts[i], starts[i] + length - 1);
    });

    paraModel = {
      texts: texts,
      ranges: ranges,
      part: function (i, from, to) {
        if (starts[i] < 0) return null;
        var text = texts[i];
        var before = text.slice(0, from).replace(/\s/g, '').length;
        var inside = text.slice(from, to).replace(/\s/g, '').length;
        if (!inside) return null;
        return over(starts[i] + before, starts[i] + before + inside - 1);
      },
    };
    return paraModel;
  }

  function paragraphs() {
    return paragraphModel().texts.slice();
  }

  function intersects(rect, box) {
    return rect.width > 0 && rect.height > 0 &&
      rect.right > box.left && rect.left < box.right && rect.bottom > box.top && rect.top < box.bottom;
  }

  /* The first paragraph with text on screen between top and bottom (CSS px the chrome covers). */
  function firstVisibleParagraph(top, bottom) {
    var model = paragraphModel();
    var box = {
      left: 0,
      right: window.innerWidth,
      top: Math.max(0, num(top, 0)),
      bottom: window.innerHeight - Math.max(0, num(bottom, 0)),
    };
    for (var i = 0; i < model.ranges.length; i++) {
      var range = model.ranges[i];
      if (!range) continue;
      var rects = range.getClientRects();
      for (var j = 0; j < rects.length; j++) if (intersects(rects[j], box)) return i;
    }
    return -1;
  }

  function clearHighlight() {
    if (window.CSS && CSS.highlights) CSS.highlights.delete(HIGHLIGHT_NAME);
  }

  /* Marks paragraph i (from start to end of its text, or all of it for -1); turns to it when off screen. */
  function highlight(i, start, end) {
    var index = num(i, -1);
    if (index < 0) {
      clearHighlight();
      return;
    }
    var model = paragraphModel();
    var from = num(start, -1);
    var to = num(end, -1);
    var range = from >= 0 && to > from ? model.part(index, from, to) : null;
    if (!range) range = model.ranges[index] || null;
    if (!range) {
      clearHighlight();
      return;
    }
    if (window.CSS && CSS.highlights && typeof Highlight === 'function') {
      CSS.highlights.set(HIGHLIGHT_NAME, new Highlight(range));
    }
    follow(range);
  }

  function follow(range) {
    if (!ready || !geo) return;
    var rect = firstRect(range.getClientRects());
    if (!rect) return;
    if (geo.paged) {
      var target = pageOfRect(rect);
      if (target === page) return;
      setPage(target);
    } else {
      var box = {
        left: 0,
        right: geo.W,
        top: geo.insetTop,
        bottom: geo.H - geo.insetBottom,
      };
      if (rect.left >= box.left && rect.right <= box.right && rect.top >= box.top && rect.bottom <= box.bottom) return;
      scrollToProgress(scrollProgress() + lineDelta(rect, range.startContainer), true);
    }
    intendedDirty = true;
    schedulePos();
  }

  // endregion

  // region start

  var api = window.JpReader || {};
  api.__started = true;
  api.applySettings = applySettings;
  api.turn = turn;
  api.seekFraction = seekFraction;
  api.seekChar = seekChar;
  api.autoScroll = autoScroll;
  api.paragraphs = paragraphs;
  api.firstVisibleParagraph = firstVisibleParagraph;
  api.highlight = highlight;
  api.state = function () {
    if (ready && geo) return settleState();
    return computeState();
  };
  api.charRangeAt = charRangeAt;
  if (!('onTextTap' in api)) api.onTextTap = null;
  window.JpReader = api;

  function start() {
    ensureViewportMeta();
    applyClasses();
    applyVars();
    dropRepeatedTitle();
    wrapTateChuYoko();
    buildUnits();
    if (init.charOffset !== null && init.charOffset !== undefined && isFinite(Number(init.charOffset))) {
      intended = Math.max(0, Math.min(total, Math.round(Number(init.charOffset))));
    } else {
      intended = Math.round(Math.max(0, Math.min(1, num(init.fraction, 0))) * total);
    }
    installInput();
    Promise.all([imagesSettled(), fontsSettled()]).then(function () {
      measure();
      landOn(intended);
      ready = true;
      var state = settleState();
      lastPosted = JSON.stringify(state);
      post({ t: 'ready', pos: state });
    });
  }

  start();

  // endregion
})();
