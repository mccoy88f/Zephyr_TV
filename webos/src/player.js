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
    video.play().catch(() => {});
  };
  start();
}

async function playHls(video, url, startMs) {
  const mod = await import("hls.js");
  const Hls = mod.default;
  if (!Hls.isSupported()) {
    native(video, url, "application/vnd.apple.mpegurl", startMs, false);
    return;
  }
  hls = new Hls();
  hls.loadSource(url);
  hls.attachMedia(video);
  hls.on(Hls.Events.MANIFEST_PARSED, () => {
    if (startMs > 0) video.currentTime = startMs / 1000;
    video.play().catch(() => {});
  });
  hls.on(Hls.Events.ERROR, (_, data) => {
    if (data && data.fatal) video.dispatchEvent(new Event("error"));
  });
}

async function playDash(video, url, startMs) {
  await import("shaka-player/dist/shaka-player.compiled.js");
  const shaka = window.shaka;
  if (!shaka || !shaka.Player.isBrowserSupported()) throw new Error("dash");
  shaka.polyfill.installAll();
  dash = new shaka.Player(video);
  await dash.load(url, startMs > 0 ? startMs / 1000 : undefined);
  video.play().catch(() => {});
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
