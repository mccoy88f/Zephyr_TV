/** Stremio add-on protocol, the part a webOS app can ask over HTTP. */

export const CINEMETA = "https://v3-cinemeta.strem.io/manifest.json";

const SKIP = ["rtsp://", "rtmp://", "rtmps://", "udp://", "rtp://"];

export function enc(s) {
  return encodeURIComponent(s);
}

export function manifestUrl(input) {
  let url = String(input || "").split("\n").map((s) => s.trim()).find(Boolean) || "";
  if (url.startsWith("stremio://")) url = "https://" + url.slice("stremio://".length);
  if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
  if (!url.slice(url.indexOf("://") + 3).split("/")[0]) return null;
  url = url.replace(/\/$/, "");
  return url.endsWith("/manifest.json") ? url : url + "/manifest.json";
}

export function addonBase(url) {
  return String(url || "").replace(/\/manifest\.json$/, "");
}

export function isVodType(type) {
  return !!type && type !== "tv" && type !== "channel";
}

function strings(list) {
  if (!Array.isArray(list)) return [];
  return list.map((s) => String(s || "")).filter(Boolean);
}

export function parseResources(manifest) {
  const types = strings(manifest && manifest.types);
  const prefixes = strings(manifest && manifest.idPrefixes);
  const list = (manifest && manifest.resources) || [];
  return list.map((r) => {
    if (typeof r === "string") return { name: r, types, prefixes: prefixes.length ? prefixes : null };
    if (!r || !r.name) return null;
    const ownTypes = Array.isArray(r.types) ? strings(r.types) : types;
    const ownPrefixes = Array.isArray(r.idPrefixes) ? strings(r.idPrefixes) : null;
    return { name: String(r.name), types: ownTypes, prefixes: ownPrefixes && ownPrefixes.length ? ownPrefixes : null };
  }).filter(Boolean);
}

export function provides(addon, resource, type, id) {
  return (addon.resources || []).some((r) =>
    r.name === resource && r.types.indexOf(type) !== -1 &&
    (id == null || r.prefixes == null || r.prefixes.some((p) => String(id).indexOf(p) === 0)));
}

function catalogOf(c, manifest) {
  const type = String(c.type || "");
  const id = String(c.id || "");
  if (!type || !id) return null;
  const extras = Array.isArray(c.extra) ? c.extra : [];
  const search = extras.some((e) => e && e.name === "search");
  const genres = ((extras.find((e) => e && e.name === "genre") || {}).options || [])
    .map((g) => String(g))
    .filter((g) => /[\p{L}\p{N}]/u.test(g));
  const required = extras.filter((e) => e && e.isRequired);
  const name = c.name || (manifest && manifest.name) || id;
  return { type, id, name, genres, search, required };
}

export function parseAddon(url, manifest) {
  const m = manifest || {};
  const catalogs = [];
  const vod = [];
  (m.catalogs || []).forEach((c) => {
    const cat = catalogOf(c, m);
    if (!cat) return;
    if (cat.type === "tv") {
      const blocked = cat.required.some((e) => e.name !== "genre");
      if (!blocked) catalogs.push(cat);
      return;
    }
    if (!isVodType(cat.type)) return;
    const open = cat.required.length === 0;
    if (open || cat.search) vod.push(Object.assign({}, cat, { listed: open }));
  });
  return {
    url,
    base: addonBase(url),
    id: m.id || url,
    name: m.name || url,
    logo: m.logo || null,
    adult: !!(m.behaviorHints && m.behaviorHints.adult),
    resources: parseResources(m),
    catalogs,
    vod,
  };
}

export function catalogUrl(base, type, id, extra) {
  const path = base + "/catalog/" + enc(type) + "/" + enc(id);
  return path + (extra ? "/" + extra : "") + ".json";
}

export function metaUrl(base, type, id) {
  return base + "/meta/" + enc(type) + "/" + enc(id) + ".json";
}

export function streamUrl(base, type, id) {
  return base + "/stream/" + enc(type) + "/" + enc(id) + ".json";
}

function text(obj, key) {
  const v = obj && obj[key];
  if (v == null) return null;
  const s = String(v).trim();
  return s || null;
}

export function parseMetas(page) {
  const list = (page && page.metas) || [];
  return list.map((m) => {
    if (!m || !m.id || !m.name) return null;
    const type = m.type || "video";
    return {
      type,
      id: String(m.id),
      name: String(m.name).trim(),
      poster: text(m, "poster"),
      background: text(m, "background"),
      logo: text(m, "logo"),
      description: text(m, "description"),
      year: text(m, "releaseInfo") || text(m, "year"),
      genres: Array.isArray(m.genres) ? m.genres.map(String) : [],
    };
  }).filter(Boolean);
}

export function parseMeta(page, fallbackType) {
  const m = page && page.meta;
  if (!m || !m.id || !m.name) return null;
  const type = m.type || fallbackType || "video";
  const videos = Array.isArray(m.videos) ? m.videos : [];
  const episodes = videos.map((v, index) => {
    if (!v || !v.id) return null;
    const season = v.season == null ? (type === "series" ? -1 : 1) : Number(v.season);
    if (!(season >= 0)) return null;
    return {
      id: String(v.id),
      season,
      episode: v.episode == null ? (v.number == null ? index + 1 : Number(v.number)) : Number(v.episode),
      name: text(v, "title") || text(v, "name") || "",
      thumbnail: text(v, "thumbnail"),
      overview: text(v, "overview") || text(v, "description"),
    };
  }).filter(Boolean).sort((a, b) => (a.season - b.season) || (a.episode - b.episode));
  return {
    type,
    id: String(m.id),
    name: String(m.name).trim(),
    poster: text(m, "poster"),
    background: text(m, "background"),
    description: text(m, "description"),
    year: text(m, "releaseInfo"),
    genres: Array.isArray(m.genres) ? m.genres.map(String) : [],
    episodes: type === "movie" ? [] : episodes,
  };
}

export function parseStreams(page) {
  const list = (page && page.streams) || [];
  return list.map((s) => {
    if (!s) return null;
    if (s.infoHash && !s.url) return null;
    const url = text(s, "url");
    if (!url) return null;
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
    if (SKIP.some((p) => url.toLowerCase().indexOf(p) === 0)) return null;
    const hints = s.behaviorHints || {};
    const headers = (hints.proxyHeaders && hints.proxyHeaders.request) || {};
    const name = text(s, "name") || "";
    const described = text(s, "title") || text(s, "description") || "";
    const title = [name, described.split("\n")[0]].filter(Boolean).filter((v, i, a) => a.indexOf(v) === i).join(" · ");
    const clearkey = text(hints, "clearkey") || text(s, "clearkey");
    return {
      url,
      title,
      headers,
      clearkey,
      group: hints.bingeGroup || null,
      audioOnly: hints.audioOnly === true || /\.(mp3|m4a|aac|ogg|opus)(\?|$)/i.test(url),
    };
  }).filter(Boolean);
}

export async function getJson(url, ms) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), ms || 15000);
  try {
    const res = await fetch(url, { signal: ctrl.signal });
    if (!res.ok) throw new Error(String(res.status));
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}
