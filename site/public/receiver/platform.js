/*
 * Zephyr TV Receiver: what differs from one TV to the other, and only that (app.js is the same on every one). Each
 * platform says how it's told apart, its name for the account's devices, how the app leaves, its own remote keys,
 * and whether it can play a stream with its own headers: through the proxy (webos/service/service.js, one file for
 * webOS and Tizen), on 127.0.0.1. Another TV: one more entry here. ES5, as the rest.
 */
(function () {
  "use strict";
  var UA = navigator.userAgent || "";
  var VERSION = "1.0.0"; // the Receiver's version: webos/package.sh writes it into the .ipk too, and package.json has it

  /** Leaving the app, as a TV app does: window.close() where there's nothing of its own (VIDAA's way). */
  function close() { window.close(); }

  /** The proxy's address for [url] with [headers], on [port]: what to fetch rides in the address (see service.js). */
  function proxyUrl(port, url, headers) {
    var json = JSON.stringify({ u: url, h: headers });
    // UTF-8, then base64url: the address's path, as the proxy reads it.
    var b64 = btoa(unescape(encodeURIComponent(json))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    var name = (url.split("?")[0].split("/").pop() || "video");
    return "http://127.0.0.1:" + port + "/p/" + b64 + "/" + encodeURIComponent(name);
  }

  /** Whether the proxy answers on [port]: [done](true or false), in at most 2 s. */
  function ping(port, done) {
    var x = new XMLHttpRequest();
    x.open("GET", "http://127.0.0.1:" + port + "/ping", true);
    x.timeout = 2000;
    x.onload = function () { done(x.status === 200); };
    x.onerror = x.ontimeout = function () { done(false); };
    x.send();
  }

  // ---------- webOS: the app's service through the TV's own bridge ----------

  var SERVICE = "luna://link.mccoy88f.zephyr.receiver.service/";
  var bridges = [];
  /** [method] of the app's service with [params]; [cb](answer) for each answer (a subscription answers again). */
  function luna(method, params, cb) {
    if (typeof PalmServiceBridge === "undefined") { if (cb) cb({ returnValue: false, errorText: "not on webOS" }); return; }
    var b = new PalmServiceBridge(); // kept: a bridge let go of ends its call (and a subscription)
    bridges.push(b);
    b.onservicecallback = function (text) {
      var a;
      try { a = JSON.parse(text); } catch (e) { a = { returnValue: false }; }
      if (cb) cb(a);
    };
    b.call(SERVICE + method, JSON.stringify(params || {}));
  }
  var webosPort = 0;

  // ---------- Tizen: TizenBrew's module service, on its own port ----------

  var TIZEN_PORT = 8723; // service.js's PORT
  var tizenProxy = false;

  var PLATFORMS = [
    {
      id: "webOS", tv: "LG TV",
      is: function () { return typeof PalmServiceBridge !== "undefined" || /Web0S|webOS/.test(UA); },
      // The service up while the app runs (subscribed, the system doesn't stop it when idle), and its port.
      start: function () { luna("start", { subscribe: true }, function (a) { if (a.returnValue && a.port) webosPort = a.port; }); },
      exit: function () { if (typeof webOS !== "undefined" && webOS.platformBack) webOS.platformBack(); else close(); },
      keys: { back: [461] },
      proxy: function (url, headers, done) {
        if (webosPort) return done(proxyUrl(webosPort, url, headers));
        luna("start", {}, function (a) { if (a.returnValue && a.port) webosPort = a.port; done(webosPort ? proxyUrl(webosPort, url, headers) : url); });
      }
    },
    {
      id: "Tizen", tv: "Samsung TV",
      is: function () { return typeof tizen !== "undefined" || /Tizen/i.test(UA); },
      // Samsung's media keys come to the app only once registered (TizenBrew registers package.json's keys too).
      start: function () {
        try {
          ["MediaPlayPause", "MediaPlay", "MediaPause", "MediaStop", "MediaFastForward", "MediaRewind"].forEach(function (k) {
            tizen.tvinputdevice.registerKey(k);
          });
        } catch (e) { /* not there, or not allowed */ }
        // Through TizenBrew, its service is the proxy; installed by itself (a .wgt), there's none.
        ping(TIZEN_PORT, function (ok) { tizenProxy = ok; });
      },
      exit: function () {
        try { tizen.application.getCurrentApplication().exit(); } catch (e) { close(); }
      },
      keys: { back: [10009], playPause: [10252] },
      proxy: function (url, headers, done) { done(tizenProxy ? proxyUrl(TIZEN_PORT, url, headers) : url); }
    },
    {
      id: "VIDAA", tv: "Hisense TV",
      is: function () { return /VIDAA|Hisense/i.test(UA); },
      start: function () {},
      exit: close,
      keys: { back: [8] }, // its Back is Backspace
      proxy: null // no service of its own: Zephyr TV doesn't send it streams that need headers
    },
    {
      id: "Web", tv: "TV", // a browser, for a try on a computer
      is: function () { return true; },
      start: function () {},
      exit: close,
      keys: {},
      proxy: null
    }
  ];

  var p = PLATFORMS.filter(function (x) { return x.is(); })[0];
  p.version = VERSION;
  p.model = p.id + " · Zephyr TV Receiver"; // how Zephyr TV knows a Receiver, and which (see Handoff.Device)
  if (!p.proxy) p.proxy = function (url, headers, done) { done(url); };
  window.Platform = p;
})();
