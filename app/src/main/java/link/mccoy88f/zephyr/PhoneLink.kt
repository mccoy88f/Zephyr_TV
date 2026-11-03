package link.mccoy88f.zephyr

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * What the phone can do on one Zephyr TV screen: the [page] it opens, how much it may send ([maxBytes]), and
 * [onSend], which gets what it sent (as a temporary file, deleted afterwards) and returns the status code and the
 * text the phone shows. [onSend] runs on a server thread.
 */
internal class PhoneTask(val page: PhonePage, val maxBytes: Long, val onSend: (File) -> Pair<Int, String>) {
    /** A page of its own (read-only, nothing to do on the device itself: see PhoneLocal). */
    constructor(html: String, maxBytes: Long, onSend: (File) -> Pair<Int, String>) : this(PhonePage(html, PhoneLocal.View), maxBytes, onSend)
}

/** A phone page, and what it asks for, so a phone or tablet running Zephyr TV can ask for it itself (see Handheld). */
internal class PhonePage(val html: String, val local: PhoneLocal, val texts: PhoneTexts? = null)

/** What a phone page asks for: some text, a file, a form, or a file to save; View: only something to read. */
internal sealed class PhoneLocal {
    class Text(val hint: String, val secret: Boolean = false) : PhoneLocal()
    class Addon(val hint: String, val adultLabel: String) : PhoneLocal()
    class File(val accept: String) : PhoneLocal()
    class Download(val fileName: String, val content: String) : PhoneLocal()
    class Form(val fields: List<Triple<String, String, Pair<String, Boolean>>>) : PhoneLocal()
    object View : PhoneLocal()
}

/**
 * A small web server that lets a phone on the same Wi-Fi do something on the TV: send a wallpaper, type the TMDB
 * key, and whatever comes next. It runs only while its screen is open (call [stop] when it closes) and only
 * answers on /<token>, a random 144-bit path that's in the QR code, so nobody else on the network can guess it.
 * The page sends its data as the raw request body, so there's no multipart parsing. A few requests are served at
 * once, so one slow or silent connection can't hold up the phone.
 */
internal class PhoneLink(private val tempDir: File, private val task: PhoneTask) {
    val token: String = ByteArray(18).also { SecureRandom().nextBytes(it) }
        .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private val socket = try {
        ServerSocket(8080)
    } catch (e: IOException) {
        ServerSocket(0) // 8080 taken: any free port (it's in the QR code either way)
    }
    private val workers = Executors.newFixedThreadPool(4)
    val port get() = socket.localPort

    init {
        Thread(::serve, "phone-link").start()
    }

    fun url(ip: String) = "http://$ip:$port/$token"

    fun stop() {
        socket.close()
        workers.shutdownNow()
    }

    private fun serve() {
        while (!socket.isClosed) {
            val s = try {
                socket.accept()
            } catch (e: IOException) {
                return // closed
            }
            try {
                workers.execute {
                    try {
                        s.use { handle(it) }
                    } catch (e: Exception) {
                        warn("phone request failed ($e)")
                    }
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                s.close() // stopping
            }
        }
    }

    private fun handle(s: Socket) {
        s.soTimeout = 30_000
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        val request = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
            .take(100)
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val method = request.substringBefore(' ')
        val path = request.substringAfter(' ').substringBefore(' ').substringBefore('?')
        when {
            path != "/$token" -> reply(out, 404, "text/plain; charset=utf-8", "Not found. Scan the code on the TV again.")
            method == "GET" -> reply(out, 200, "text/html; charset=utf-8", task.page.html)
            method == "POST" -> {
                val length = headers["content-length"]?.toLongOrNull() ?: -1
                if (length !in 1..task.maxBytes) return reply(out, 413, "text/plain; charset=utf-8", "Too big.")
                val temp = File.createTempFile("phone", ".part", tempDir)
                try {
                    temp.outputStream().use { file ->
                        val buf = ByteArray(64 * 1024)
                        var left = length
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw IOException("upload cut short")
                            file.write(buf, 0, n)
                            left -= n
                        }
                    }
                    val (status, text) = task.onSend(temp)
                    reply(out, status, "text/plain; charset=utf-8", text)
                } finally {
                    temp.delete()
                }
            }
            else -> reply(out, 405, "text/plain; charset=utf-8", "Not allowed")
        }
    }

