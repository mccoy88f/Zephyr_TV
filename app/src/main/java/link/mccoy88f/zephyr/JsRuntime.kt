package link.mccoy88f.zephyr

import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The JavaScript engine scripts from elsewhere run in: Nuvio's plugins and Zephyr TV's extensions. QuickJS, a context
 * of its own for each call, with what a script expects of a browser (fetch, timers, console, base64, URL, a
 * localStorage of its own), the modules it may require(), the services Zephyr TV gives it by name, a memory limit
 * and a time limit; no files. Its fetch is ours (Http), so cookies and redirects are seen as a browser wouldn't let a
 * page see them.
 */
internal object Js {
    private const val FETCH_MS = 15_000 // one of its requests, at most
    private const val MEMORY = 64L * 1024 * 1024
    private const val BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * The modules a script (a Nuvio plugin, an extension) gets with require(), by name, and the global each bundle defines: HTML read as
     * jQuery does (cheerio) and ciphers (crypto-js), bundled from npm into assets/nuvio. [modules] reads one's source:
     * the app's assets (set by the activities), files in the unit tests.
     */
    private val MODULES = mapOf("cheerio-without-node-native" to ("cheerio.js" to "__mod_cheerio"), "cheerio" to ("cheerio.js" to "__mod_cheerio"),
        "crypto-js" to ("crypto-js.js" to "__mod_crypto"))
    @Volatile var modules: (String) -> String? = { null }

    /** Where a script's console goes: nowhere, but in the tests. */
    @Volatile var console: ((String) -> Unit)? = null

    /** The modules from the app's assets (assets/nuvio), each read once. */
    fun useAssets(context: android.content.Context) {
        val read = ConcurrentHashMap<String, String>()
        modules = { file -> read[file] ?: runCatching { context.assets.open("nuvio/$file").use { it.readBytes().decodeToString() } }.getOrNull()?.also { read[file] = it } }
    }

