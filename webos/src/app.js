/** Zephyr TV on webOS. One screen at a time, keys from the remote. */

import { t } from "./i18n.js";
import { focusId, installKeys, isEnter } from "./nav.js";
import { launchApp, listApps, platformBack, sdkVersion } from "./platform.js";
import { start, stop } from "./player.js";
import { loadHistory, loadProblems, loadSettings, pushProblem, saveProgress, saveSettings } from "./store.js";
import {
  catalogUrl, enc, getJson, manifestUrl, metaUrl, parseAddon, parseMeta, parseMetas, parseStreams, provides, streamUrl,
} from "./stremio.js";
import { formatClock, hhmm, inNight } from "./time.js";
import { screenHtml, spotlightHtml } from "./view.js";

const state = {
  screen: "home",
  settings: null,
  addons: [],
  rows: [],
  history: [],
  apps: [],
  spotlight: [],
  spot: 0,
  detail: null,
  streams: [],
  streamAt: 0,
  tries: 0,
  playItem: null,
  returnScreen: "home",
  zapList: null,
  zapIndex: 0,
  query: "",
  results: [],
  problems: [],
  notice: "",
  edit: null,
  sdk: "",
  weather: null,
  night: false,
  epg: {},
};

let lastAct = 0;

function lang() { return state.settings.lang; }
function tr(key) { return t(lang(), key); }
function appRoot() { return document.getElementById("app"); }

function applyChrome() {
  const s = state.settings;
  state.night = inNight(new Date(), s.nightFrom, s.nightTo, s.night);
  let theme = s.theme;
  if (theme === "auto") {
    const light = window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches;
    theme = light ? "light" : "dark";
  }
  document.body.dataset.theme = theme;
  document.body.classList.toggle("night", state.night);
  document.documentElement.lang = s.lang === "en" ? "en" : s.lang;
}

function render() {
  const fid = document.activeElement && document.activeElement.dataset && document.activeElement.dataset.fid;
  document.body.dataset.screen = state.screen;
  appRoot().innerHTML = screenHtml(state);
  if (state.screen === "edit") {
    const input = document.getElementById("editor");
    if (input) input.focus();
    return;
  }
  if (state.screen === "search") {
    const input = document.getElementById("q");
    if (input && !fid) { input.focus(); return; }
  }
  if (state.screen === "player") {
    focusId(appRoot(), "player-pause");
    return;
  }
  focusId(appRoot(), fid);
}

async function problem(text) {
  if (!text) return;
  await pushProblem(text);
  state.problems = await loadProblems();
}

async function persist() {
  await saveSettings(state.settings);
  applyChrome();
}

export async function boot() {
  state.settings = await loadSettings();
  state.problems = await loadProblems();
  applyChrome();
  installKeys(document.body, {
    back,
    activate,
    playerKey,
    submit() {
      if (state.screen === "search") { state.query = inputValue("q"); runSearch(); }
      if (state.screen === "edit") saveEdit();
    },
  });
  render();
  setInterval(clock, 30000);
  setInterval(rotate, 8000);
  refresh();
}

function clock() {
  const was = state.night;
  applyChrome();
  const el = document.getElementById("clock");
  if (el) el.textContent = formatClock(new Date(), state.settings.clock);
  if (was !== state.night && state.screen === "home") render();
}

function rotate() {
  if (state.screen !== "home" || state.night || state.spotlight.length < 2) return;
  state.spot = (state.spot + 1) % state.spotlight.length;
  const el = document.getElementById("spot");
  if (!el) return;
  const focused = document.activeElement && document.activeElement.dataset && document.activeElement.dataset.fid === "spot-play";
  el.outerHTML = spotlightHtml(state);
  if (focused) focusId(document.body, "spot-play");
}

async function refresh() {
  state.notice = tr("loading");
  if (state.screen === "home") render();
  try {
    state.history = await loadHistory(state.settings.user);
    state.apps = await listApps();
    state.sdk = await sdkVersion();
    state.epg = await loadEpg();
    state.addons = await loadAddons();
    state.rows = await loadRows(state.addons);
    state.spotlight = state.settings.tmdbKey && !inNight(new Date(), state.settings.nightFrom, state.settings.nightTo, state.settings.night)
      ? await loadSpotlight() : [];
    state.weather = state.settings.weather ? await loadWeather() : null;
  } catch (e) {
    await problem(e.message);
  }
  state.notice = "";
  if (state.screen === "home") render();
}

