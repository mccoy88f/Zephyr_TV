import assert from "node:assert/strict";
import test from "node:test";
import { needsProxy, proxied } from "../src/proxy.js";

const mediaflow = { proxyUrl: "https://proxy.example/", proxyPassword: "secret", proxyType: "mediaflow" };
const easy = { proxyUrl: "https://easy.example", proxyPassword: "", proxyType: "easyproxy" };

function params(url) {
  return new URL(url).searchParams;
}

test("a plain playlist stays off the proxy", () => {
  const stream = { url: "https://cdn.example/live.m3u8", headers: {} };
  assert.equal(needsProxy(mediaflow, stream), false);
  assert.equal(needsProxy({ proxyUrl: "" }, { url: "https://cdn.example/video.mpd" }), false);
});

test("headers and dash go through the proxy, an address already there does not", () => {
  assert.equal(needsProxy(mediaflow, {
    url: "https://cdn.example/live.m3u8",
    headers: { Referer: "https://r.example/" },
  }), true);
  assert.equal(needsProxy(mediaflow, { url: "https://cdn.example/video.mpd", headers: {} }), true);
  assert.equal(needsProxy(mediaflow, { url: "https://proxy.example/proxy/hls/manifest.m3u8?d=1" }), false);
});

test("MediaFlow turns DASH into an HLS version 3 playlist and keeps the headers", () => {
  const url = proxied("mediaflow", mediaflow, {
    url: "https://cdn.example/video.mpd?token=1",
    headers: { Referer: "https://r.example/", "User-Agent": "VLC" },
  });
  const query = params(url);
  assert.equal(new URL(url).pathname, "/proxy/mpd/manifest.m3u8");
  assert.equal(query.get("d"), "https://cdn.example/video.mpd?token=1");
  assert.equal(query.get("remux_to_ts"), "true");
  assert.equal(query.get("api_password"), "secret");
  assert.equal(query.get("h_referer"), "https://r.example/");
  assert.equal(query.get("h_user-agent"), "VLC");
});

test("an HLS stream with headers uses the HLS proxy and not the remux flag", () => {
  const url = proxied("mediaflow", mediaflow, {
    url: "https://cdn.example/live.m3u8",
    headers: { Origin: "https://r.example" },
  });
  const query = params(url);
  assert.equal(new URL(url).pathname, "/proxy/hls/manifest.m3u8");
  assert.equal(query.get("remux_to_ts"), null);
  assert.equal(query.get("h_origin"), "https://r.example");
});

test("EasyProxy sends DASH to its manifest address", () => {
  const url = proxied("easyproxy", easy, {
    url: "https://cdn.example/video.mpd",
    headers: {},
    clearkey: "kid:key",
  });
  const query = params(url);
  assert.equal(new URL(url).pathname, "/proxy/manifest.m3u8");
  assert.equal(query.get("url"), "https://cdn.example/video.mpd");
  assert.equal(query.get("clearkey"), "kid:key");
  assert.equal(query.get("api_password"), null);
});