    /** What a script's fetch got: the final address, the answer's status and headers, and its body. */
    internal class Fetched(val status: Int, val statusText: String, val url: String, val headers: List<Pair<String, String>>, val body: ByteArray,
                           val charset: String?) {
        fun text() = String(body, runCatching { charset(charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
    }

    /**
     * A script's request ([req]: url, method, headers, body or body64, redirect), as fetch would make it: redirects
     * followed (http to https too) unless "manual", any status answered rather than thrown.
     */
    internal fun httpFetch(req: JSONObject): Fetched {
        var at = req.getString("url")
        var method = req.optString("method", "GET").uppercase()
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = BROWSER
        req.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
        headers.keys.filter { it.equals("accept-encoding", true) || it.equals("content-length", true) || it.equals("host", true) }
            .forEach { headers.remove(it) } // Android decompresses gzip itself only when it asked for it
        var body: ByteArray? = when {
            !req.isNull("body64") -> java.util.Base64.getDecoder().decode(req.getString("body64"))
            !req.isNull("body") -> req.getString("body").toByteArray()
            else -> null
        }
        val manual = req.optString("redirect") == "manual"
        for (hop in 0..8) {
            val c = Http.connect(at, headers, FETCH_MS).apply {
                instanceFollowRedirects = false
                requestMethod = method
            }
            try {
                if (body != null && method != "GET" && method != "HEAD") {
                    c.doOutput = true
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                val location = c.getHeaderField("Location")
                if (!manual && code in 300..399 && location != null) {
                    at = URL(URL(at), location).toString()
                    if (code == 303 || ((code == 301 || code == 302) && method == "POST")) { method = "GET"; body = null }
                    continue
                }
                val hs = c.headerFields.filterKeys { it != null }.flatMap { (k, vs) -> vs.map { k.lowercase() to it } }
                val bytes = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readCapped(Http.MAX_TEXT) } ?: ByteArray(0)
                val charset = c.contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
                return Fetched(code, c.responseMessage.orEmpty(), at, hs, bytes, charset)
            } finally {
                c.disconnect()
            }
        }
        throw java.io.IOException("too many redirects")
    }

    /**
     * Runs [script] in a QuickJS context of its own, with [globals] and our fetch ([fetch], Http by default), then
     * [call], a value or a promise of one: it as JSON, or null when it ran out of time ([timeoutMs]). Throws when the
     * script fails. [stored] gets its localStorage (globalThis.__store, as JSON) once [call] is done. [services]: what
     * the script asks Zephyr TV for by name, __service(name, args) in it, a promise of the answer (JSON).
     */
    internal fun run(script: String, call: String, globals: String = "", timeoutMs: Long,
                     fetch: (JSONObject) -> Fetched = ::httpFetch, stored: ((String) -> Unit)? = null,
                     services: Map<String, suspend (JSONObject) -> Any?> = emptyMap(),
                     extraModules: Map<String, String> = emptyMap()): String? = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                quickJs {
                    memoryLimit = MEMORY
                    val bodies = ConcurrentHashMap<Int, Fetched>()
                    val next = AtomicInteger()
                    function("__log") { args: Array<Any?> -> console?.invoke(args.firstOrNull()?.toString().orEmpty()); null }
                    asyncFunction("__sleep") { args -> delay((args.getOrNull(0) as? Number)?.toLong() ?: 0L); null }
                    asyncFunction("__fetch") { args ->
                        try {
                            val got = runInterruptible(Dispatchers.IO) { fetch(JSONObject(args[0] as String)) }
                            val n = next.incrementAndGet()
                            bodies[n] = got
                            JSONObject().put("id", n).put("status", got.status).put("statusText", got.statusText).put("url", got.url)
                                .put("headers", JSONArray(got.headers.map { (k, v) -> JSONArray().put(k).put(v) })).toString().let(::ascii)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            ascii(JSONObject().put("error", e.toString()).toString())
                        }
                    }
                    function("__text") { args -> ascii(JSONObject.quote(bodies[(args[0] as Number).toInt()]?.text() ?: "")) }
                    function("__body64") { args -> bodies[(args[0] as Number).toInt()]?.let { java.util.Base64.getEncoder().encodeToString(it.body) } ?: "" }
                    asyncFunction("__service") { args ->
                        val name = args.getOrNull(0) as? String
                        val task = services[name] ?: return@asyncFunction ascii(JSONObject().put("error", "no service $name").toString())
                        try {
                            val answer = task(JSONObject(args.getOrNull(1) as? String ?: "{}"))
                            ascii(JSONObject().put("value", answer ?: JSONObject.NULL).toString())
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            ascii(JSONObject().put("error", e.message ?: e.toString()).toString())
                        }
                    }
                    // Each ends in undefined: what a script leaves is turned into Kotlin, a module (circular) can't be.
                    evaluate<Any?>(PRELUDE + globals + END, "prelude.js", false)
                    // The modules it require()s, those we have: each bundle defines its global, then is registered.
                    val needed = Regex("""require\(\s*['"]([^'"]+)['"]\s*\)""").findAll(script).map { it.groupValues[1] }.toSet()
                    for (name in needed) {
                        val (file, global) = MODULES[name] ?: continue
                        val code = modules(file) ?: continue
                        evaluate<Any?>(code + END, file, false)
                        evaluate<Any?>("__modules[${JSONObject.quote(name)}] = $global;$END", "require.js", false)
                    }
                    // Libraries downloaded into the extension's folder: one CommonJS file each (module.exports).
                    for (name in needed) {
                        if (name in MODULES) continue
                        val code = extraModules[name] ?: continue
                        val q = JSONObject.quote(name)
                        evaluate<Any?>(
                            "(function(){ var module = { exports: {} }; var exports = module.exports; $code\n__modules[$q] = module.exports; })();$END",
                            name, false)
                    }
                    evaluate<Any?>(script + END, "plugin.js", false)
                    val out = evaluate<String?>("__ascii(JSON.stringify(await $call))", "call.js", false)
                    if (stored != null) evaluate<String?>("__ascii(JSON.stringify(globalThis.__store))", "store.js", false)?.let(stored)
                    out
                }
            }
        }
    }

    /**
     * [s] with every character past ASCII written \uXXXX: strings cross between QuickJS and Kotlin as JSON in ASCII,
     * since the native bridge mangles what isn't in Unicode's first plane (a 💎 in a name).
     */
    internal fun ascii(s: String): String {
        if (s.all { it.code < 128 }) return s
        val out = StringBuilder(s.length + 16)
        for (c in s) if (c.code < 128) out.append(c) else out.append("\\u").append("%04x".format(c.code))
        return out.toString()
    }

