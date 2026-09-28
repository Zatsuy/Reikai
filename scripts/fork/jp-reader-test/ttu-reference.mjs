/*
 * The reference the tests hold jp-reader.js's count to: ttu-ebook-reader's own functions, turned
 * from TypeScript into plain JavaScript and otherwise unchanged, evaluated in the test page as
 * window.ttuRef. From apps/web/src/lib/functions/get-character-count.ts, is-node-gaiji.ts,
 * is-element-gaiji.ts and apps/web/src/lib/components/book-reader/get-paragraph-nodes.ts:
 *
 * @license BSD-3-Clause
 * Copyright (c) 2026, ッツ Reader Authors
 * All rights reserved.
 * (Licence text: LICENSES/BSD-3-Clause.txt.)
 */
export const TTU_REFERENCE = `(() => {
  function isElementGaiji(el) {
    return Array.from(el.classList).some((className) => className.includes('gaiji'));
  }

  function isNodeGaiji(node) {
    if (!(node instanceof HTMLImageElement)) {
      return false;
    }
    return isElementGaiji(node);
  }

  function getCharacterCount(node) {
    return isNodeGaiji(node) ? 1 : getRawCharacterCount(node);
  }

  const isNotJapaneseRegex =
    /[^0-9A-Z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚｦ-ﾝ\\p{Radical}\\p{Unified_Ideograph}]+/gimu;

  function getRawCharacterCount(node) {
    if (!node.textContent) return 0;
    return countUnicodeCharacters(node.textContent.replace(isNotJapaneseRegex, ''));
  }

  function countUnicodeCharacters(s) {
    return Array.from(s).length;
  }

  function getParagraphNodes(node) {
    return getTextNodeOrGaijiNodes(node, (n) => {
      if (n.nodeName === 'RT') {
        return false;
      }
      const isHidden =
        n instanceof HTMLElement &&
        (n.attributes.getNamedItem('aria-hidden') || n.attributes.getNamedItem('hidden'));
      if (isHidden) {
        return false;
      }
      return true;
    }).filter((n) => {
      if (isNodeGaiji(n)) {
        return true;
      }
      if (n.textContent?.replace(/\\s/g, '').length) {
        return true;
      }
      return false;
    });
  }

  function getTextNodeOrGaijiNodes(node, filterFn) {
    if (!node.hasChildNodes() || !filterFn(node)) {
      return [];
    }

    return Array.from(node.childNodes)
      .flatMap((n) => {
        if (n.nodeType === Node.TEXT_NODE) {
          return [n];
        }
        if (isNodeGaiji(n)) {
          return [n];
        }
        return getTextNodeOrGaijiNodes(n, filterFn);
      })
      .filter(filterFn);
  }

  // The chapter's total, as ttu's calculators add it up (accumulated getCharacterCount).
  function countNodes(root) {
    return getParagraphNodes(root).reduce((sum, n) => sum + getCharacterCount(n), 0);
  }

  window.ttuRef = { getParagraphNodes, getCharacterCount, countNodes };
})();`;
