/*
 * Reikai JP (roadmap 3.1). GPL-3.0-or-later.
 * Imported first by workers/dictionary-worker-main.js. zip.js inflates archives in workers of its
 * own, and a worker never starts a worker in Android WebView, so Yomitan's dictionary import would
 * wait forever. This turns zip.js's workers off on the one shared /lib/zip.js instance before
 * Yomitan's importer loads (the importer's own configure() later sets only workerScripts); zip.js
 * then inflates in the import worker's thread with the native DecompressionStream.
 */
import {configure} from '/lib/zip.js';

configure({useWebWorkers: false});