    /** Ends what's evaluated with nothing to hand back (see run). */
    private const val END = "\n;undefined;\n"

    /** What a script expects of a browser and QuickJS hasn't: fetch, timers, console, base64, a UTF-8 TextDecoder. */
    private val PRELUDE = """
        (function () {
          var g = globalThis;
          g.__ascii = function (s) {
            return String(s).replace(/[\u0080-\uffff]/g, function (c) { return "\\u" + ("000" + c.charCodeAt(0).toString(16)).slice(-4); });
          };
          var say = function () {
            var line = Array.prototype.map.call(arguments, function (a) {
              if (typeof a === "string") return a;
              try { return JSON.stringify(a); } catch (e) { return String(a); }
            }).join(" ");
            __log(g.__ascii(line.length > 2000 ? line.slice(0, 2000) : line));
          };
          g.console = { log: say, info: say, warn: say, error: say, debug: say };
          var timers = 0, cleared = {};
          g.setTimeout = function (fn, ms) {
            var args = Array.prototype.slice.call(arguments, 2), id = ++timers;
            __sleep(Number(ms) || 0).then(function () { if (!cleared[id] && typeof fn === "function") fn.apply(null, args); });
            return id;
          };
          g.clearTimeout = function (id) { cleared[id] = true; };
          var B = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
          function toB64(bytes) {
            var out = "", i;
            for (i = 0; i + 2 < bytes.length; i += 3) {
              var n = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
              out += B[n >> 18 & 63] + B[n >> 12 & 63] + B[n >> 6 & 63] + B[n & 63];
            }
            if (i < bytes.length) {
              var m = bytes[i] << 16 | (i + 1 < bytes.length ? bytes[i + 1] << 8 : 0);
              out += B[m >> 18 & 63] + B[m >> 12 & 63] + (i + 1 < bytes.length ? B[m >> 6 & 63] : "=") + "=";
            }
            return out;
          }
          function fromB64(s) {
            s = String(s).replace(/[^A-Za-z0-9+/]/g, "");
            var out = new Uint8Array(Math.floor(s.length * 3 / 4)), o = 0, buf = 0, bits = 0;
            for (var i = 0; i < s.length; i++) {
              buf = (buf << 6) | B.indexOf(s[i]); bits += 6;
              if (bits >= 8) { bits -= 8; out[o++] = (buf >> bits) & 255; }
            }
            return out.subarray(0, o);
          }
          if (typeof g.btoa !== "function") g.btoa = function (s) {
            s = String(s); var b = new Uint8Array(s.length);
            for (var i = 0; i < s.length; i++) b[i] = s.charCodeAt(i) & 255;
            return toB64(b);
          };
          if (typeof g.atob !== "function") g.atob = function (s) {
            var b = fromB64(s), out = "";
            for (var i = 0; i < b.length; i++) out += String.fromCharCode(b[i]);
            return out;
          };
          function utf8(bytes) {
            var out = "", i = 0;
            while (i < bytes.length) {
              var c = bytes[i++];
              if (c < 128) out += String.fromCharCode(c);
              else if (c < 224) out += String.fromCharCode(((c & 31) << 6) | (bytes[i++] & 63));
              else if (c < 240) out += String.fromCharCode(((c & 15) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63));
              else {
                var p = (((c & 7) << 18) | ((bytes[i++] & 63) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63)) - 65536;
                out += String.fromCharCode(55296 + (p >> 10), 56320 + (p & 1023));
              }
            }
            return out;
          }
          g.global = g; // Node's name for it, which some plugins use
          g.window = g; // and the browser's
          g.self = g;
          // localStorage, a browser's, the plugin's own: strings by key in __store (set by the run, read back after it).
          g.__store = {};
          g.__storeLimit = 1048576; // 1 MB, as JSON
          // A service of Zephyr TV, by name: a promise of its answer (see Js.run).
          g.__zephyr = function (name, args) {
            return __service(name, g.__ascii(JSON.stringify(args || {}))).then(function (s) {
              var m = JSON.parse(s);
              if (m.error) throw new Error(m.error);
              return m.value;
            });
          };
          (function () {
            var own = function (k) { return Object.prototype.hasOwnProperty.call(g.__store, k); };
            var api = {
              getItem: function (k) { k = String(k); return own(k) ? g.__store[k] : null; },
              setItem: function (k, v) {
                k = String(k); v = String(v);
                var was = g.__store[k];
                g.__store[k] = v;
                if (JSON.stringify(g.__store).length > g.__storeLimit) {
                  if (was === undefined) delete g.__store[k]; else g.__store[k] = was;
                  var e = new Error("localStorage is full"); e.name = "QuotaExceededError"; throw e;
                }
              },
              removeItem: function (k) { delete g.__store[String(k)]; },
              clear: function () { g.__store = {}; },
              key: function (i) { var ks = Object.keys(g.__store); return i >= 0 && i < ks.length ? ks[i] : null; }
            };
            g.localStorage = new Proxy(api, {
              get: function (t, p) {
                if (p === "length") return Object.keys(g.__store).length;
                if (Object.prototype.hasOwnProperty.call(api, p)) return api[p];
                return typeof p === "string" && own(p) ? g.__store[p] : undefined;
              },
              set: function (t, p, v) { api.setItem(p, v); return true; },
              deleteProperty: function (t, p) { delete g.__store[String(p)]; return true; },
              has: function (t, p) { return p === "length" || Object.prototype.hasOwnProperty.call(api, p) || own(p); },
              ownKeys: function () { return Object.keys(g.__store); },
              getOwnPropertyDescriptor: function (t, p) {
                return own(p) ? { value: g.__store[p], writable: true, enumerable: true, configurable: true } : undefined;
              }
            });
          })();
          g.module = { exports: {} };
          g.exports = g.module.exports;
          g.__modules = {};
          g.require = function (n) {
            if (g.__modules[n]) return g.__modules[n];
            if (n === "ws") throw new Error("WebSocket (ws) is not supported");
            throw new Error("Cannot find module '" + n + "'");
          };
          function dec(s) { try { return decodeURIComponent(String(s).replace(/\+/g, " ")); } catch (e) { return String(s); } }
          function enc(s) {
            return encodeURIComponent(String(s)).replace(/%20/g, "+").replace(/[!'()~]/g, function (c) { return "%" + c.charCodeAt(0).toString(16).toUpperCase(); });
          }
          function SearchParams(init) {
            this._l = []; this._u = null;
            var self = this;
            if (init == null) return;
            if (typeof init === "string") String(init).replace(/^\?/, "").split("&").forEach(function (p) {
              if (!p) return;
              var i = p.indexOf("=");
              self._l.push(i < 0 ? [dec(p), ""] : [dec(p.slice(0, i)), dec(p.slice(i + 1))]);
            });
            else if (Array.isArray(init)) init.forEach(function (p) { self._l.push([String(p[0]), String(p[1])]); });
            else if (init instanceof SearchParams) init._l.forEach(function (p) { self._l.push([p[0], p[1]]); });
            else for (var k in init) self._l.push([k, String(init[k])]);
          }
          SearchParams.prototype._changed = function () { if (this._u) this._u._search = this._l.length ? "?" + this.toString() : ""; };
          SearchParams.prototype.append = function (k, v) { this._l.push([String(k), String(v)]); this._changed(); };
          SearchParams.prototype["delete"] = function (k) { this._l = this._l.filter(function (p) { return p[0] !== String(k); }); this._changed(); };
          SearchParams.prototype.get = function (k) { for (var i = 0; i < this._l.length; i++) if (this._l[i][0] === String(k)) return this._l[i][1]; return null; };
          SearchParams.prototype.getAll = function (k) { return this._l.filter(function (p) { return p[0] === String(k); }).map(function (p) { return p[1]; }); };
          SearchParams.prototype.has = function (k) { return this.get(k) !== null; };
          SearchParams.prototype.set = function (k, v) {
            k = String(k); v = String(v);
            var i = -1;
            this._l = this._l.filter(function (p, n) { if (p[0] !== k) return true; if (i < 0) { i = n; return true; } return false; });
            if (i < 0) this._l.push([k, v]); else this._l[i][1] = v;
            this._changed();
          };
          SearchParams.prototype.sort = function () { this._l.sort(function (a, b) { return a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0; }); this._changed(); };
          SearchParams.prototype.toString = function () { return this._l.map(function (p) { return enc(p[0]) + "=" + enc(p[1]); }).join("&"); };
          SearchParams.prototype.forEach = function (fn) { var self = this; this._l.forEach(function (p) { fn(p[1], p[0], self); }); };
          SearchParams.prototype.entries = function () { return this._l.map(function (p) { return [p[0], p[1]]; })[Symbol.iterator](); };
          SearchParams.prototype.keys = function () { return this._l.map(function (p) { return p[0]; })[Symbol.iterator](); };
          SearchParams.prototype.values = function () { return this._l.map(function (p) { return p[1]; })[Symbol.iterator](); };
          SearchParams.prototype[Symbol.iterator] = SearchParams.prototype.entries;
          Object.defineProperty(SearchParams.prototype, "size", { get: function () { return this._l.length; } });
          if (typeof g.URLSearchParams !== "function") g.URLSearchParams = SearchParams;
          var SCHEME = /^([a-zA-Z][a-zA-Z0-9+.-]*:)(.*)$/;
          var PORTS = { "http:": "80", "https:": "443", "ws:": "80", "wss:": "443", "ftp:": "21" };
          function dots(path) {
            var out = [];
            path.split("/").forEach(function (s, i, all) {
              if (s === "..") { if (out.length > 1) out.pop(); if (i === all.length - 1) out.push(""); }
              else if (s === ".") { if (i === all.length - 1) out.push(""); }
              else out.push(s);
            });
            var p = out.join("/");
            return p.charAt(0) === "/" ? p : "/" + p;
          }
          function Url(url, base) {
            url = String(url).replace(/^[\s\u0000-\u001f]+|[\s\u0000-\u001f]+$/g, "");
            var m = SCHEME.exec(url);
            if (!m) {
              if (base === undefined) throw new TypeError("Invalid URL: " + url);
              var b = base instanceof Url ? base : new Url(base);
              url = resolve(b, url);
              m = SCHEME.exec(url);
            }
            this._parse(m[1].toLowerCase(), m[2]);
          }
          function resolve(b, rel) {
            if (rel.slice(0, 2) === "//") return b.protocol + rel;
            if (rel.charAt(0) === "/") return b.protocol + "//" + b.host + rel;
            if (rel === "") return b.href.split("#")[0];
            if (rel.charAt(0) === "?") return b.protocol + "//" + b.host + b.pathname + rel;
            if (rel.charAt(0) === "#") return b.href.split("#")[0] + rel;
            return b.protocol + "//" + b.host + b.pathname.slice(0, b.pathname.lastIndexOf("/") + 1) + rel;
          }
          Url.prototype._parse = function (protocol, rest) {
            this.protocol = protocol;
            var h = rest.indexOf("#");
            this._hash = h >= 0 && h < rest.length - 1 ? rest.slice(h) : "";
            if (h >= 0) rest = rest.slice(0, h);
            var q = rest.indexOf("?");
            this._search = q >= 0 && q < rest.length - 1 ? rest.slice(q) : "";
            if (q >= 0) rest = rest.slice(0, q);
            this.username = ""; this.password = ""; this.hostname = ""; this.port = "";
            if (rest.slice(0, 2) === "//") {
              rest = rest.slice(2);
              var s = rest.search(/[\/\\]/), auth = s < 0 ? rest : rest.slice(0, s);
              rest = s < 0 ? "" : rest.slice(s).replace(/\\/g, "/");
              var at = auth.lastIndexOf("@");
              if (at >= 0) {
                var user = auth.slice(0, at), c = user.indexOf(":");
                this.username = c < 0 ? user : user.slice(0, c);
                this.password = c < 0 ? "" : user.slice(c + 1);
                auth = auth.slice(at + 1);
              }
              var pm = /^(\[[^\]]*\]|[^:]*)(?::(\d*))?$/.exec(auth) || [auth, auth, ""];
              this.hostname = pm[1].toLowerCase();
              this.port = pm[2] && pm[2] !== PORTS[protocol] ? pm[2] : "";
              this.pathname = PORTS[protocol] ? dots(rest || "/") : rest;
              this._special = true;
            } else {
              this.pathname = rest;
              this._special = false;
            }
            this._sp = null;
          };
          function prop(name, get, set) { Object.defineProperty(Url.prototype, name, { get: get, set: set, configurable: true }); }
          prop("host", function () { return this.hostname + (this.port ? ":" + this.port : ""); }, function (v) {
            var p = String(v).split(":"); this.hostname = p[0].toLowerCase(); this.port = p[1] && p[1] !== PORTS[this.protocol] ? p[1] : "";
          });
          prop("origin", function () { return this._special ? this.protocol + "//" + this.host : "null"; });
          prop("search", function () { return this._search; }, function (v) {
            v = String(v); this._search = v === "" || v === "?" ? "" : (v.charAt(0) === "?" ? v : "?" + v);
            if (this._sp) { this._sp._l = new SearchParams(this._search)._l; }
          });
          prop("hash", function () { return this._hash; }, function (v) { v = String(v); this._hash = v === "" || v === "#" ? "" : (v.charAt(0) === "#" ? v : "#" + v); });
          prop("searchParams", function () {
            if (!this._sp) { this._sp = new SearchParams(this._search); this._sp._u = this; }
            return this._sp;
          });
          prop("href", function () {
            var auth = this.username ? this.username + (this.password ? ":" + this.password : "") + "@" : "";
            return this.protocol + (this._special ? "//" + auth + this.host : "") + this.pathname + this._search + this._hash;
          }, function (v) { var m = SCHEME.exec(String(v)); if (!m) throw new TypeError("Invalid URL: " + v); this._parse(m[1].toLowerCase(), m[2]); });
          Url.prototype.toString = function () { return this.href; };
          Url.prototype.toJSON = function () { return this.href; };
          if (typeof g.URL !== "function") g.URL = Url;
          function utf8Bytes(str) {
            var out = [];
            for (var i = 0; i < str.length; i++) {
              var c = str.charCodeAt(i);
              if (c >= 55296 && c < 56320 && i + 1 < str.length) { c = 65536 + ((c - 55296) << 10) + (str.charCodeAt(++i) - 56320); }
              if (c < 128) out.push(c);
              else if (c < 2048) out.push(192 | c >> 6, 128 | c & 63);
              else if (c < 65536) out.push(224 | c >> 12, 128 | c >> 6 & 63, 128 | c & 63);
              else out.push(240 | c >> 18, 128 | c >> 12 & 63, 128 | c >> 6 & 63, 128 | c & 63);
            }
            return new Uint8Array(out);
          }
          if (typeof g.TextEncoder !== "function") g.TextEncoder = function () {};
          if (!g.TextEncoder.prototype.encode) g.TextEncoder.prototype.encode = function (s) { return utf8Bytes(String(s == null ? "" : s)); };
          // Node's Buffer, as far as plugins (and cheerio) use it: bytes from and to text in utf8, base64, hex, latin1.
          if (typeof g.Buffer !== "function") {
            var Buf = function (n) { var b = new Uint8Array(n); Object.setPrototypeOf(b, Buf.prototype); return b; };
            Buf.prototype = Object.create(Uint8Array.prototype);
            Buf.prototype.constructor = Buf;
            var wrap = function (u8) { var b = new Uint8Array(u8); Object.setPrototypeOf(b, Buf.prototype); return b; };
            Buf.isBuffer = function (x) { return x instanceof Buf; };
            Buf.alloc = function (n) { return Buf(n); };
            Buf.byteLength = function (s, e) { return Buf.from(s, e).length; };
            Buf.from = function (x, e) {
              if (x instanceof ArrayBuffer) return wrap(new Uint8Array(x));
              if (ArrayBuffer.isView(x)) return wrap(new Uint8Array(x.buffer, x.byteOffset, x.byteLength));
              if (Array.isArray(x)) return wrap(new Uint8Array(x));
              x = String(x); e = String(e || "utf8").toLowerCase();
              if (e === "base64" || e === "base64url") return wrap(fromB64(x.replace(/-/g, "+").replace(/_/g, "/")));
              if (e === "hex") { var h = new Uint8Array(x.length >> 1); for (var i = 0; i < h.length; i++) h[i] = parseInt(x.substr(i * 2, 2), 16); return wrap(h); }
              if (e === "latin1" || e === "binary" || e === "ascii") { var l = new Uint8Array(x.length); for (var j = 0; j < x.length; j++) l[j] = x.charCodeAt(j) & 255; return wrap(l); }
              return wrap(utf8Bytes(x));
            };
            Buf.concat = function (list) {
              var n = list.reduce(function (a, b) { return a + b.length; }, 0), out = Buf(n), at = 0;
              list.forEach(function (b) { out.set(b, at); at += b.length; });
              return out;
            };
            Buf.prototype.toString = function (e) {
              e = String(e || "utf8").toLowerCase();
              if (e === "base64") return toB64(this);
              if (e === "hex") return Array.prototype.map.call(this, function (b) { return ("0" + b.toString(16)).slice(-2); }).join("");
              if (e === "latin1" || e === "binary" || e === "ascii") { var s = ""; for (var i = 0; i < this.length; i++) s += String.fromCharCode(this[i]); return s; }
              return utf8(this);
            };
            Buf.prototype.slice = Buf.prototype.subarray = function (a, b) { return wrap(Uint8Array.prototype.slice.call(this, a, b)); };
            g.Buffer = Buf;
          }
          if (typeof g.TextDecoder !== "function") g.TextDecoder = function () {};
          if (!g.TextDecoder.prototype.decode) g.TextDecoder.prototype.decode = function (b) {
            if (!b) return "";
            return utf8(b instanceof ArrayBuffer ? new Uint8Array(b) : new Uint8Array(b.buffer, b.byteOffset, b.byteLength));
          };
          function Headers(list) {
            this._l = [];
            var self = this;
            if (Array.isArray(list)) list.forEach(function (p) { self._l.push([String(p[0]).toLowerCase(), String(p[1])]); });
            else if (list) for (var k in list) self._l.push([k.toLowerCase(), String(list[k])]);
          }
          Headers.prototype.get = function (n) {
            n = String(n).toLowerCase();
            var v = this._l.filter(function (p) { return p[0] === n; }).map(function (p) { return p[1]; });
            return v.length ? v.join(", ") : null;
          };
          Headers.prototype.has = function (n) { return this.get(n) !== null; };
          Headers.prototype.getSetCookie = function () {
            return this._l.filter(function (p) { return p[0] === "set-cookie"; }).map(function (p) { return p[1]; });
          };
          Headers.prototype.forEach = function (fn) { var self = this; this._l.forEach(function (p) { fn(p[1], p[0], self); }); };
          Headers.prototype.entries = function () { return this._l.slice()[Symbol.iterator](); };
          Headers.prototype.keys = function () { return this._l.map(function (p) { return p[0]; })[Symbol.iterator](); };
          Headers.prototype.values = function () { return this._l.map(function (p) { return p[1]; })[Symbol.iterator](); };
          Headers.prototype[Symbol.iterator] = Headers.prototype.entries;
          if (typeof g.Headers !== "function") g.Headers = Headers;
          function Response(m) {
            this._id = m.id; this.status = m.status; this.statusText = m.statusText; this.url = m.url;
            this.ok = m.status >= 200 && m.status < 300; this.redirected = false; this.type = "basic";
            this.headers = new Headers(m.headers);
          }
          Response.prototype.text = function () { return Promise.resolve(JSON.parse(__text(this._id))); };
          Response.prototype.json = function () { return this.text().then(function (t) { return JSON.parse(t); }); };
          Response.prototype.arrayBuffer = function () {
            var b = fromB64(__body64(this._id));
            return Promise.resolve(b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength));
          };
          Response.prototype.clone = function () { return this; };
          g.fetch = function (input, init) {
            init = init || {};
            var url = typeof input === "string" ? input : (input && input.url) || String(input);
            var headers = {}, h = init.headers;
            if (h) {
              if (Array.isArray(h)) h.forEach(function (p) { headers[p[0]] = String(p[1]); });
              else if (typeof h.forEach === "function") h.forEach(function (v, k) { headers[k] = String(v); });
              else for (var k in h) headers[k] = String(h[k]);
            }
            var body = init.body, text = null, b64 = null;
            if (body != null) {
              if (typeof body === "string") text = body;
              else if (body instanceof ArrayBuffer) b64 = toB64(new Uint8Array(body));
              else if (ArrayBuffer.isView(body)) b64 = toB64(new Uint8Array(body.buffer, body.byteOffset, body.byteLength));
              else text = String(body);
            }
            var req = { url: url, method: init.method || "GET", headers: headers, body: text, body64: b64, redirect: init.redirect || "follow" };
            return __fetch(g.__ascii(JSON.stringify(req))).then(function (s) {
              var m = JSON.parse(s);
              if (m.error) throw new TypeError("fetch failed: " + m.error);
              return new Response(m);
            });
          };
        })();
    """.trimIndent() + "\n"
}
