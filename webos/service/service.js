/*
 * Zephyr TV Receiver's service: a small proxy on the TV (127.0.0.1) for the streams that need their own headers (a
 * User-Agent, a Referer). A web app's player can't send those, Node can: the app asks for a stream's local address
 * (play), and the TV's player reads it from here as from any server. Byte ranges go through (seeking); an HLS
 * playlist is rewritten so its segments, keys and other playlists come through here too, with the same headers.
 * ES5 and Node's own modules only: webOS 3 and 4 run an old Node.
 */
"use strict";

var Service = require("webos-service");
var http = require("http");
var https = require("https");
var url = require("url");
var crypto = require("crypto");

var service = new Service("link.mccoy88f.zephyr.receiver.service");
var streams = {}; // id -> {url, headers}, the last few only
var order = [];
var server = null;
var port = 0;
var keep = null; // the activity that keeps the service up while the app subscribes

function up(done) {
  if (server) return done(port);
  server = http.createServer(handle);
  server.on("error", function (e) { console.error("receiver proxy: " + e); });
  server.listen(0, "127.0.0.1", function () {
    port = server.address().port;
    done(port);
  });
}

/** The proxy up; subscribed, the service stays up while the app runs (an idle service is stopped by the system). */
service.register("start", function (msg) {
  if (msg.isSubscription && !keep) {
    service.activityManager.create("keepAlive", function (activity) { keep = activity; });
  }
  up(function (p) { msg.respond({ returnValue: true, port: p, subscribed: !!msg.isSubscription }); });
}, function () {
  // The app gone (its subscription cancelled): the service may stop.
  if (keep) { service.activityManager.complete(keep, function () {}); keep = null; }
});

/** A stream's local address: {url, headers} in, {url: http://127.0.0.1:<port>/s/<id>/<name>} out. */
service.register("play", function (msg) {
  var p = msg.payload || {};
  if (!p.url || !/^https?:/i.test(p.url)) return msg.respond({ returnValue: false, errorText: "no http address" });
  up(function (port) {
    var id = crypto.randomBytes(12).toString("hex");
    streams[id] = { url: p.url, headers: clean(p.headers) };
    order.push(id);
    while (order.length > 4) delete streams[order.shift()];
    var name = (url.parse(p.url).pathname || "/video").split("/").pop() || "video";
    msg.respond({ returnValue: true, url: "http://127.0.0.1:" + port + "/s/" + id + "/" + encodeURIComponent(name) });
  });
});

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

/** /s/<id>/<name>: the stream; /s/<id>/u/<address>: a segment, key or playlist of its (an HLS stream's). */
function handle(req, res) {
  var parts = (req.url || "").split("?")[0].split("/");
  var s = streams[parts[2]];
  if (parts[1] !== "s" || !s) { res.writeHead(404); return res.end(); }
  var target = s.url;
  if (parts[3] === "u" && parts[4]) {
    try { target = new Buffer(decodeURIComponent(parts[4]), "base64").toString("utf8"); } catch (e) { target = null; }
    if (!target || !/^https?:/i.test(target)) { res.writeHead(400); return res.end(); }
  }
  fetch(target, s.headers, req.headers.range, req.method === "HEAD" ? "HEAD" : "GET", 0, function (err, up, finalUrl) {
    if (err) { res.writeHead(502); return res.end(); }
    var type = up.headers["content-type"] || "";
    if (req.method !== "HEAD" && up.statusCode < 300 && isPlaylist(finalUrl, type)) return playlist(up, finalUrl, parts[2], res);
    var head = {};
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
function playlist(up, base, id, res) {
  var chunks = [];
  up.on("data", function (c) { chunks.push(c); });
  up.on("end", function () {
    var text = Buffer.concat(chunks).toString("utf8");
    function local(addr) {
      var abs = url.resolve(base, addr.trim());
      return "/s/" + id + "/u/" + encodeURIComponent(new Buffer(abs, "utf8").toString("base64"));
    }
    var out = text.split(/\r?\n/).map(function (line) {
      if (!line) return line;
      if (line.charAt(0) === "#") return line.replace(/URI="([^"]+)"/g, function (m, a) { return 'URI="' + local(a) + '"'; });
      return local(line);
    }).join("\n");
    res.writeHead(200, { "Content-Type": "application/vnd.apple.mpegurl", "Cache-Control": "no-cache" });
    res.end(out);
  });
  up.on("error", function () { res.writeHead(502); res.end(); });
}
