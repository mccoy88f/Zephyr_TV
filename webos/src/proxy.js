/** MediaFlow and EasyProxy. A stream the TV can play stays direct. The others go through the proxy. */

import { getText } from "./stremio.js";

const XTREAM_UA = "VLC/3.0.20 LibVLC/3.0.20";
const XTREAM_LINK = /\/(live|movie|series)\/[^/]+\/[^/]+\/[^/?]+\.[a-z0-9]{2,5}(\?|$)/i;

const kinds = new Map();

export function proxyBase(input) {
  const url = String(input || "").trim().replace(/\/+$/, "");
  return /^https?:\/\//.test(url) ? url : "";
}

function headerParams(params, headers, url) {
  const h = headers || {};
  const ua = h["User-Agent"] || h["user-agent"];
  params.set("h_user-agent", ua || (XTREAM_LINK.test(url) ? XTREAM_UA : "Mozilla/5.0"));
  const referer = h.Referer || h.referer;
  if (referer) params.set("h_referer", referer);
  const origin = h.Origin || h.origin;
  if (origin) params.set("h_origin", origin);
  Object.keys(h).forEach((name) => {
    if (/^(user-agent|referer|origin)$/i.test(name)) return;
    if (h[name]) params.set("h_" + name, String(h[name]));
  });
}

/** The TV player can open this address itself. Headers, DASH and ClearKey need the proxy. */
export function needsProxy(settings, stream) {
  const base = proxyBase(settings && settings.proxyUrl);
  const url = stream && stream.url ? String(stream.url) : "";
  if (!base || !/^https?:\/\//.test(url)) return false;
  if (url === base || url.indexOf(base + "/") === 0) return false;
  const headers = stream.headers || {};
  if (Object.keys(headers).some((name) => headers[name])) return true;
  if (stream.clearkey) return true;
  return /\.mpd(\?|#|$)/i.test(url);
}

/**
 * The proxy address for one stream.
 * MediaFlow DASH asks for MPEG-TS, which the TV plays as HLS version 3.
 */
export function proxied(kind, settings, stream) {
  const base = proxyBase(settings.proxyUrl);
  const url = String(stream.url);
  const params = new URLSearchParams();
  if (settings.proxyPassword) params.set("api_password", settings.proxyPassword);
  if (kind === "easyproxy") {
    if (/\.mp4(\?|#|$)/i.test(url) && !stream.clearkey && !XTREAM_LINK.test(url)) {
      params.set("d", url);
      headerParams(params, stream.headers, url);
      return base + "/proxy/stream?" + params.toString();
    }
    params.set("url", url);
    headerParams(params, stream.headers, url);
    if (stream.clearkey) params.set("clearkey", stream.clearkey);
    return base + "/proxy/manifest.m3u8?" + params.toString();
  }
  params.set("d", url);
  headerParams(params, stream.headers, url);
  if (stream.clearkey) {
    const pairs = String(stream.clearkey).split(",").map((part) => part.split(":"));
    params.set("key_id", pairs.map((part) => part[0]).join(","));
    params.set("key", pairs.map((part) => part[1] || "").join(","));
    params.set("remux_to_ts", "true");
    return base + "/proxy/mpd/manifest.m3u8?" + params.toString();
  }
  if (/\.mpd(\?|#|$)/i.test(url)) {
    params.set("remux_to_ts", "true");
    return base + "/proxy/mpd/manifest.m3u8?" + params.toString();
  }
  if (/\.mp4(\?|#|$)/i.test(url)) return base + "/proxy/stream?" + params.toString();
  return base + "/proxy/hls/manifest.m3u8?" + params.toString();
}

/** "mediaflow" or "easyproxy". A saved type wins. Otherwise /api/info decides, and MediaFlow is the fallback. */
export async function proxyKind(settings) {
  if (settings.proxyType === "mediaflow" || settings.proxyType === "easyproxy") return settings.proxyType;
  const base = proxyBase(settings.proxyUrl);
  if (!base) return "mediaflow";
  if (kinds.has(base)) return kinds.get(base);
  let kind = "mediaflow";
  try {
    const info = new URLSearchParams();
    if (settings.proxyPassword) info.set("api_password", settings.proxyPassword);
    const body = JSON.parse(await getText(base + "/api/info?" + info.toString()));
    if (String(body.proxy || "").toLowerCase().indexOf("easyproxy") !== -1) kind = "easyproxy";
  } catch (e) { /* The add-on assumes MediaFlow when the check fails. */ }
  kinds.set(base, kind);
  return kind;
}

export function forgetProxyKind() {
  kinds.clear();
}
