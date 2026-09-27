/*
 * Reikai JP: the Yomitan spike's vertical reader (roadmap 2.1). GPL-3.0-or-later.
 * Yomitan's own content script (loaded by reader.html) does the scanning; this only records when
 * a tap happens and where the test words are, so scripts/fork/yomitan_spike.py can tap them.
 */
(() => {
    const post = (header, payload = '') => globalThis.reikaiHub.postMessage(`${JSON.stringify(header)}\n${payload}`);
    const now = (event) => performance.timeOrigin + event.timeStamp;
    addEventListener('pointerdown', (e) => post({t: 'result', name: 'tap_down'}, JSON.stringify({at: now(e)})), {capture: true, passive: true});
    addEventListener('pointerup', (e) => post({t: 'result', name: 'tap_up'}, JSON.stringify({at: now(e)})), {capture: true, passive: true});

    const WORDS = ['喫茶店', '傘', '冷めて', '連絡', '小説', '主人公', '巻き込まれて', '不思議', '馬酔木'];
    const locate = () => {
        const rects = {};
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
            if (node.parentElement?.closest('rt')) { continue; }
            for (const word of WORDS) {
                const i = node.data.indexOf(word);
                if (i < 0 || rects[word]) { continue; }
                const range = document.createRange();
                range.setStart(node, i);
                range.setEnd(node, i + 1);
                const r = range.getBoundingClientRect();
                rects[word] = {x: r.left + r.width / 2, y: r.top + r.height / 2};
            }
        }
        post({t: 'rects', name: 'targets', done: true}, JSON.stringify(rects));
    };
    addEventListener('load', () => requestAnimationFrame(locate));
    // The page can scroll (a narrow phone holds fewer columns): report the words' new places.
    let pending = 0;
    addEventListener('scroll', () => {
        clearTimeout(pending);
        pending = setTimeout(locate, 150);
    }, {passive: true});
})();
