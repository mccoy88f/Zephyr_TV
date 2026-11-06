/*
 * Zephyr TV Receiver's proxy: on the TV (127.0.0.1), for the streams that need their own headers (a User-Agent, a
 * Referer). A web app's player can't send those, Node can: the app asks for a stream at a local address that says
 * what to fetch and with which headers (/p/<them, base64url JSON>/<name>), and the TV's player reads it from here as
 * from any server. Byte ranges go through (seeking), redirects are followed, and an HLS playlist is rewritten so its
 * segments, keys and other playlists come through here too, with the same headers.
 *
 * One file for every TV that runs it: on webOS it's the app's service (luna: start answers its port); on Samsung's
 * Tizen it's the TizenBrew module's service (see package.json at the repository's root), on a port of its own
 * (PORT). ES5 and Node's own modules only: webOS 3 and 4 and TizenBrew (Node 4) run an old Node.
 */
"use strict";

var http = require("http");
var https = require("https");
var url = require("url");

var PORT = 8723; // where Tizen's app finds it (webOS's asks the service)

// ---------- the proxy ----------

/** [o] as text for an address: JSON, base64url. */
function pack(o) {
  return new Buffer(JSON.stringify(o), "utf8").toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function unpack(s) {
  try {
    var b64 = s.replace(/-/g, "+").replace(/_/g, "/");
    while (b64.length % 4) b64 += "=";
    return JSON.parse(new Buffer(b64, "base64").toString("utf8"));
  } catch (e) {
    return null;
  }
}

/** The headers a stream asks for: names and values as text, none that would break the request. */
function clean(h) {
  var out = {};
  if (!h || typeof h !== "object") return out;
  Object.keys(h).forEach(function (k) {
    var low = k.toLowerCase();
    if (["host", "content-length", "connection", "range", "accept-encoding", "transfer-encoding"].indexOf(low) >= 0) return;
    if (typeof h[k] === "string" && /^[\x20-\x7e\t]*$/.test(h[k])) out[k] = h[k];
  });
  return out;
}

function isPlaylist(target, type) {
  return /mpegurl/i.test(type || "") || /\.m3u8?(\?|$)/i.test(url.parse(target).pathname || "");
}

/** /ping: it's up. /p/<{u, h}>/<name>: [u] fetched with the headers [h]. */
function handle(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  var parts = (req.url || "").split("?")[0].split("/");
  if (parts[1] === "ping") { res.writeHead(200, { "Content-Type": "text/plain" }); return res.end("ok"); }
  var what = parts[1] === "p" ? unpack(parts[2] || "") : null;
  if (!what || typeof what.u !== "string" || !/^https?:/i.test(what.u)) { res.writeHead(404); return res.end(); }
  var headers = clean(what.h);
  fetch(what.u, headers, req.headers.range, req.method === "HEAD" ? "HEAD" : "GET", 0, function (err, up, finalUrl) {
    if (err) { res.writeHead(502); return res.end(); }
    var type = up.headers["content-type"] || "";
    if (req.method !== "HEAD" && up.statusCode < 300 && isPlaylist(finalUrl, type)) return playlist(up, finalUrl, headers, res);
    var head = { "Access-Control-Allow-Origin": "*" };
    ["content-type", "content-length", "content-range", "accept-ranges", "last-modified", "etag"].forEach(function (k) {
      if (up.headers[k]) head[k] = up.headers[k];
    });
    if (!head["accept-ranges"] && up.statusCode === 206) head["accept-ranges"] = "bytes";
    res.writeHead(up.statusCode, head);
    up.pipe(res);
    res.on("close", function () { up.destroy(); });
  });
}

/** [target] with [headers] (and the player's [range]), redirects followed: [done](error, answer, its address). */
function fetch(target, headers, range, method, hops, done) {
  var u = url.parse(target);
  var mod = u.protocol === "https:" ? https : http;
  var h = {};
  Object.keys(headers).forEach(function (k) { h[k] = headers[k]; });
  if (range) h.Range = range;
  if (!Object.keys(h).some(function (k) { return k.toLowerCase() === "user-agent"; })) h["User-Agent"] = "Zephyr TV Receiver";
  var r = mod.request({ protocol: u.protocol, hostname: u.hostname, port: u.port, path: u.path, method: method, headers: h }, function (up) {
    if (up.statusCode >= 300 && up.statusCode < 400 && up.headers.location && hops < 6) {
      up.resume();
      return fetch(url.resolve(target, up.headers.location), headers, range, method, hops + 1, done);
    }
    done(null, up, target);
  });
  r.setTimeout(30000, function () { r.abort(); });
  r.on("error", function (e) { done(e); });
  r.end();
}

/** An HLS playlist, its addresses (lines and URI="…") made the proxy's, so they come with the headers too. */
function playlist(up, base, headers, res) {
  var chunks = [];
  up.on("data", function (c) { chunks.push(c); });
  up.on("end", function () {
    var text = Buffer.concat(chunks).toString("utf8");
    function local(addr) {
      var abs = url.resolve(base, addr.trim());
      return "/p/" + pack({ u: abs, h: headers }) + "/" + (url.parse(abs).pathname || "/x").split("/").pop();
    }
    var out = text.split(/\r?\n/).map(function (line) {
      if (!line) return line;
      if (line.charAt(0) === "#") return line.replace(/URI="([^"]+)"/g, function (m, a) { return 'URI="' + local(a) + '"'; });
      return local(line);
    }).join("\n");
    res.writeHead(200, { "Content-Type": "application/vnd.apple.mpegurl", "Cache-Control": "no-cache", "Access-Control-Allow-Origin": "*" });
    res.end(out);
  });
  up.on("error", function () { res.writeHead(502); res.end(); });
}

var server = null;
/** The proxy up on [port] (0: any free one): [done](its port). */
function up(port, done) {
  if (server) return done(server.address().port);
  server = http.createServer(handle);
  server.on("error", function (e) { console.error("receiver proxy: " + e); server = null; });
  server.listen(port, "127.0.0.1", function () { done(server.address().port); });
}

// ---------- where it runs ----------

var Service = null;
try { Service = require("webos-service"); } catch (e) { Service = null; }

if (Service) {
  // webOS: the app's service. start: the proxy up and its port; subscribed, the service stays up while the app runs.
  var service = new Service("link.mccoy88f.zephyr.receiver.service");
  var keep = null;
  service.register("start", function (msg) {
    if (msg.isSubscription && !keep) service.activityManager.create("keepAlive", function (activity) { keep = activity; });
    up(0, function (p) { msg.respond({ returnValue: true, port: p, subscribed: !!msg.isSubscription }); });
  }, function () {
    if (keep) { service.activityManager.complete(keep, function () {}); keep = null; }
  });
} else {
  // TizenBrew (Tizen): the module's service, started with the app; the app finds it on PORT (see platform.js).
  up(PORT, function () {});
}

if (typeof module !== "undefined" && module.exports) module.exports = { pack: pack, unpack: unpack, PORT: PORT };
