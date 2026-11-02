/** Settings and watch history. IndexedDB, one database, two stores. */

import { CINEMETA } from "./stremio.js";

const DB = "zephyr";
const VERSION = 1;

export function defaults() {
  const lang = (navigator.language || "it").slice(0, 2);
  return {
    lang: ["it", "en", "es", "fr"].indexOf(lang) === -1 ? "en" : lang,
    theme: "dark",
    clock: "auto",
    night: false,
    nightFrom: 23 * 60,
    nightTo: 6 * 60,
    tmdbKey: "",
    tmdbLang: lang === "it" ? "it-IT" : lang === "es" ? "es-ES" : lang === "fr" ? "fr-FR" : "en-US",
    tmdbSource: "trending",
    weather: false,
    weatherPlace: null,
    addons: [CINEMETA],
    adult: false,
    user: "default",
    users: [{ id: "default", name: "" }],
    epgUrl: "",
  };
}

function open() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains("kv")) db.createObjectStore("kv");
      if (!db.objectStoreNames.contains("history")) db.createObjectStore("history");
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function txDone(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error || new Error("abort"));
  });
}

let dbp;
function db() {
  if (!dbp) dbp = open();
  return dbp;
}

export async function loadSettings() {
  const base = defaults();
  const database = await db();
  const saved = await new Promise((resolve, reject) => {
    const req = database.transaction("kv").objectStore("kv").get("settings");
    req.onsuccess = () => resolve(req.result || null);
    req.onerror = () => reject(req.error);
  });
  const merged = Object.assign(base, saved || {});
  if (!Array.isArray(merged.addons) || !merged.addons.length) merged.addons = [CINEMETA];
  if (!Array.isArray(merged.users) || !merged.users.length) merged.users = base.users;
  return merged;
}

export async function saveSettings(settings) {
  const database = await db();
  const tx = database.transaction("kv", "readwrite");
  tx.objectStore("kv").put(settings, "settings");
  await txDone(tx);
}

export async function loadHistory(user) {
  const database = await db();
  const all = await new Promise((resolve, reject) => {
    const req = database.transaction("history").objectStore("history").getAll();
    req.onsuccess = () => resolve(req.result || []);
    req.onerror = () => reject(req.error);
  });
  return all.filter((row) => row && row.user === user && row.position > 15)
    .sort((a, b) => b.updated - a.updated)
    .slice(0, 30);
}

export async function saveProgress(entry) {
  const database = await db();
  const tx = database.transaction("history", "readwrite");
  tx.objectStore("history").put(entry, entry.key);
  await txDone(tx);
}

export async function loadProblems() {
  const database = await db();
  const rows = await new Promise((resolve, reject) => {
    const req = database.transaction("kv").objectStore("kv").get("problems");
    req.onsuccess = () => resolve(req.result || []);
    req.onerror = () => reject(req.error);
  });
  return Array.isArray(rows) ? rows : [];
}

export async function pushProblem(text) {
  const rows = await loadProblems();
  rows.unshift({ at: Date.now(), text: String(text).slice(0, 240) });
  const database = await db();
  const tx = database.transaction("kv", "readwrite");
  tx.objectStore("kv").put(rows.slice(0, 20), "problems");
  await txDone(tx);
}
