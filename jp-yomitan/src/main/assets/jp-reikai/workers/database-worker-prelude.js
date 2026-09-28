/*
 * Reikai JP (roadmap 3.1). GPL-3.0-or-later.
 * Runs before Yomitan's database worker entry (workers/dictionary-database-worker-main.js).
 *
 * 1. Yomitan's worker listens for messages only after it has opened the database and loaded the
 *    picture renderer, so a message posted to a new worker would be lost; messages are held here
 *    until Yomitan's listener exists, then handed to it in order.
 * 2. This worker keeps the dictionary database open, which would block Yomitan's "delete all
 *    dictionaries" and any schema upgrade, so the database is closed as soon as another connection
 *    asks for that (versionchange), and the page is told to stop this worker.
 */
const held = [];
let listener = null;
const hold = (event) => {
    if (listener !== null) { return; }
    held.push(event);
    event.stopImmediatePropagation();
};
self.addEventListener('message', hold);

const addEventListener = self.addEventListener.bind(self);
self.addEventListener = (type, fn, options) => {
    addEventListener(type, fn, options);
    if (type === 'message' && listener === null && fn !== hold) {
        listener = fn;
        self.removeEventListener('message', hold);
        for (const event of held.splice(0)) { fn.call(self, event); }
    }
};

const open = IDBFactory.prototype.open;
IDBFactory.prototype.open = function (...args) {
    const request = open.apply(this, args);
    request.addEventListener('success', () => {
        const db = request.result;
        db.addEventListener('versionchange', () => {
            db.close();
            self.postMessage({reikai: 'db-closed'});
        });
    });
    return request;
};
