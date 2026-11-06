/*
 * Zephyr TV Receiver: a TV that only plays what Zephyr TV sends it (Send to, in its player). One web app for two
 * TVs: LG's webOS, packaged as an .ipk with its service (webos/), and Hisense's VIDAA, which opens it from the site
 * (ztv.mccoy88f.link/receiver/index.html) as an app of its own. Linked once to the
 * Zephyr TV account through the site (a code, or its QR code, entered on a phone), it waits for what the account's
 * other devices send it, says it for five seconds (Watch now, Cancel), then plays it in a player that looks like
 * Zephyr TV's, from where it got to there. What differs between the TVs is in platform.js, the words in i18n.js;
 * this is the same on every one. ES5: webOS 3 runs an old Chromium.
 */
(function () {
  "use strict";

  // Served by the site (VIDAA): its own address; packaged (webOS: a file on the TV): the site's.
  var SITE = /^https?:$/.test(location.protocol) ? location.protocol + "//" + location.host : "https://ztv.mccoy88f.link";
  var P = window.Platform; // what differs from one TV to the other (see platform.js)
  var OFFER_SECONDS = 5;
  var BANNER_MS = 5000;

  // ---------- words (see i18n.js) ----------

  var t = window.t;
  function $(id) { return document.getElementById(id); }
  function show(id) {
    ["link", "idle", "player"].forEach(function (s) { $(s).classList.toggle("hidden", s !== id); });
  }
  Array.prototype.forEach.call(document.querySelectorAll("[data-t]"), function (e) { e.textContent = t(e.getAttribute("data-t")); });

  // ---------- storage ----------

  function load(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
  function save(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch (e) { /* none */ } }

  // ---------- the site ----------

  /** A call to the site: [cb](status, body as JSON or null). [timeout] in ms. */
  function call(method, path, body, cb, timeout) {
    var x = new XMLHttpRequest();
    x.open(method, SITE + path, true);
    x.timeout = timeout || 15000;
    var token = load("token");
    if (token) x.setRequestHeader("Authorization", "Bearer " + token);
    if (body) x.setRequestHeader("Content-Type", "application/json");
    x.onload = function () {
      var json = null;
      try { json = x.responseText ? JSON.parse(x.responseText) : null; } catch (e) { json = null; }
      cb(x.status, json);
    };
    x.onerror = x.ontimeout = function () { cb(0, null); };
    x.send(body ? JSON.stringify(body) : null);
  }

  P.start();

  // ---------- linking ----------

  var linkTimer = null;
  function startLink() {
    show("link");
    clearTimeout(linkTimer);
    $("linkState").textContent = "";
    call("POST", "/api/v1/link/start", { name: P.tv, model: P.model, version: P.version }, function (status, o) {
      if (status !== 200 || !o) {
        $("linkState").textContent = t("linkFail");
        linkTimer = setTimeout(startLink, 10000);
        return;
      }
      $("linkUrl").textContent = o.verification_url.replace(/^https?:\/\//, "");
      $("linkCode").textContent = o.user_code;
      $("qr").src = SITE + "/api/v1/qr?d=" + encodeURIComponent(o.verification_url_complete);
      $("linkState").textContent = t("linkWait");
      var every = Math.max(2, Math.min(30, o.interval || 5)) * 1000;
      var until = Date.now() + (o.expires_in || 600) * 1000;
      function poll() {
        if (Date.now() > until) { $("linkState").textContent = t("linkGone"); linkTimer = setTimeout(startLink, 3000); return; }
        call("POST", "/api/v1/link/poll", { device_code: o.device_code }, function (s, r) {
          if (s === 200 && r && r.token) {
            save("token", r.token);
            save("email", r.email || "");
            return ready();
          }
          if (s === 410) { $("linkState").textContent = t("linkDenied"); linkTimer = setTimeout(startLink, 3000); return; }
          if (s === 404) { $("linkState").textContent = t("linkGone"); linkTimer = setTimeout(startLink, 3000); return; }
          linkTimer = setTimeout(poll, every);
        });
      }
      linkTimer = setTimeout(poll, every);
    });
  }

  // ---------- waiting for what's sent ----------

  var waiting = false;
  function ready() {
    if (!playing) show("idle");
    var email = load("email");
    $("account").textContent = email ? t("account", email) : "";
    $("idleNote").textContent = t("readyNote");
    if (!waiting) waitLoop();
  }

  /** One wait after the other (the site answers each within 25 s): what comes is offered; unlinked, back to linking. */
  function waitLoop() {
    waiting = true;
    call("GET", "/api/v1/handoffs/wait", null, function (status, o) {
      if (status === 401) {
        waiting = false;
        save("token", null);
        return startLink();
      }
      if (status === 200 && o && o.handoffs && o.handoffs.length) offer(o.handoffs[o.handoffs.length - 1]);
      setTimeout(waitLoop, status === 0 || status >= 500 ? 15000 : 0);
    }, 40000);
  }

  // ---------- Watch now / Cancel, five seconds ----------

  var offerTimer = null;
  var offering = null;
  var offerFocus = 0;
  function clockOf(s) {
    s = Math.max(0, Math.floor(s));
    var h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, ss = s % 60;
    return (h ? h + ":" + (m < 10 ? "0" : "") : "") + m + ":" + (ss < 10 ? "0" : "") + ss;
  }
  function offer(h) {
    offering = h;
    var w = h.data || {};
    var left = OFFER_SECONDS;
    $("offerFrom").textContent = t("from", h.from || t("anotherDevice"));
    $("offerWhat").textContent = w.name || "";
    $("offerAt").textContent = w.position > 0 ? t("fromAt", clockOf(w.position / 1000)) : "";
    offerFocus = 0;
    function draw() {
      $("offerWatch").textContent = t("watch", left);
      $("offerWatch").classList.toggle("focus", offerFocus === 0);
      $("offerCancel").classList.toggle("focus", offerFocus === 1);
    }
    draw();
    $("offer").classList.remove("hidden");
    clearInterval(offerTimer);
    offerTimer = setInterval(function () {
      if (--left <= 0) return accept();
      draw();
    }, 1000);
    offer.draw = draw;
  }
  function closeOffer() {
    clearInterval(offerTimer);
    $("offer").classList.add("hidden");
    var h = offering;
    offering = null;
    return h;
  }
  function accept() { var h = closeOffer(); if (h) play(h.data || {}); }

  // ---------- the player ----------

  var video = $("video");
  var playing = null;
  var bannerTimer = null;

  function status(text) { $("loadStatus").textContent = text; }
  function setPoster(url) {
    ["poster", "loadPoster"].forEach(function (id) {
      var img = $(id);
      if (url) { img.src = url; img.style.visibility = "visible"; } else { img.removeAttribute("src"); img.style.visibility = "hidden"; }
    });
  }

  /** Where [w]'s stream plays from: straight, or through the platform when it needs its headers (DASH: straight). */
  function address(s, done) {
    var headers = s.headers || {};
    var needs = Object.keys(headers).length > 0;
    if (!needs || /\.mpd(\?|$)/i.test(s.url)) return done(s.url);
    P.proxy(s.url, headers, done);
  }

  function play(w) {
    stop(true);
    var s = w.stream;
    playing = w;
    show("player");
    $("loading").classList.remove("hidden");
    $("loadName").textContent = w.name || "";
    $("title").textContent = w.name || "";
    $("source").textContent = (s && s.title ? s.title : "") + (s && s.source ? " · " + s.source : "");
    setPoster(w.poster);
    status(t("loading"));
    if (!s || !s.url || /^magnet:/i.test(s.url)) return fail(s && /^magnet:/i.test(s.url) ? t("torrent") : t("cantPlay"));
    if (!/^https?:/i.test(s.url)) return fail(t("cantPlay"));
    address(s, function (src) {
      if (playing !== w) return;
      video.src = src;
      var at = (w.position || 0) / 1000;
      video.onloadedmetadata = function () {
        if (at > 5 && isFinite(video.duration) && at < video.duration - 5) video.currentTime = at;
      };
      video.onplaying = function () { $("loading").classList.add("hidden"); showBanner(); };
      video.onerror = function () { fail(t("failed")); };
      video.onended = function () { stop(); };
      video.play();
    });
  }

  function fail(text) {
    status(text);
    $("loading").classList.remove("hidden");
    var w = playing;
    setTimeout(function () { if (playing === w) stop(); }, 5000);
  }

  /** Back to waiting, the stream let go of. [quiet]: another one comes at once. */
  function stop(quiet) {
    video.onloadedmetadata = video.onplaying = video.onerror = video.onended = null;
    video.pause();
    video.removeAttribute("src");
    video.load();
    playing = null;
    $("banner").classList.add("hidden");
    if (!quiet) ready();
  }

  function tick() {
    var d = video.duration;
    var p = video.currentTime;
    $("position").textContent = isFinite(d) && d > 0 ? clockOf(p) + " / " + clockOf(d) : clockOf(p);
    $("progress").style.width = isFinite(d) && d > 0 ? (p / d * 100) + "%" : "0";
    var now = new Date();
    $("clock").textContent = (now.getHours() < 10 ? "0" : "") + now.getHours() + ":" + (now.getMinutes() < 10 ? "0" : "") + now.getMinutes();
    $("state").classList.toggle("hidden", !video.paused);
  }
  setInterval(function () { if (playing && !$("banner").classList.contains("hidden")) tick(); }, 1000);

  function showBanner(stay) {
    tick();
    $("banner").classList.remove("hidden");
    clearTimeout(bannerTimer);
    if (!stay) bannerTimer = setTimeout(function () { if (!video.paused) $("banner").classList.add("hidden"); }, BANNER_MS);
  }

  var hintTimer = null;
  function hint(text) {
    $("hint").textContent = text;
    $("hint").classList.remove("hidden");
    clearTimeout(hintTimer);
    hintTimer = setTimeout(function () { $("hint").classList.add("hidden"); }, 900);
  }

  function seek(by) {
    if (!playing || !isFinite(video.duration)) return;
    video.currentTime = Math.max(0, Math.min(video.duration - 1, video.currentTime + by));
    hint((by > 0 ? "+" : "") + by + " s");
    showBanner();
  }

  function setPaused(paused) {
    if (!playing) return;
    if (paused) video.pause(); else video.play();
    showBanner(paused);
  }

  // ---------- the remote ----------

  var KEY = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13, BACK: 461, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412, ESC: 27, BKSP: 8, PLAYPAUSE: 179 };
  document.addEventListener("keydown", function (e) {
    var k = e.keyCode;
    if (offering) {
      if (k === KEY.UP || k === KEY.DOWN) { offerFocus = 1 - offerFocus; offer.draw(); }
      else if (k === KEY.OK) { if (offerFocus === 0) accept(); else closeOffer(); }
      else if (k === KEY.BACK || k === KEY.ESC || k === KEY.BKSP) closeOffer();
      e.preventDefault();
      return;
    }
    if (!playing) {
      // Waiting: Back leaves the app, as a TV app does.
      // VIDAA's Back is Backspace (8); its apps leave with window.close().
      if (k === KEY.BACK || k === KEY.BKSP || k === KEY.ESC) P.exit();
      return;
    }
    if (k === KEY.OK || k === KEY.PLAYPAUSE) setPaused(!video.paused);
    else if (k === KEY.PLAY) setPaused(false);
    else if (k === KEY.PAUSE) setPaused(true);
    else if (k === KEY.LEFT || k === KEY.RW) seek(-10);
    else if (k === KEY.RIGHT || k === KEY.FF) seek(10);
    else if (k === KEY.UP || k === KEY.DOWN) showBanner();
    else if (k === KEY.BACK || k === KEY.STOP || k === KEY.ESC || k === KEY.BKSP) stop();
    else return;
    e.preventDefault();
  });

  // ---------- the screen ----------

  // Drawn at 1920x1080 and scaled to the page's size: VIDAA's browser often lays pages out at 1280x720.
  function fit() {
    var k = window.innerWidth / 1920;
    document.body.style.transformOrigin = "0 0";
    document.body.style.transform = Math.abs(k - 1) > 0.01 ? "scale(" + k + ")" : "";
  }
  fit();
  window.addEventListener("resize", fit);

  // ---------- start ----------

  if (load("token")) ready(); else startLink();
})();
