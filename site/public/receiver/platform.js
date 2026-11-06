/*
 * Zephyr TV Receiver: what differs from one TV to the other, and only that (app.js is the same on every one). Each
 * platform says how it's told apart, its name for the account's devices, how the app leaves, and whether it can play
 * a stream with its own headers (and how). Another TV: one more entry here. ES5, as the rest.
 */
(function () {
  "use strict";
  var UA = navigator.userAgent || "";
  var VERSION = "1.0.0"; // the Receiver's version: webos/package.sh writes it into the .ipk too

  /** Leaving the app, as a TV app does: its own call when there's one, else window.close() (VIDAA's way). */
  function close() { window.close(); }

  // ---------- webOS: its service (webos/service) through the TV's own bridge ----------

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

  var PLATFORMS = [
    {
      id: "webOS", tv: "LG TV",
      is: function () { return typeof PalmServiceBridge !== "undefined" || /Web0S|webOS/.test(UA); },
      // The service up while the app runs: subscribed, the system doesn't stop it when idle.
      start: function () { luna("start", { subscribe: true }); },
      exit: function () { if (typeof webOS !== "undefined" && webOS.platformBack) webOS.platformBack(); else close(); },
      headers: true,
      /** [url] with [headers]: the service's local address for it (the TV's player can't send them itself). */
      proxy: function (url, headers, done) {
        luna("play", { url: url, headers: headers }, function (a) { done(a.returnValue && a.url ? a.url : url); });
      }
    },
    {
      id: "VIDAA", tv: "Hisense TV",
      is: function () { return /VIDAA|Hisense/i.test(UA); },
      start: function () {},
      exit: close,
      headers: false // no service of its own: Zephyr TV doesn't send it streams that need them
    },
    {
      id: "Web", tv: "TV", // a browser, for a try on a computer
      is: function () { return true; },
      start: function () {},
      exit: close,
      headers: false
    }
  ];

  var p = PLATFORMS.filter(function (x) { return x.is(); })[0];
  p.version = VERSION;
  p.model = p.id + " · Zephyr TV Receiver"; // how Zephyr TV knows a Receiver, and which (see Handoff.Device)
  if (!p.proxy) p.proxy = function (url, headers, done) { done(url); };
  window.Platform = p;
})();
