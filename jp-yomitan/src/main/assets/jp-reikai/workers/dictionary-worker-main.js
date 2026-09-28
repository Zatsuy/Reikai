/*
 * Reikai JP (roadmap 3.1). GPL-3.0-or-later.
 * The stand-in starts this in place of Yomitan's /js/dictionary/dictionary-worker-main.js (the
 * dictionary import worker): zip.js is set up first, then Yomitan's own, unmodified entry runs.
 * Module imports run in order, so the configuration is in place before any archive is read.
 */
import '/__reikai/zip-inline.js';
import '/js/dictionary/dictionary-worker-main.js';
