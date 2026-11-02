import assert from "node:assert/strict";
import test from "node:test";
import { kindOf } from "../src/player.js";
import { backdropStyle, posterHtml } from "../src/view.js";
import {
  catalogUrl, manifestUrl, parseAddon, parseStreams, provides, streamUrl,
} from "../src/stremio.js";
import { inNight } from "../src/time.js";

test("manifest address accepts a host and a stremio link", () => {
  assert.equal(manifestUrl("https://v3-cinemeta.strem.io"), "https://v3-cinemeta.strem.io/manifest.json");
  assert.equal(manifestUrl("stremio://v3-cinemeta.strem.io/manifest.json"), "https://v3-cinemeta.strem.io/manifest.json");
  assert.equal(manifestUrl("not a url"), null);
});

test("catalog and stream paths encode the id", () => {
  assert.equal(
    catalogUrl("https://addon.example", "movie", "top", "search=" + encodeURIComponent("a b")),
    "https://addon.example/catalog/movie/top/search=a%20b.json",
  );
  assert.equal(streamUrl("https://addon.example", "series", "tt1:1:2"), "https://addon.example/stream/series/tt1%3A1%3A2.json");
});

test("a manifest splits films from channels", () => {
  const addon = parseAddon("https://addon.example/manifest.json", {
    id: "org.example",
    name: "Example",
    resources: ["catalog", { name: "stream", types: ["movie", "series"], idPrefixes: ["tt"] }],
    catalogs: [
      { type: "movie", id: "top", name: "Top" },
      { type: "tv", id: "live", name: "Live", extra: [{ name: "genre", options: ["News", "⚙️"] }] },
      { type: "movie", id: "find", name: "Find", extra: [{ name: "search", isRequired: true }] },
    ],
  });
  assert.equal(addon.vod.length, 2);
  assert.equal(addon.vod[1].listed, false);
  assert.equal(addon.catalogs.length, 1);
  assert.deepEqual(addon.catalogs[0].genres, ["News"]);
  assert.equal(provides(addon, "stream", "movie", "tt9"), true);
  assert.equal(provides(addon, "stream", "tv", "1"), false);
});

test("streams keep http and drop torrents and udp", () => {
  const streams = parseStreams({
    streams: [
      { url: "https://cdn.example/a.m3u8", name: "720p" },
      { url: "udp://239.0.0.1:1234", name: "udp" },
      { infoHash: "abc", name: "torrent" },
      { url: "rtsp://cam.example/live", name: "cam" },
    ],
  });
  assert.equal(streams.length, 1);
  assert.equal(streams[0].url, "https://cdn.example/a.m3u8");
});

test("night wraps past midnight", () => {
  const late = new Date(2026, 10, 2, 23, 30);
  const early = new Date(2026, 10, 2, 5, 0);
  const noon = new Date(2026, 10, 2, 12, 0);
  assert.equal(inNight(late, 23 * 60, 6 * 60, true), true);
  assert.equal(inNight(early, 23 * 60, 6 * 60, true), true);
  assert.equal(inNight(noon, 23 * 60, 6 * 60, true), false);
  assert.equal(inNight(late, 23 * 60, 6 * 60, false), false);
});

test("a poster address stays inside the img and a backdrop stays inside style", () => {
  const html = posterHtml("https://images.example/a.png?x=1&y=2");
  assert.match(html, /^<img class="poster" alt="" src="https:\/\/images\.example\/a\.png\?x=1&amp;y=2">$/);
  assert.equal(posterHtml("javascript:alert(1)"), '<span class="poster"></span>');
  const style = backdropStyle("https://images.example/a.png");
  assert.equal(style.includes('"'), false);
  assert.equal(style, "background-image:url('https://images.example/a.png')");
});

test("a playlist address is hls and a manifest is dash", () => {
  assert.equal(kindOf("https://cdn.example/live.m3u8?token=1"), "hls");
  assert.equal(kindOf("https://cdn.example/video.mpd"), "dash");
  assert.equal(kindOf("https://cdn.example/a.mp4"), "file");
});
