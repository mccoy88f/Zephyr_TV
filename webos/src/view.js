/** Screen markup. Every value that comes from the network is escaped. */

import { t, themeName } from "./i18n.js";
import { formatClock, hhmm } from "./time.js";

export function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
}

function httpUrl(url) {
  return /^https?:\/\//.test(url || "") ? String(url) : "";
}

/** A backdrop rule that can sit inside a double-quoted style attribute. */
export function backdropStyle(url) {
  const safe = httpUrl(url).replace(/['"\\()]/g, "");
  if (!safe) return "";
  return "background-image:url('" + safe + "')";
}

export function posterHtml(url, big) {
  const cls = "poster" + (big ? " big" : "");
  const src = httpUrl(url);
  if (!src) return '<span class="' + cls + '"></span>';
  return '<img class="' + cls + '" alt="" src="' + esc(src) + '">';
}

function btn(row, fid, act, label, extra) {
  const attrs = extra || "";
  return '<button class="tile" data-row="' + esc(row) + '" data-fid="' + esc(fid) + '" data-act="' + esc(act) + '" ' + attrs + ">" +
    '<span class="tile-label">' + esc(label) + "</span></button>";
}

function card(row, fid, act, item, attrs) {
  const art = item.poster || item.background || item.logo || "";
  return '<button class="card" data-row="' + esc(row) + '" data-fid="' + esc(fid) + '" data-act="' + esc(act) + '" ' + (attrs || "") + ">" +
    posterHtml(art) +
    '<span class="label">' + esc(item.name || item.title) + "</span>" +
    (item.now ? '<span class="now">' + esc(item.now) + "</span>" : "") + "</button>";
}

export function homeHtml(state) {
  const lang = state.settings.lang;
  const s = state.settings;
  const clock = formatClock(new Date(), s.clock);
  const wx = state.weather ? esc(state.weather.name) + " " + esc(state.weather.temp) + "°" : "";
  const fav = [
    btn("fav", "search", "search", t(lang, "search")),
    btn("fav", "settings", "settings", t(lang, "settings")),
    btn("fav", "films", "goto", t(lang, "films"), 'data-target="films"'),
    btn("fav", "series", "goto", t(lang, "series"), 'data-target="series"'),
  ];
  state.apps.slice(0, 24).forEach((app) => {
    fav.push(btn("fav", "app:" + app.id, "launch", app.title, 'data-app="' + esc(app.id) + '"'));
  });
  let body = '<header class="top"><div class="brand">' + esc(t(lang, "app")) + "</div>" +
    '<div class="top-right"><span id="weather">' + wx + '</span><span id="clock">' + esc(clock) + "</span></div></header>";
  if (state.notice) body += '<p class="notice">' + esc(state.notice) + "</p>";
  if (state.spotlight.length && !state.night) body += spotlightHtml(state);
  body += '<section class="shelf"><h2>' + esc(t(lang, "favourites")) + '</h2><div class="scroller">' + fav.join("") + "</div></section>";
  if (state.history.length) {
    const cards = state.history.map((item) => card("continue", "h:" + item.key, "resume", item, 'data-key="' + esc(item.key) + '"'));
    body += '<section class="shelf"><h2>' + esc(t(lang, "continue")) + '</h2><div class="scroller">' + cards.join("") + "</div></section>";
  }
  state.rows.forEach((row) => {
    const shared = state.rows.filter((other) => other.title === row.title).length > 1;
    const kind = row.cat.type === "series" ? t(lang, "series") : row.cat.type === "movie" ? t(lang, "films") : t(lang, "channels");
    const title = shared ? row.title + " · " + kind : row.title;
    const cards = row.metas.map((item) => {
      const live = row.cat.type === "tv";
      const act = live ? "live" : "open";
      return card(row.id, row.id + ":" + item.id, act, item,
        'data-type="' + esc(item.type) + '" data-id="' + esc(item.id) + '" data-base="' + esc(row.addon.base) + '" data-rowid="' + esc(row.id) + '"');
    });
    body += '<section class="shelf" id="row-' + esc(row.id) + '"><h2>' + esc(title) + '</h2><div class="scroller">' + cards.join("") + "</div></section>";
  });
  if (!state.rows.length && !state.notice) body += '<p class="notice">' + esc(t(lang, "empty")) + "</p>";
  return body;
}

export function spotlightHtml(state) {
  const item = state.spotlight[state.spot] || state.spotlight[0];
  if (!item) return "";
  const lang = state.settings.lang;
  return '<section class="spot" id="spot" style="' + backdropStyle(item.backdrop || item.poster) + '">' +
    '<div class="spot-copy"><p class="kicker">' + esc(t(lang, "spotlight")) + "</p><h1>" + esc(item.name) + "</h1>" +
    '<p class="overview">' + esc(item.overview || "") + "</p>" +
    '<button class="tile" data-row="spot" data-fid="spot-play" data-act="spot" data-index="' + state.spot + '">' +
    esc(t(lang, "watch")) + "</button></div></section>";
}

export function detailHtml(state) {
  const lang = state.settings.lang;
  const d = state.detail;
  let body = '<header class="top"><button class="tile" data-row="detail-top" data-fid="detail-back" data-act="back">' +
    esc(t(lang, "back")) + "</button><div class=\"brand\">" + esc(d.name) + "</div></header>";
  body += '<div class="detail">' + posterHtml(d.poster || d.background, true) + "<div>";
  body += '<p>' + esc(d.description || "") + "</p>";
  body += '<button class="tile" data-row="detail" data-fid="watch" data-act="watch">' + esc(t(lang, "watch")) + "</button>";
  if (d.episodes && d.episodes.length) {
    body += "<h2>" + esc(t(lang, "episodes")) + '</h2><div class="scroller">';
    d.episodes.forEach((ep) => {
      const label = ep.season + "x" + ep.episode + " " + (ep.name || "");
      body += card("eps", "ep:" + ep.id, "episode", { name: label, poster: ep.thumbnail }, 'data-id="' + esc(ep.id) + '"');
    });
    body += "</div>";
  }
  if (state.streams.length) {
    body += "<h2>" + esc(t(lang, "streams")) + '</h2><div class="list">';
    state.streams.forEach((s, i) => {
      body += '<button class="rowbtn" data-row="streams" data-fid="st:' + i + '" data-act="stream" data-index="' + i + '">' +
        esc(s.title || s.url) + "</button>";
    });
    body += "</div>";
  }
  body += "</div></div>";
  if (state.notice) body += '<p class="notice">' + esc(state.notice) + "</p>";
  return body;
}

export function playerHtml(state) {
  const lang = state.settings.lang;
  const title = state.playItem ? state.playItem.name : "";
  const stream = state.streams[state.streamAt];
  return '<div class="player"><video id="video"></video><div class="banner" id="banner">' +
    '<button class="tile" data-row="player" data-fid="player-back" data-act="back">' + esc(t(lang, "back")) + "</button>" +
    "<div><strong>" + esc(title) + "</strong><p id=\"stream-label\">" + esc(stream ? stream.title : "") + "</p></div>" +
    '<button class="tile" data-row="player" data-fid="player-pause" data-act="pause">' + esc(t(lang, "pause")) + "</button>" +
    '<button class="tile" data-row="player" data-fid="player-next" data-act="next-stream">' + esc(t(lang, "next")) + "</button>" +
    "</div></div>";
}

export function searchHtml(state) {
  const lang = state.settings.lang;
  let body = '<header class="top"><button class="tile" data-row="search-top" data-fid="search-back" data-act="back">' +
    esc(t(lang, "back")) + '</button><input id="q" data-row="search-top" data-fid="q" value="' + esc(state.query) + '" placeholder="' +
    esc(t(lang, "typeHere")) + '"></header>';
  body += '<button class="tile" data-row="search-go" data-fid="search-go" data-act="run-search">' + esc(t(lang, "search")) + "</button>";
  if (state.notice) body += '<p class="notice">' + esc(state.notice) + "</p>";
  const cards = state.results.map((item, i) => card("results", "r:" + i, item.act || "open", item,
    'data-type="' + esc(item.type || "") + '" data-id="' + esc(item.id || "") + '" data-base="' + esc(item.base || "") + '" data-index="' + i + '"'));
  body += '<div class="scroller wrap">' + cards.join("") + "</div>";
  return body;
}

export function settingsHtml(state) {
  const lang = state.settings.lang;
  const s = state.settings;
  const line = (fid, act, label, value, extra) =>
    '<button class="rowbtn" data-row="settings" data-fid="' + fid + '" data-act="' + act + '" ' + (extra || "") + ">" +
    "<span>" + esc(label) + "</span><span>" + esc(value) + "</span></button>";
  let body = '<header class="top"><button class="tile" data-row="set-top" data-fid="set-back" data-act="back">' +
    esc(t(lang, "back")) + "</button><div class=\"brand\">" + esc(t(lang, "settings")) + "</div></header><div class=\"list\">";
  body += line("theme", "cycle-theme", t(lang, "theme"), themeName(lang, s.theme));
  body += line("clock", "cycle-clock", t(lang, "clock"), s.clock === "12" ? "12" : s.clock === "24" ? "24" : t(lang, "clockAuto"));
  body += line("night", "toggle", t(lang, "night"), s.night ? t(lang, "on") : t(lang, "off"), 'data-key="night"');
  body += line("night-from", "edit", t(lang, "nightFrom"), hhmm(s.nightFrom), 'data-key="nightFrom"');
  body += line("night-to", "edit", t(lang, "nightTo"), hhmm(s.nightTo), 'data-key="nightTo"');
  body += line("weather", "toggle", t(lang, "weather"), s.weather ? t(lang, "on") : t(lang, "off"), 'data-key="weather"');
  body += line("lang", "cycle-lang", t(lang, "language"), s.lang);
  body += line("tmdb", "edit", t(lang, "tmdb"), s.tmdbKey ? "••••" : "", 'data-key="tmdbKey"');
  body += line("adult", "toggle", t(lang, "adult"), s.adult ? t(lang, "on") : t(lang, "off"), 'data-key="adult"');
  body += line("epg", "edit", t(lang, "epg"), s.epgUrl, 'data-key="epgUrl"');
  body += "<h2>" + esc(t(lang, "users")) + "</h2>";
  s.users.forEach((u) => {
    const mark = u.id === s.user ? " · " : "";
    const label = u.name || (u.id === "default" ? t(lang, "you") : u.id);
    body += line("user:" + u.id, "user", label + mark, t(lang, "useUser"), 'data-user="' + esc(u.id) + '"');
  });
  body += line("add-user", "edit", t(lang, "addUser"), "", 'data-key="newUser"');
  body += "<h2>" + esc(t(lang, "addons")) + "</h2>";
  s.addons.forEach((url) => {
    body += line("addon:" + url, "remove-addon", url, t(lang, "remove"), 'data-url="' + esc(url) + '"');
  });
  body += line("add-addon", "edit", t(lang, "addAddon"), "", 'data-key="addon"');
  body += "<h2>" + esc(t(lang, "about")) + "</h2>";
  body += '<p class="about">' + esc(t(lang, "floor")) + ". " + esc(state.sdk || "") + "</p>";
  body += '<p class="about">' + esc(t(lang, "excluded")) + "</p>";
  if (state.problems.length) {
    body += "<h2>" + esc(t(lang, "problems")) + "</h2>";
    state.problems.slice(0, 8).forEach((p) => { body += '<p class="about">' + esc(p.text) + "</p>"; });
  }
  body += "</div>";
  return body;
}

export function editHtml(state) {
  const lang = state.settings.lang;
  return '<header class="top"><button class="tile" data-row="edit-top" data-fid="edit-back" data-act="back">' +
    esc(t(lang, "back")) + "</button><div class=\"brand\">" + esc(state.edit.label) + "</div></header>" +
    '<input id="editor" data-row="edit" data-fid="editor" value="' + esc(state.edit.value) + '">' +
    '<button class="tile" data-row="edit" data-fid="edit-save" data-act="save-edit">' + esc(t(lang, "save")) + "</button>";
}

export function screenHtml(state) {
  if (state.screen === "detail" && state.detail) return detailHtml(state);
  if (state.screen === "player") return playerHtml(state);
  if (state.screen === "search") return searchHtml(state);
  if (state.screen === "settings") return settingsHtml(state);
  if (state.screen === "edit" && state.edit) return editHtml(state);
  return homeHtml(state);
}