    /** One header line, without the line break; null at the end of the stream. Capped so junk can't fill memory. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        throw IOException("header line too long")
    }

    private fun reply(out: OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        out.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }
}

/**
 * The texts of a phone page, in the TV's language. [footer] goes at the bottom: which TV the page belongs to
 * ("Zephyr TV - TV del salotto"). [theme] is the TV's theme (see phoneTheme); empty follows the phone's.
 */
internal class PhoneTexts(
    val title: String, val intro: String, val send: String, val sending: String, val unreachable: String,
    val footer: String = "", val theme: String = "",
)

/** The pages the phone opens. They post to their own address, so the token never has to be in the page itself. */
internal object PhonePages {
    private fun esc(s: String) = escHtml(s)
    private fun js(s: String) = escJs(s)

    /** The page around [body], in the TV's theme, with the app's mark at the top as on the remote page. */
    private fun frame(t: PhoneTexts, body: String, script: String) = """
        <!doctype html>
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${esc(t.title)}</title>
        <style>
          ${PhoneStyle.THEMES}
          *{box-sizing:border-box}
          body{font:17px system-ui,sans-serif;margin:0;background:var(--ground);color:var(--fg)}
          .sheet{max-width:420px;min-height:100vh;margin:0 auto;padding:28px 20px 32px;background:var(--panel)}
          body.glass .sheet{background:linear-gradient(180deg,rgba(42,42,50,.85),rgba(16,16,20,.9))}
          .logo{width:56px;height:56px;display:block;margin:0 auto 16px}
          h1{font:500 22px system-ui,sans-serif;margin:0 0 8px;text-align:center} p{color:var(--muted);margin:0 0 24px}
          label,button,textarea{display:block;width:100%;padding:16px;border-radius:8px;font:inherit;margin-bottom:12px}
          label{border:1.5px dashed var(--line);cursor:pointer;text-align:center}
          button{border:0;background:var(--fill);color:var(--on);font-weight:500}
          textarea{border:0;background:var(--field);color:inherit;min-height:6em;font-family:ui-monospace,monospace}
          button:disabled{opacity:.35} input{display:none} #s{min-height:1.5em}
          ol{padding-left:20px;font:14px ui-monospace,monospace} li{margin:0 0 10px;word-break:break-word}
          footer{margin-top:40px;font-size:13px;color:var(--muted);text-align:center}
        </style></head><body class="${if (t.theme.isEmpty()) "auto" else esc(t.theme)}"><div class="sheet">
        ${PhoneStyle.LOGO}
        <h1>${esc(t.title)}</h1>
        ${if (t.intro.isEmpty()) "" else "<p>${esc(t.intro)}</p>"}
        $body
        <div id="s"></div>
        ${if (t.footer.isEmpty()) "" else "<footer>${esc(t.footer)}</footer>"}
        </div>
        <script>
          const b=document.getElementById('b'),s=document.getElementById('s');
          async function send(data){
            b.disabled=true;s.textContent='${js(t.sending)}';
            try{const r=await fetch(location.pathname,{method:'POST',body:data});s.textContent=await r.text();}
            catch(e){s.textContent='${js(t.unreachable)}';}
            b.disabled=false;
          }
          $script
        </script></body></html>
    """.trimIndent()

    /** Something to read only: [body] is its HTML, already escaped. */
    fun view(t: PhoneTexts, body: String) = PhonePage(frame(t, body, ""), PhoneLocal.View, t)

