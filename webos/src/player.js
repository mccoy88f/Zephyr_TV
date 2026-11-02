/** Playback. The TV's own player first. Shaka only for DASH. hls.js only when the page has no HLS. */

import { onTV } from "./platform.js";

let hls = null;
let dash = null;

export function kindOf(url) {
  const clean = String(url || "").split("?")[0].toLowerCase();
  if (clean.endsWith(".m3u8")) return "hls";
  if (clean.endsWith(".mpd")) return "dash";
  if (/\.(mp3|m4a|aac|ogg|opus)$/.test(clean)) return "audio";
  return "file";
}

export function stop(video) {
  if (hls) {
    hls.destroy();
    hls = null;
  }
  if (dash) {
    dash.destroy();
    dash = null;
  }
  if (!video) return;
  video.onerror = null;
  video.pause();
  while (video.firstChild) video.removeChild(video.firstChild);
  video.removeAttribute("src");
  video.load();
}

function mediaType(mime, startMs, audioOnly) {
  const options = {
    option: {
      transmission: { playTime: { start: Math.max(0, startMs || 0) } },
      adaptiveStreaming: { audioOnly: !!audioOnly, adaptiveResolution: true, maxHeight: 1080 },
    },
  };
  return mime + ";mediaOption=" + encodeURIComponent(JSON.stringify(options));
}

function chromeMajor() {
  const match = /Chrome\/(\d+)/.exec(navigator.userAgent || "");
  return match ? Number(match[1]) : 100;
}

function playPromise(video) {
  const played = video.play();
  if (played && played.catch) played.catch(() => {});
}

const loading = {};

function loadScript(src) {
  if (loading[src]) return loading[src];
  loading[src] = new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = src;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error("script"));
    document.head.appendChild(script);
  });
  return loading[src];
}

function native(video, url, mime, startMs, audioOnly) {
  const source = document.createElement("source");
  source.src = url;
  if (onTV()) source.type = mediaType(mime, startMs, audioOnly);
  video.appendChild(source);
  const start = () => {
    if (startMs > 0 && !onTV()) {
      const seek = () => {
        video.currentTime = startMs / 1000;
        video.removeEventListener("loadedmetadata", seek);
      };
      video.addEventListener("loadedmetadata", seek);
    }
    playPromise(video);
  };
  start();
}

async function playHls(video, url, startMs) {
  if (chromeMajor() < 47) {
    native(video, url, "application/vnd.apple.mpegurl", startMs, false);
    return;
  }
  await loadScript("hls.js");
  const Hls = window.Hls;
  if (!Hls || !Hls.isSupported()) {
    native(video, url, "application/vnd.apple.mpegurl", startMs, false);
    return;
  }
  hls = new Hls();
  hls.loadSource(url);
  hls.attachMedia(video);
  hls.on(Hls.Events.MANIFEST_PARSED, () => {
    if (startMs > 0) video.currentTime = startMs / 1000;
    playPromise(video);
  });
  hls.on(Hls.Events.ERROR, (_, data) => {
    if (data && data.fatal) video.dispatchEvent(new Event("error"));
  });
}

async function playDash(video, url, startMs) {
  // Shaka 4.3 needs Chromium 68 (webOS 5). Earlier TVs play DASH only through the proxy.
  if (chromeMajor() < 68 || typeof MediaSource === "undefined") throw new Error("dash");
  await loadScript("shaka-player.compiled.js");
  const shaka = window.shaka;
  if (!shaka || !shaka.Player.isBrowserSupported()) throw new Error("dash");
  shaka.polyfill.installAll();
  dash = new shaka.Player(video);
  await dash.load(url, startMs > 0 ? startMs / 1000 : undefined);
  playPromise(video);
}

/**
 * Play one stream into [video]. Resolves when playback has been asked.
 * Rejects when this engine cannot start it. The caller tries the next stream.
 */
export async function start(video, stream, startMs) {
  stop(video);
  const url = stream.url;
  const kind = kindOf(url);
  const audioOnly = !!(stream.audioOnly || kind === "audio");
  if (kind === "dash") {
    await playDash(video, url, startMs || 0);
    return;
  }
  const nativeHls = onTV() || !!video.canPlayType("application/vnd.apple.mpegurl");
  if (kind === "hls" && !nativeHls) {
    await playHls(video, url, startMs || 0);
    return;
  }
  const mime = kind === "hls" ? "application/vnd.apple.mpegurl" : audioOnly ? "audio/mp4" : "video/mp4";
  native(video, url, mime, startMs || 0, audioOnly);
}
