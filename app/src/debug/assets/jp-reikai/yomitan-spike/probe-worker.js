// Spike probe: can a dedicated worker load files from the private origin and start its own worker?
const report = (what) => self.postMessage(what);
fetch('/lib/resvg.wasm').then((r) => report(`worker fetch /lib/resvg.wasm: ${r.status}`), (e) => report(`worker fetch failed: ${e}`));
try {
    const child = new Worker('/__reikai/probe-child.js');
    child.onmessage = (e) => report(`nested worker said: ${e.data}`);
    child.onerror = (e) => report(`nested worker error: ${e.message ?? 'no message'}`);
} catch (e) {
    report(`nested worker threw: ${e}`);
}
try {
    const blocked = indexedDB.open('dict', 60);
    blocked.onsuccess = () => { report(`worker opened IndexedDB dict v${blocked.result.version}`); blocked.result.close(); };
    blocked.onerror = () => report(`worker IndexedDB error ${blocked.error}`);
    blocked.onblocked = () => report('worker IndexedDB blocked');
} catch (e) {
    report(`worker IndexedDB threw ${e}`);
}