async function loadAddons() {
  const out = [];
  for (const url of state.settings.addons) {
    try {
      const manifest = await getJson(url);
      const addon = parseAddon(url, manifest);
      if (addon.adult && !state.settings.adult) continue;
      out.push(addon);
    } catch (e) {
      await problem(url + " " + (e.message || e));
    }
  }
  return out;
}

async function loadRows(addons) {
  const jobs = [];
  addons.forEach((addon) => {
    const cats = addon.vod.filter((c) => c.listed).concat(addon.catalogs).slice(0, 4);
    cats.forEach((cat) => jobs.push({ addon, cat }));
  });
  const rows = [];
  for (let i = 0; i < jobs.length; i += 3) {
    const part = await Promise.all(jobs.slice(i, i + 3).map(async (job) => {
      try {
        const page = await getJson(catalogUrl(job.addon.base, job.cat.type, job.cat.id));
        const metas = parseMetas(page).slice(0, 40);
        metas.forEach((item) => {
          if (state.epg[item.id]) item.now = state.epg[item.id];
        });
        if (!metas.length) return null;
        return { id: job.addon.id + ":" + job.cat.type + ":" + job.cat.id, title: job.cat.name, addon: job.addon, cat: job.cat, metas };
      } catch (e) {
        await problem(job.cat.name + " " + (e.message || e));
        return null;
      }
    }));
    part.forEach((row) => { if (row) rows.push(row); });
  }
  return rows;
}

async function loadSpotlight() {
  const key = state.settings.tmdbKey;
  const language = state.settings.tmdbLang;
  const data = await getJson("https://api.themoviedb.org/3/trending/all/day?language=" + enc(language) + "&api_key=" + enc(key));
  return (data.results || []).filter((r) => r.media_type === "movie" || r.media_type === "tv").slice(0, 12).map((r) => ({
    tmdb: r.id,
    kind: r.media_type,
    name: r.title || r.name || "",
    overview: r.overview || "",
    backdrop: r.backdrop_path ? "https://image.tmdb.org/t/p/w1280" + r.backdrop_path : "",
    poster: r.poster_path ? "https://image.tmdb.org/t/p/w342" + r.poster_path : "",
  }));
}

async function loadWeather() {
  let place = state.settings.weatherPlace;
  if (!place) {
    const geo = await getJson("https://ipwho.is/?fields=success,city,latitude,longitude");
    if (!geo.success) return null;
    place = { name: geo.city, lat: geo.latitude, lon: geo.longitude };
    state.settings.weatherPlace = place;
    await saveSettings(state.settings);
  }
  const w = await getJson("https://api.open-meteo.com/v1/forecast?latitude=" + place.lat + "&longitude=" + place.lon + "&current=temperature_2m");
  return { name: place.name, temp: Math.round(w.current.temperature_2m) };
}

async function loadEpg() {
  const url = state.settings.epgUrl;
  if (!url) return {};
  try {
    const res = await fetch(url);
    const text = await res.text();
    if (!res.ok || text.length > 2500000) return {};
    const xml = new DOMParser().parseFromString(text, "text/xml");
    const now = Date.now();
    const map = {};
    xml.querySelectorAll("programme").forEach((p) => {
      const start = xmltvDate(p.getAttribute("start"));
      const stop = xmltvDate(p.getAttribute("stop"));
      if (!start || !stop || start > now || stop < now) return;
      const channel = p.getAttribute("channel");
      const title = p.querySelector("title");
      if (channel && title && title.textContent) map[channel] = title.textContent;
    });
    return map;
  } catch (e) {
    return {};
  }
}

function xmltvDate(s) {
  if (!s || s.length < 14) return null;
  const iso = s.slice(0, 4) + "-" + s.slice(4, 6) + "-" + s.slice(6, 8) + "T" + s.slice(8, 10) + ":" + s.slice(10, 12) + ":" + s.slice(12, 14);
  const n = Date.parse(iso);
  return n === n ? n : null;
}