    /** Pick a file (of [accept] types) and send it. */
    fun file(t: PhoneTexts, choose: String, accept: String) = PhonePage(frame(t,
        """<label><input id="f" type="file" accept="${esc(accept)}"><span id="n">${esc(choose)}</span></label>
        <button id="b" disabled>${esc(t.send)}</button>""",
        """const f=document.getElementById('f'),n=document.getElementById('n');
        f.onchange=()=>{b.disabled=!f.files.length;n.textContent=f.files.length?f.files[0].name:'${js(choose)}';s.textContent=''};
        b.onclick=()=>send(f.files[0]);"""), PhoneLocal.File(accept), t)

    /** Shows a button that saves [content] on the phone as [fileName]; nothing goes back to the TV. */
    /** [t].send is the button's text. */
    fun download(t: PhoneTexts, fileName: String, content: String): PhonePage {
        val data = content.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\x3c").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return PhonePage(frame(t,
            """<button id="b">${esc(t.send)}</button>""",
            """b.onclick=()=>{const u=URL.createObjectURL(new Blob(['$data'],{type:'application/json'}));
            const a=document.createElement('a');a.href=u;a.download='${js(fileName)}';document.body.appendChild(a);a.click();a.remove();};"""),
            PhoneLocal.Download(fileName, content), t)
    }

    /** Type or paste some text and send it. [secret]: hide it (a cookie, a key). */
    fun text(t: PhoneTexts, hint: String, secret: Boolean = false) = PhonePage(frame(t,
        """<textarea id="t" placeholder="${esc(hint)}" autocapitalize="off" autocomplete="off" spellcheck="false"${if (secret) " style=\"-webkit-text-security:disc\"" else ""}></textarea>
        <button id="b" disabled>${esc(t.send)}</button>""",
        """const x=document.getElementById('t');
        x.oninput=()=>{b.disabled=!x.value.trim();s.textContent=''};
        b.onclick=()=>send(x.value.trim());"""), PhoneLocal.Text(hint, secret), t)

    /**
     * Several fields at once ([fields]: key, label, placeholder, secret), sent as a JSON object: an account's server,
     * user and password typed on a phone in one go.
     */
    fun form(t: PhoneTexts, fields: List<Triple<String, String, Pair<String, Boolean>>>) = PhonePage(frame(t,
        fields.joinToString("") { (key, label, more) ->
            """<p style="margin:0 0 6px;opacity:1">${esc(label)}</p><textarea data-k="${esc(key)}" rows="1" placeholder="${esc(more.first)}"
            style="min-height:2.6em${if (more.second) ";-webkit-text-security:disc" else ""}" autocapitalize="off" autocomplete="off" spellcheck="false"></textarea>"""
        } + """<button id="b">${esc(t.send)}</button>""",
        """const xs=[...document.querySelectorAll('textarea')];
        b.onclick=()=>{const o={};xs.forEach(x=>{if(x.value.trim())o[x.dataset.k]=x.value.trim()});send(JSON.stringify(o));};"""), PhoneLocal.Form(fields), t)

    /** Add a Stremio add-on, with a checkbox for adult content (18+). */
    fun addon(t: PhoneTexts, hint: String, adultLabel: String) = PhonePage(frame(t,
        """<textarea id="t" placeholder="${esc(hint)}" autocapitalize="off" autocomplete="off" spellcheck="false"></textarea>
        <label style="display:flex;align-items:center;gap:12px;cursor:pointer;border:0;padding:8px 0;margin-bottom:16px;text-align:left">
          <input id="a" type="checkbox" style="display:inline;width:20px;height:20px;accent-color:currentColor"> <span>${esc(adultLabel)}</span>
        </label>
        <button id="b" disabled>${esc(t.send)}</button>""",
        """const x=document.getElementById('t'),a=document.getElementById('a');
        x.oninput=()=>{b.disabled=!x.value.trim();s.textContent=''};
        b.onclick=()=>send(x.value.trim()+'\n'+(a.checked?'adult':''));"""), PhoneLocal.Addon(hint, adultLabel), t)
}
