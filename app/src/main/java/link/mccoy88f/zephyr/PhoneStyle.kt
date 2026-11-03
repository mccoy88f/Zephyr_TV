package link.mccoy88f.zephyr

/** [s] safe inside HTML text and inside a quoted attribute. */
internal fun escHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** [s] safe inside a JavaScript string in single quotes, in a <script>. Line breaks become spaces. */
internal fun escJs(s: String) = s.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\x3c")
    .replace("\r", " ").replace("\n", " ").replace(" ", " ").replace(" ", " ")

/** Light, dark or glass, as the TV is set. Auto follows the same hours as the home screen. */
internal fun MainActivity.phoneTheme() = when (prefs.getString(K.THEME, "light")) {
    "dark" -> "dark"
    "glass" -> "glass"
    "auto" -> if (prefersDark(prefs)) "dark" else "light"
    else -> "light"
}

/** What every phone page shares, so the remote page and the pages of single settings look the same. */
internal object PhoneStyle {
    /** The mark from the app icon: a TV, without the word under it. */
    const val LOGO = """<svg class="logo" viewBox="0 0 72 72" aria-hidden="true"><defs><linearGradient id="lg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0F172A"/><stop offset="1" stop-color="#020617"/></linearGradient><linearGradient id="lf" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#38BDF8"/><stop offset="1" stop-color="#2563EB"/></linearGradient></defs><rect width="72" height="72" rx="16" fill="url(#lg)"/><circle cx="36" cy="12" r="1.7" fill="#38BDF8"/><path d="M36 13.8 V18" stroke="#38BDF8" stroke-width="1.6" stroke-linecap="round"/><rect x="14" y="18" width="44" height="28" rx="5" fill="none" stroke="url(#lf)" stroke-width="3"/><polygon points="31,25 31,39 45,32" fill="#fff"/></svg>"""

    /**
     * The colours of the TV's themes, on body.light, body.dark and body.glass. A page without a theme
     * (body.auto) follows the phone's own light or dark setting.
     */
    const val THEMES = """:root{--panel:#f7f8fa;--ground:#f3f5f7;--fg:#111214;--on:#fff;--muted:rgba(17,18,20,.6);--line:rgba(17,18,20,.12);--field:#e5e7eb;--fill:#111214}
      body.dark{--panel:#09090b;--ground:#000;--fg:#f5f5f7;--on:#111214;--muted:rgba(245,245,247,.6);--line:rgba(245,245,247,.16);--field:#2a2a32;--fill:#f5f5f7}
      @media (prefers-color-scheme:dark){body.auto{--panel:#09090b;--ground:#000;--fg:#f5f5f7;--on:#111214;--muted:rgba(245,245,247,.6);--line:rgba(245,245,247,.16);--field:#2a2a32;--fill:#f5f5f7}}
      body.glass{--panel:transparent;--ground:#000;--fg:#fff;--on:#111214;--muted:rgba(255,255,255,.6);--line:rgba(255,255,255,.16);--field:rgba(255,255,255,.08);--fill:#fff}"""
}