function activate(el) {
  const now = Date.now();
  if (now - lastAct < 250) return;
  lastAct = now;
  const act = el.dataset.act;
  if (act === "back") return back();
  if (act === "search") { state.notice = ""; state.screen = "search"; state.results = []; render(); return; }
  if (act === "settings") { state.notice = ""; state.screen = "settings"; render(); return; }
  if (act === "goto") return goto(el.dataset.target);
  if (act === "launch") return launchApp(el.dataset.app).catch((e) => problem(e.message || "launch"));
  if (act === "open") return openTitle(el.dataset.type, el.dataset.id, el.dataset.base);
  if (act === "live") return playLive(el);
  if (act === "resume") return resume(el.dataset.key);
  if (act === "spot") return playSpot(Number(el.dataset.index));
  if (act === "result") return openResult(Number(el.dataset.index));
  if (act === "watch") return watchDetail(state.detail.id);
  if (act === "episode") return watchDetail(el.dataset.id);
  if (act === "stream") return playStream(Number(el.dataset.index));
  if (act === "pause") return pause();
  if (act === "next-stream") { state.tries = 0; return nextStream(1); }
  if (act === "run-search") { state.query = inputValue("q"); return runSearch(); }
  if (act === "cycle-theme") return cycle("theme", ["dark", "light", "glass", "auto"]);
  if (act === "cycle-clock") return cycle("clock", ["auto", "24", "12"]);
  if (act === "cycle-lang") return cycle("lang", ["it", "en", "es", "fr"]);
  if (act === "toggle") return toggle(el.dataset.key);
  if (act === "edit") return beginEdit(el.dataset.key);
  if (act === "save-edit") return saveEdit();
  if (act === "user") return switchUser(el.dataset.user);
  if (act === "remove-addon") return removeAddon(el.dataset.url);
}

function back() {
  state.notice = "";
  if (state.screen === "player") {
    remember();
    const video = document.getElementById("video");
    if (video) stop(video);
    state.screen = state.returnScreen || "home";
    render();
    return;
  }
  if (state.screen === "edit") { state.screen = "settings"; render(); return; }
  if (state.screen !== "home") { state.screen = "home"; render(); return; }
  platformBack();
}

function playerKey(event) {
  if (state.screen !== "player") return false;
  if (event.keyCode === 461 || event.key === "Escape" || event.keyCode === 10009) return false;
  const live = state.playItem && state.playItem.live;
  if (event.keyCode === 37) { if (live) nextStream(-1); else seek(-10); return true; }
  if (event.keyCode === 39) { if (live) nextStream(1); else seek(10); return true; }
  if (event.keyCode === 33 || event.keyCode === 427) { zap(-1); return true; }
  if (event.keyCode === 34 || event.keyCode === 428) { zap(1); return true; }
  if (isEnter(event) && document.activeElement && document.activeElement.dataset && document.activeElement.dataset.act) return false;
  if (isEnter(event)) { pause(); return true; }
  return false;
}

function goto(target) {
  const type = target === "series" ? "series" : "movie";
  const row = state.rows.find((r) => r.cat.type === type);
  if (!row) return;
  const el = appRoot().querySelector('[data-row="' + css(row.id) + '"]');
  if (el) el.focus();
}

function css(value) {
  return window.CSS && CSS.escape ? CSS.escape(value) : value;
}

async function openTitle(type, id, base) {
  state.detail = { name: id, type: type, id: id, base: base, description: "", poster: "", episodes: [] };
  state.streams = [];
  state.notice = tr("loading");
  state.screen = "detail";
  render();
  try {
    const meta = parseMeta(await getJson(metaUrl(base, type, id)), type);
    if (meta) {
      meta.base = base;
      state.detail = meta;
    }
  } catch (e) {
    await problem(e.message || String(e));
  }
  state.notice = "";
  if (state.screen === "detail") render();
}

async function playLive(el) {
  const row = state.rows.find((r) => r.id === el.dataset.rowid);
  const metas = row ? row.metas : [{ id: el.dataset.id, type: el.dataset.type, name: el.textContent, poster: "" }];
  state.zapList = metas;
  state.zapIndex = Math.max(0, metas.findIndex((m) => m.id === el.dataset.id));
  const item = metas[state.zapIndex] || metas[0];
  await watch(item.type || "tv", item.id, item.name, item.poster || item.logo, el.dataset.base, 0, true);
}

async function resume(key) {
  const item = state.history.find((h) => h.key === key);
  if (!item) return;
  await watch(item.type, item.id, item.name, item.poster, item.base, item.position, false);
}

async function playSpot(index) {
  const item = state.spotlight[index];
  if (item) await playTmdb(item);
}

async function openResult(index) {
  const item = state.results[index];
  if (!item) return;
  if (item.tmdb) return playTmdb(item);
  if (item.type === "tv") {
    state.zapList = [item];
    state.zapIndex = 0;
    return watch(item.type, item.id, item.name, item.poster, item.base, 0, true);
  }
  return openTitle(item.type, item.id, item.base);
}

async function playTmdb(item) {
  state.notice = tr("loading");
  state.screen = "detail";
  state.detail = { name: item.name, type: item.kind === "tv" ? "series" : "movie", id: "", base: "", description: item.overview, poster: item.poster, episodes: [] };
  state.streams = [];
  render();
  try {
    const path = item.kind === "tv" ? "tv" : "movie";
    const type = item.kind === "tv" ? "series" : "movie";
    const data = await getJson("https://api.themoviedb.org/3/" + path + "/" + item.tmdb + "/external_ids?api_key=" + enc(state.settings.tmdbKey));
    if (!data.imdb_id) throw new Error("imdb");
    const addon = state.addons.find((a) => provides(a, "meta", type, data.imdb_id)) || state.addons[0];
    if (!addon) throw new Error("addon");
    await openTitle(type, data.imdb_id, addon.base);
  } catch (e) {
    await problem(e.message || String(e));
    state.notice = tr("noStreams");
    render();
  }
}

async function watchDetail(id) {
  const d = state.detail;
  if (!d) return;
  await watch(d.type, id, d.name, d.poster, d.base, 0, d.type === "tv");
}

async function watch(type, id, name, poster, base, position, live) {
  state.notice = tr("loading");
  if (state.screen !== "player") {
    state.screen = state.screen === "home" ? "home" : state.screen;
    render();
  }
  const streams = await collectStreams(type, id);
  state.streams = streams;
  state.streamAt = 0;
  state.tries = 0;
  if (!streams.length) {
    state.notice = tr("noStreams");
    if (state.screen !== "player") render();
    return;
  }
  state.playItem = { type, id, name, poster, base, position: position || 0, live: !!live };
  state.returnScreen = state.screen === "detail" ? "detail" : "home";
  state.screen = "player";
  state.notice = "";
  render();
  await playCurrent();
}

async function collectStreams(type, id) {
  const out = [];
  for (const addon of state.addons) {
    if (!provides(addon, "stream", type, id)) continue;
    try {
      const page = await getJson(streamUrl(addon.base, type, id), 20000);
      parseStreams(page).forEach((s) => out.push(s));
    } catch (e) {
      await problem(addon.name + " " + (e.message || e));
    }
  }
  return out;
}

async function playStream(index) {
  const d = state.detail;
  state.streamAt = index;
  state.tries = 0;
  state.playItem = { type: d.type, id: d.id, name: d.name, poster: d.poster, base: d.base, position: 0, live: d.type === "tv" };
  state.returnScreen = "detail";
  state.screen = "player";
  render();
  await playCurrent();
}

async function playCurrent() {
  const video = document.getElementById("video");
  const stream = state.streams[state.streamAt];
  if (!video || !stream) return;
  const label = document.getElementById("stream-label");
  if (label) label.textContent = stream.title || "";
  try {
    await start(video, stream, (state.playItem.position || 0) * 1000);
    state.playItem.position = 0;
    video.onerror = () => { video.onerror = null; nextStream(1); };
    video.ontimeupdate = () => {
      const now = Date.now();
      if (now - (video._saved || 0) < 15000) return;
      video._saved = now;
      remember();
    };
  } catch (e) {
    nextStream(1);
  }
}

function nextStream(delta) {
  if (state.screen !== "player" || !state.streams.length) return;
  state.tries += 1;
  if (state.tries > state.streams.length) {
    const label = document.getElementById("stream-label");
    if (label) label.textContent = tr("noStreams");
    return;
  }
  const n = state.streams.length;
  state.streamAt = (state.streamAt + delta + n) % n;
  const label = document.getElementById("stream-label");
  if (label) label.textContent = tr("failed");
  playCurrent();
}

async function zap(delta) {
  if (!state.zapList || !state.zapList.length) return;
  state.zapIndex = (state.zapIndex + delta + state.zapList.length) % state.zapList.length;
  const item = state.zapList[state.zapIndex];
  const streams = await collectStreams(item.type || "tv", item.id);
  if (!streams.length) return;
  state.streams = streams;
  state.streamAt = 0;
  state.tries = 0;
  state.playItem = { type: item.type || "tv", id: item.id, name: item.name, poster: item.poster, base: state.playItem.base, position: 0, live: true };
  const title = document.querySelector(".banner strong");
  if (title) title.textContent = item.name;
  await playCurrent();
}

function seek(sec) {
  const video = document.getElementById("video");
  if (!video || video.currentTime !== video.currentTime) return;
  video.currentTime = Math.max(0, video.currentTime + sec);
}

function pause() {
  const video = document.getElementById("video");
  if (!video) return;
  if (video.paused) video.play().catch(() => {});
  else video.pause();
}

function remember() {
  const video = document.getElementById("video");
  const item = state.playItem;
  if (!video || !item || item.live) return;
  const position = video.currentTime || 0;
  if (position < 15) return;
  saveProgress({
    key: state.settings.user + ":" + item.type + ":" + item.id,
    user: state.settings.user,
    type: item.type,
    id: item.id,
    name: item.name,
    poster: item.poster || "",
    base: item.base || "",
    position,
    duration: video.duration || 0,
    updated: Date.now(),
  }).catch((e) => problem(e.message));
}

async function runSearch() {
  const q = state.query.trim();
  state.results = [];
  if (!q) { render(); return; }
  state.notice = tr("loading");
  render();
  const results = [];
  if (state.settings.tmdbKey) {
    try {
      const data = await getJson("https://api.themoviedb.org/3/search/multi?include_adult=false&language=" +
        enc(state.settings.tmdbLang) + "&query=" + enc(q) + "&api_key=" + enc(state.settings.tmdbKey));
      (data.results || []).forEach((r) => {
        if (r.media_type !== "movie" && r.media_type !== "tv") return;
        results.push({
          act: "result",
          tmdb: r.id,
          kind: r.media_type,
          name: r.title || r.name || "",
          overview: r.overview || "",
          poster: r.poster_path ? "https://image.tmdb.org/t/p/w342" + r.poster_path : "",
        });
      });
    } catch (e) {
      await problem(e.message || String(e));
    }
  }
  for (const addon of state.addons) {
    const cats = addon.vod.filter((c) => c.search).concat(addon.catalogs.filter((c) => c.search)).slice(0, 3);
    for (const cat of cats) {
      try {
        const page = await getJson(catalogUrl(addon.base, cat.type, cat.id, "search=" + enc(q)));
        parseMetas(page).slice(0, 16).forEach((m) => {
          results.push(Object.assign({}, m, { base: addon.base, act: "result" }));
        });
      } catch (e) { /* This catalog has no search page. */ }
    }
  }
  state.results = results;
  state.notice = results.length ? "" : tr("empty");
  state.screen = "search";
  render();
}

function inputValue(id) {
  const el = document.getElementById(id);
  return el ? el.value : "";
}

async function cycle(key, values) {
  const i = values.indexOf(state.settings[key]);
  state.settings[key] = values[(i + 1) % values.length];
  await persist();
  render();
  if (key === "lang") refresh();
}

async function toggle(key) {
  state.settings[key] = !state.settings[key];
  await persist();
  render();
  if (key === "weather" || key === "adult" || key === "night") refresh();
}

function beginEdit(key) {
  const s = state.settings;
  let value = "";
  let label = key;
  if (key === "tmdbKey") { value = s.tmdbKey; label = tr("tmdb"); }
  else if (key === "epgUrl") { value = s.epgUrl; label = tr("epg"); }
  else if (key === "nightFrom") { value = hhmm(s.nightFrom); label = tr("nightFrom"); }
  else if (key === "nightTo") { value = hhmm(s.nightTo); label = tr("nightTo"); }
  else if (key === "addon") { value = ""; label = tr("addonHint"); }
  else if (key === "newUser") { value = ""; label = tr("userName"); }
  state.edit = { key, value, label };
  state.screen = "edit";
  render();
}

async function saveEdit() {
  const value = inputValue("editor").trim();
  const key = state.edit && state.edit.key;
  state.screen = "settings";
  if (key === "tmdbKey") state.settings.tmdbKey = value;
  else if (key === "epgUrl") state.settings.epgUrl = value;
  else if (key === "nightFrom" || key === "nightTo") {
    const mins = parseHHMM(value);
    if (mins == null) { render(); return; }
    state.settings[key] = mins;
  } else if (key === "addon") {
    const url = manifestUrl(value);
    if (url && state.settings.addons.indexOf(url) === -1) state.settings.addons.push(url);
  } else if (key === "newUser" && value) {
    const id = "u" + Date.now();
    state.settings.users.push({ id, name: value });
    state.settings.user = id;
  }
  await persist();
  render();
  if (key === "tmdbKey" || key === "addon" || key === "epgUrl" || key === "newUser") refresh();
}

function parseHHMM(s) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(s);
  if (!m) return null;
  const h = Number(m[1]);
  const min = Number(m[2]);
  if (h > 23 || min > 59) return null;
  return h * 60 + min;
}

async function switchUser(id) {
  state.settings.user = id;
  await persist();
  state.screen = "home";
  refresh();
}

async function removeAddon(url) {
  state.settings.addons = state.settings.addons.filter((u) => u !== url);
  if (!state.settings.addons.length) state.settings.addons = ["https://v3-cinemeta.strem.io/manifest.json"];
  await persist();
  render();
  refresh();
}
