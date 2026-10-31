package link.mccoy88f.zephyr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import link.mccoy88f.zephyr.MainActivity.Companion.CHOSEN
import link.mccoy88f.zephyr.MainActivity.Companion.MIC_REQUEST
import link.mccoy88f.zephyr.MainActivity.Prog
import java.io.IOException

// Searching from the top bar (films and series in Cinemeta and the add-ons, TV channels): typed with the remote, dictated, or typed on a phone. The results get a tile of
// their own in the apps row, with their cards under it; Spotlight is left alone.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

private var searchFiltersExpanded = false

/**
 * The search page: a box to type in with the remote, the filters (films or series, year, genre: Search finds
 * with them alone too, without words), dictation into that box, the TV's own assistant, and a QR code to type on a
 * phone instead. [text]: what's in the box (the page built again after a filter changed); [focus]: the row to focus.
 */
internal fun MainActivity.openSearch(text: String = "", focus: Int? = null, voice: Boolean = false) {
    if (focus == null && text.isEmpty()) searchFiltersExpanded = searchFilter.any
    if (settingsScrim.visibility != View.VISIBLE) showPanel()
    // The keyboard's Search wants words or a filter; the Search row goes without (Cinemeta's popular ones).
    fun go(query: String, always: Boolean = false) {
        if (query.isBlank() && !searchFilter.any && !always) return
        closeLauncherSettings()
        searchTmdb(query.trim())
    }
    val input = EditText(this).apply {
        id = View.generateViewId()
        hint = getString(R.string.search_hint)
        textSize = 14f
        setTextColor(fg)
        setHintTextColor(Color.argb(120, Color.red(fg), Color.green(fg), Color.blue(fg)))
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setPadding(dp(14), 0, dp(14), 0)
        val bg = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(field)
            setStroke(dp(1), fieldEdge())
        }
        background = bg
        setOnFocusChangeListener { _, focused -> bg.setStroke(if (focused) dp(2) else dp(1), if (focused) fg else fieldEdge()) }
        setOnEditorActionListener { v, _, _ -> go(v.text.toString()); true }
        setText(text)
        setSelection(text.length)
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, 0)
        addView(input, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
    }
    val f = searchFilter
    val summary = if (!f.any) getString(R.string.search_filter_any)
        else listOfNotNull(if (f.type != "all") typeName(f.type) else null, f.year, f.genre?.let { genreName(it) },
            f.director?.let { "${getString(R.string.search_filter_director)}: $it" },
            f.actor?.let { "${getString(R.string.search_filter_actor)}: $it" },
            f.adult.takeIf { it != SearchFilter.ADULT_SHOW && adultSearchOffered() }?.let { adultFilterName(it) }).joinToString(" · ")
    fun typed() = input.text?.toString().orEmpty()
    val filterRows = if (!searchFiltersExpanded) emptyList() else listOfNotNull(
        page(getString(R.string.search_filter_type), typeName(f.type)) { pickType(typed()) },
        page(getString(R.string.search_filter_year), f.year ?: getString(R.string.search_filter_any)) { pickYear(typed()) },
        page(getString(R.string.search_filter_genre), f.genre?.let { genreName(it) } ?: getString(R.string.search_filter_any)) { pickGenre(typed()) },
        page(getString(R.string.search_filter_director), f.director ?: getString(R.string.search_filter_any)) { pickPersonFilter(isDirector = true, typed()) },
        page(getString(R.string.search_filter_actor), f.actor ?: getString(R.string.search_filter_any)) { pickPersonFilter(isDirector = false, typed()) },
        // Adult content: only while 18+ shows (unlocked, or no lock) and there's an adult add-on.
        if (!adultSearchOffered()) null else page(getString(R.string.adult_app), adultFilterName(f.adult)) {
            val modes = listOf(SearchFilter.ADULT_SHOW, SearchFilter.ADULT_HIDE, SearchFilter.ADULT_ONLY)
            searchFilter = SearchFilter(f.type, f.year, f.genre, modes[(modes.indexOf(f.adult) + 1) % modes.size], f.director, f.actor)
            openSearch(typed(), 8)
        },
        if (!f.any) null else page(getString(R.string.search_filter_clear)) {
            searchFilter = SearchFilter()
            openSearch(typed(), 2)
        },
    )
    val rows = listOf(
        // Search: with words, or with the filters alone (all of Cinemeta's popular ones, without either).
        page(getString(R.string.search_go)) { go(typed(), always = true) },
        if (needsFireTvVoiceHelp()) page(getString(R.string.firetv_voice_action)) { closeLauncherSettings(); assistant() }
        else page(getString(R.string.search_dictate)) { listen(input, ::go) },
        page(getString(R.string.search_filters), if (searchFiltersExpanded) "▲ $summary" else "▼ $summary") {
            searchFiltersExpanded = !searchFiltersExpanded
            openSearch(typed(), 2)
        },
    ) + filterRows + if (needsFireTvVoiceHelp()) emptyList() else listOf(
        page(getString(R.string.search_assistant)) { closeLauncherSettings(); assistant() },
    )
    showPage(getString(R.string.search_title), rows, focus ?: 0, top = top) { closeLauncherSettings() }
    val first = settingsList.getChildAt(1)
    first.nextFocusUpId = input.id // showPage pins Up on the first row to itself; here it goes to the box
    // The TV keyboard keeps Down for itself while the box has focus, even with the keyboard put away.
    input.setOnKeyListener { _, key, e ->
        (key == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN).also { if (it) first.requestFocus() }
    }
    if (focus == null) input.requestFocus()
    pageExit = { stopListening() }
    if (voice) ui.post {
        if (needsFireTvVoiceHelp()) openFireTvVoiceHelp(typed())
        else if (SpeechRecognizer.isRecognitionAvailable(this)) listen(input, ::go)
        else Toast.makeText(this, getString(R.string.firetv_voice_unavailable), Toast.LENGTH_LONG).show()
    }
    // Under the rows, the phone: the same search typed on its own keyboard. The link works while the page is open.
    if (handheld()) return // a phone or tablet: its own keyboard is the one in the box
    val ip = lanAddress() ?: return
    val page = PhonePages.text(phoneTexts(R.string.phone_search_title, R.string.phone_search_intro), getString(R.string.search_hint))
    lateinit var link: PhoneLink // the task below checks it's still this page's
    link = try {
        PhoneLink(cacheDir, PhoneTask(page, 1024) { file ->
            val query = file.readText().trim().take(200)
            if (query.isEmpty()) return@PhoneTask 400 to getString(R.string.search_hint)
            ui.postDelayed({ if (upload === link) go(query) }, 300) // once the phone has its answer: closing the page stops the server
            200 to getString(R.string.phone_search_sent, query)
        })
    } catch (e: IOException) {
        return
    }
    upload = link
    pageExit = {
        stopListening()
        link.stop()
        if (upload === link) upload = null
    }
    val qrCard = FrameLayout(this).apply {
        id = View.generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        val bg = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.TRANSPARENT)
        }
        background = bg
        val img = ImageView(this@openSearch).apply {
            setImageBitmap(qrBitmap(link.url(ip), dp(160)))
        }
        addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(6), dp(6), dp(6), dp(6))
        })
        setOnFocusChangeListener { _, focused ->
            bg.setColor(if (focused) (0x22000000 or (fg and 0x00FFFFFF)) else Color.TRANSPARENT)
            bg.setStroke(if (focused) dp(2) else 0, if (focused) fg else Color.TRANSPARENT)
            if (focused) (settingsList.parent as? android.widget.ScrollView)?.smoothScrollTo(0, bottom + dp(40))
        }
    }
    settingsList.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(16), 0, dp(16))
        addView(label(getString(R.string.search_phone), 12f).apply { gravity = Gravity.CENTER; alpha = 0.6f; maxLines = 2 })
        addView(qrCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    })
    val lastRow = settingsList.getChildAt(rows.size)
    lastRow?.nextFocusDownId = qrCard.id
    qrCard.nextFocusUpId = lastRow?.id ?: View.NO_ID
    qrCard.nextFocusDownId = qrCard.id
}

/**
 * Dictation into the search box, without leaving the page: what's heard so far shows in [input], and the search
 * starts once it's said. (The TV's own dictation screen hears it but, on some TVs, never hands the words back.)
 */
internal fun MainActivity.listen(input: EditText, go: (String) -> Unit) {
    if (needsFireTvVoiceHelp()) { openFireTvVoiceHelp(input.text.toString()); return }
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        afterMicAccess = { listen(input, go) }
        return requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
    }
    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
        closeLauncherSettings()
        return assistant()
    }
    stopListening()
    val heard = SpeechRecognizer.createSpeechRecognizer(this)
    recognizer = heard
    input.setText("")
    input.hint = getString(R.string.search_listening)
    fun words(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
    heard.setRecognitionListener(object : RecognitionListener {
        override fun onPartialResults(b: Bundle?) {
            words(b)?.let {
                input.setText(it)
                input.setSelection(it.length)
            }
        }

        override fun onResults(b: Bundle?) {
            stopListening()
            val said = words(b)
            input.hint = getString(R.string.search_hint)
            if (said == null) return
            // What was heard, in the box, a moment before the search starts: some TVs give no words until the end.
            input.setText(said)
            input.setSelection(said.length)
            ui.postDelayed({ if (input.isAttachedToWindow) acceptVoiceWords(said, go) }, HEARD_MS)
        }

        override fun onError(error: Int) {
            stopListening()
            input.hint = getString(R.string.search_not_heard)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    heard.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tmdbLang())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
}

internal fun MainActivity.stopListening() {
    recognizer?.destroy()
    recognizer = null
}

/**
 * Looks [query] up: the films and series in Cinemeta and the add-ons' catalogs (TMDB, with its key, finding them by
 * their names in the TV's language too: see searchLibrary), and the TV channels. The results get a tile after
 * Continue Watching, focused at once, and their cards show under it. Back, or holding OK on the tile, closes it (endSearch).
 */
internal fun MainActivity.searchTmdb(query: String) {
    val filter = searchFilter
    // What the results are for: the words, else the filters ("2020 · Drama"), else Cinemeta's popular ones.
    // With words and filters, both: the filters stay until they're cleared, and say so.
    val label = if (query.isBlank()) filterLabel(filter) else listOfNotNull(query, filterLabel(filter).takeIf { filter.any }).joinToString(" · ")
    // Its tile at once, focused, with its words over cards loading; the results fill it once they're in.
    val n = ++searchCount
    searchLoading = n
    lastSearchWord = query
    searchQuery = label
    searchChannels = emptyList()
    searchTitles = emptyList()
    startYouTubeSearch(query, n)
    watchApp = null
    renderApps()
    (0 until appRow.childCount).map { appRow.getChildAt(it) }.firstOrNull { it.tag == SEARCH_PKG }?.requestFocus()
    spotlightIo.execute {
        // TV channels and radio channels
        val channels = when {
            filter.type in setOf("all", "tv", "radio") -> runCatching {
                val all = if (query.isBlank()) {
                    if (filter.type == "radio") findAddonChannels("").filter { isRadioChannel(it) }
                    else emptyList()
                } else findChannels(query)
                when (filter.type) {
                    "radio" -> all.filter { isRadioChannel(it) }
                    "tv" -> all.filter { !isRadioChannel(it) }
                    else -> all
                }
            }.getOrDefault(emptyList())
            else -> emptyList()
        }
        val titles = runCatching {
            when {
                filter.type in setOf("tv", "radio") -> emptyList()
                query.isBlank() -> {
                    when {
                        filter.director != null -> searchPersonTitles(filter.director, isDirector = true, filter = filter)
                        filter.actor != null -> searchPersonTitles(filter.actor, isDirector = false, filter = filter)
                        adultSearch() == SearchFilter.ADULT_ONLY -> browseAdult(filter)
                        adultSearch() == SearchFilter.ADULT_HIDE -> browseLibrary(filter) + browseCollectors(filter)
                        else -> browseLibrary(filter) + browseCollectors(filter) + browseAdult(filter)
                    }
                }
                else -> {
                    val searched = when {
                        adultSearch() == SearchFilter.ADULT_ONLY -> emptyList()
                        filter.director != null -> searchPersonTitles(filter.director, isDirector = true, filter = filter)
                            .filter { (t, _) -> t.name.contains(query, ignoreCase = true) }
                        filter.actor != null -> searchPersonTitles(filter.actor, isDirector = false, filter = filter)
                            .filter { (t, _) -> t.name.contains(query, ignoreCase = true) }
                        else -> searchLibrary(query)
                    }
                    val fromShelves = if (filter.director != null || filter.actor != null) emptyList() else localizeAll(searchShelves(query, filter))
                    (searched + fromShelves).filter { (t, _) -> filter.accepts(t) { g -> genreName(g) } }
                }
            }
        }.getOrDefault(emptyList()).distinctBy { it.first.type + it.first.id }
        // Adult content kept, left out, or alone (by the add-on each came from: Stremio's catalogs don't tell a title's age).
        fun keep(adult: Boolean) = when (adultSearch()) { SearchFilter.ADULT_HIDE -> !adult; SearchFilter.ADULT_ONLY -> adult; else -> true }
        val shownChannels = channels.filter { keep(it.addon?.let { c -> isAdultAddon(c.addon) } == true) }
        val shownTitles = titles.filter { (_, source) -> keep(isAdultAddon(source)) }
        ui.post {
            if (searchLoading != n) return@post // closed, or another search started meanwhile
            searchLoading = 0
            if (shownChannels.isEmpty() && shownTitles.isEmpty() && youtubeQuery == null) {
                endSearch()
                return@post Toast.makeText(this, getString(R.string.toast_no_results, label), Toast.LENGTH_SHORT).show()
            }
            searchChannels = shownChannels
            searchTitles = shownTitles
            // Its cards in place of the loading ones, where the tile (or a card under it) still has focus.
            if (watchApp == SEARCH_PKG || currentFocus?.tag == SEARCH_PKG) {
                watchApp = null
                showSearchResults()
            }
            val found = shownChannels.size + shownTitles.size
            Toast.makeText(this, resources.getQuantityString(R.plurals.toast_search_results, found, found, label), Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * The results' cards under the apps row, a row each: the films, the series, the other collectors,
 * adult content, then the TV channels it found.
 */
internal fun MainActivity.showSearchResults() {
    val query = searchQuery ?: return
    watchApp = SEARCH_PKG
    if (searchLoading != 0) return showSearchLoading(query)

    val adultTitles = searchTitles.filter { isAdultAddon(it.second) }
    val regularTitles = searchTitles.filter { !isAdultAddon(it.second) }

    val adultChannels = searchChannels.filter { it.addon?.let { c -> isAdultAddon(c.addon) } == true }
    val regularChannels = searchChannels.filter { it.addon?.let { c -> isAdultAddon(c.addon) } != true }

    val watched = VodWatched.all(vodDir())

    val filmsSection = regularTitles.filter { collectorOf(it.first.type) == "movies" }.takeIf { it.isNotEmpty() }?.let { found ->
        Section("addon-films", query, getString(R.string.search_films_for, query), found.map { (t, source) ->
            titleCard(t, source, titleTile(t.type, source), t.id in watched)
        })
    }
    val seriesSection = regularTitles.filter { collectorOf(it.first.type) == "series" }.takeIf { it.isNotEmpty() }?.let { found ->
        Section("addon-series", query, getString(R.string.search_series_for, query), found.map { (t, source) ->
            titleCard(t, source, titleTile(t.type, source), t.id in watched)
        })
    }
    val collectorOrder = listOf("videos", "podcasts", "courses", "audiobooks", "events")
    val collectorSections = collectorOrder.mapNotNull { kind ->
        regularTitles.filter { collectorOf(it.first.type) == kind }.takeIf { it.isNotEmpty() }?.let { found ->
            val heading = "${kindName(kind)} · $query"
            Section("addon-$kind", query, heading, found.map { (t, source) ->
                titleCard(t, source, titleTile(t.type, source), t.id in watched)
            })
        }
    }
    val adultSection = adultTitles.takeIf { it.isNotEmpty() }?.let { found ->
        Section("addon-adult", query, getString(R.string.search_adult_for, query), found.map { (t, source) ->
            titleCard(t, source, titleTile(t.type, source), t.id in watched)
        })
    }
    val tvChannels = regularChannels.filter { !isRadioChannel(it) }.takeIf { it.isNotEmpty() }?.let { list ->
        Section("channels", query, getString(R.string.search_channels_for, query), list.map { d ->
            Prog(d.name, listOfNotNull(d.number, d.now?.title).filter { it.isNotEmpty() }.joinToString(" · "), d.now?.let { Epg.progress(it) } ?: 0,
                d.logo, d.intent, d.pkg, badge = d.number, channelPage = d)
        })
    }
    val radioChannels = (regularChannels + adultChannels).filter { isRadioChannel(it) }.takeIf { it.isNotEmpty() }?.let { list ->
        val heading = "${getString(R.string.ext_kind_radio)} · $query"
        Section("radio", query, heading, list.map { d ->
            Prog(d.name, listOfNotNull(d.number, d.now?.title).filter { it.isNotEmpty() }.joinToString(" · "), d.now?.let { Epg.progress(it) } ?: 0,
                d.logo, d.intent, d.pkg, badge = d.number, channelPage = d)
        })
    }
    val adultChannelsSection = adultChannels.filter { !isRadioChannel(it) }.takeIf { it.isNotEmpty() }?.let { list ->
        Section("adult-channels", query, "${getString(R.string.adult_app)} · ${getString(R.string.search_type_channels)}", list.map { d ->
            Prog(d.name, listOfNotNull(d.number, d.now?.title).filter { it.isNotEmpty() }.joinToString(" · "), d.now?.let { Epg.progress(it) } ?: 0,
                d.logo, d.intent, d.pkg, badge = d.number, channelPage = d)
        })
    }

    showSections(listOfNotNull(
        filmsSection,
        seriesSection,
        youTubeSearchSection(),
    ) + collectorSections + listOfNotNull(
        adultSection,
        tvChannels,
        radioChannels,
        adultChannelsSection,
    ))
}

/** The search's words over a row of cards loading, while its results come (see searchTmdb). */
private fun MainActivity.showSearchLoading(query: String) {
    val key = "loading:$SEARCH_PKG/$query"
    if (watchKey == key) return
    watchKey = key
    watchRow.removeAllViews()
    moreSections.removeAllViews()
    watchTitle.visibility = View.VISIBLE
    watchScroll.visibility = View.VISIBLE
    watchTitle.text = getString(R.string.search_loading_for, query)
    repeat(4) { watchRow.addView(placeholderCard(it > 0, it * 120L)) }
}

/** How long what was dictated shows in the box before the search starts. */
private const val HEARD_MS = 700L

/** A TV channel the search found: what its card and its page show. */
internal class ChannelDetail(
    val name: String, val number: String, val logo: String?, val intent: String, val pkg: String?,
    val now: Epg.Show?, val next: Epg.Show?,
    val addon: AddonChannel? = null, // a Stremio add-on's channel: Watch plays it in Zephyr TV's player
)

/**
 * The tuner channels whose name has [query] in it, or whose number it is, with what's on (the TV's guide, else the
 * one set in Zephyr TV settings). At most 20. Runs off the UI thread.
 */
internal fun MainActivity.findChannels(query: String): List<ChannelDetail> {
    val words = ChannelLogos.normalize(query).takeIf { it.length >= 2 } ?: return emptyList()
    val number = query.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.trimStart('0')
    // The tuner's channels, then the Stremio add-ons' (see Addons): one row, the same cards.
    val tuner = if (checkSelfPermission(MainActivity.TV_LISTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) emptyList()
        else channelDetails(tunerChannels()
            .filter { ChannelLogos.normalize(it.name).contains(words) || (number != null && it.number.trimStart('0') == number) }
            .sortedBy { channelKey(it.number) }.take(20))
    return (tuner + findAddonChannels(words)).take(40)
}

/** What [channels]' pages show: logo, what's on now and next (the guide set in Zephyr TV settings, else the TV's). Off the UI thread. */
internal fun MainActivity.channelDetails(channels: List<TunerChannel>): List<ChannelDetail> {
    val tvApp = apps.firstOrNull { isTvApp(it.pkg) }?.pkg
    val logos = if (channelLogosOn()) ChannelLogos.saved(filesDir, channelCountry()).orEmpty() else emptyMap()
    val guide = epgUrl()?.let { Epg.saved(filesDir, it) }
    val onNow = programsOnNow()
    return channels.map { ch ->
        val epg = guide?.let { Epg.nowAndNext(it, ch.name) }
        val now = epg?.first ?: onNow[ch.id]?.let { Epg.Show(0, 0, it.title) }
        ChannelDetail(ch.name, ch.number.trimStart('0').ifEmpty { ch.number }, channelLogo(ch, logos),
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUri(ch.id)).toUri(android.content.Intent.URI_INTENT_SCHEME),
            tvApp, now, epg?.second)
    }
}

/** Hold OK on a channel card, Details: the channel's page, as search shows it. */
internal fun MainActivity.showChannelDetails(id: Long) = io.execute {
    val d = tunerChannels().firstOrNull { it.id == id }?.let { channelDetails(listOf(it)).first() } ?: return@execute
    ui.post { showChannel(d) }
}

/**
 * A channel's page, as a whole page like a film's (see DetailPage): its logo large on the right (on a blur of itself), what's on with
 * its time and what it's about, what follows, and Watch on TV.
 */
internal fun MainActivity.showChannel(d: ChannelDetail) {
    if (settingsScrim.visibility != View.VISIBLE) showPanel()
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    fun hours(s: Epg.Show) = if (s.start > 0) "${time.format(java.util.Date(s.start))} – ${time.format(java.util.Date(s.stop))}" else null
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), 0, dp(14), dp(12)) // in line with the page title and the rows' names
        if (d.number.isNotEmpty()) addView(label(getString(R.string.channel_number, d.number), 13f).apply { alpha = 0.7f })
        d.now?.let { now ->
            addView(label(now.title, 16f).apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 2
                setPadding(0, dp(10), 0, 0)
            })
            hours(now)?.let { addView(label(it, 13f).apply { alpha = 0.7f; setPadding(0, dp(2), 0, 0) }) }
            if (now.desc.isNotBlank()) addView(label(now.desc, 13f).apply {
                maxLines = 8
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(8), 0, 0)
            })
        }
        d.next?.let { next ->
            addView(label(getString(R.string.channel_next, hours(next)?.substringBefore(" –") ?: "", next.title), 13f).apply {
                alpha = 0.7f
                maxLines = 2
                setPadding(0, dp(10), 0, 0)
            })
        }
    }
    // An add-on's channel: Watch on the tile it shows under (its own app, the IPTV app, the TV app), with its icon.
    val tile = d.addon?.let { addonTile(it) }
    val watch = tile?.let { getString(R.string.tmdb_search_on, it.label) } ?: getString(if (d.addon != null) R.string.channel_play else R.string.channel_watch)
    val rows = listOf(page(watch) {
        // The page stays behind the player (or the TV's app): back from it, the page again, focus on Watch.
        pageReturn = { showChannel(d) }
        val p = Prog(d.name, d.number, 0, null, d.intent, d.pkg, addon = d.addon)
        if (d.addon != null) playAddonChannel(p) else open(p)
    })
    showPage(d.name, rows, top = top, back = { closeLauncherSettings() }, panes = glass)
    // A whole page: the channel's logo large on the right half, on a blur of itself (see DetailPage).
    detailPage("channel|${d.name}|${d.number}", emptyList()) { frame, w, h ->
        val half = FrameLayout(this)
        frame.addView(half, FrameLayout.LayoutParams(w / 2, h, android.view.Gravity.END))
        channelArt(half, w / 2, h, d.number, d.name) {
            listOfNotNull(d.logo, d.addon?.let { ChannelLogos.match(channelLogoTable(), d.name) })
        }
    }
    val icon = (d.pkg ?: tile?.pkg?.takeIf { !it.startsWith(STREMIO) })?.let { pkg -> runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() }
        ?: tile?.icon?.constantState?.newDrawable(resources)?.mutate()
    if (icon != null) {
        val row = settingsList.getChildAt(1) as? LinearLayout
        val iv = ImageView(this).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (icon !is android.graphics.drawable.BitmapDrawable) {
                setColorFilter(fg, android.graphics.PorterDuff.Mode.SRC_IN)
            }
        }
        row?.addView(iv, 0, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
        val orig = row?.onFocusChangeListener
        row?.setOnFocusChangeListener { v, focused ->
            orig?.onFocusChange(v, focused)
            if (icon !is android.graphics.drawable.BitmapDrawable) {
                iv.setColorFilter(if (focused && !glass) onFg else fg, android.graphics.PorterDuff.Mode.SRC_IN)
            }
        }
    }
}

/** Closes the search: its tile and cards go, focus goes back to Continue Watching. False when none is open. */
internal fun MainActivity.endSearch(): Boolean {
    if (searchQuery == null) return false
    val onIt = currentFocus?.let { it.tag == SEARCH_PKG || (it.getTag(R.id.watchRow) as? Prog)?.let { p -> p.channelPage != null || p.vod != null } == true } ?: true
    searchQuery = null
    lastSearchWord = ""
    searchFilter = SearchFilter()
    searchLoading = 0
    searchChannels = emptyList()
    searchTitles = emptyList()
    youtubeCancellation?.cancel()
    youtubeQuery = null
    youtubePackage = null
    youtubeVideos = emptyList()
    youtubeLoading = false
    if (watchApp == SEARCH_PKG) watchApp = null
    renderApps()
    if (onIt) appRow.getChildAt(0)?.requestFocus()
    return true
}

// ---------- filters ----------

/** The search's kind of content: all, films, TV series, TV channels, or generic collectors (radio, videos, podcasts...). */
private fun MainActivity.typeName(type: String) = when (type) {
    "all" -> getString(R.string.search_type_all)
    "movie" -> getString(R.string.vod_movies)
    "series" -> getString(R.string.vod_series)
    "tv" -> getString(R.string.search_type_channels)
    else -> kindName(type)
}

internal fun MainActivity.availableFilterTypes(): List<String> {
    val types = mutableListOf("all", "movie", "series", "tv")
    val found = shownAddonUrls().flatMap { url ->
        val a = Stremio.saved(filesDir, url)?.addon
        val fromAddon = (a?.catalogs.orEmpty() + a?.vod.orEmpty()).map { collectorOf(it.type) }
        val fromExt = ExtensionCalls.idOf(url)?.let { id -> Extensions.installed(filesDir, id)?.collectors }.orEmpty()
        fromAddon + fromExt
    }.toSet()
    val collectors = listOf("radio", "videos", "podcasts", "courses", "audiobooks", "events")
    for (c in collectors) {
        if (c in found || (c == "radio" && radioPlace() != RadioPlace.OFF)) {
            types.add(c)
        }
    }
    val f = searchFilter.type
    if (f !in types) types.add(f)
    return types
}

private fun MainActivity.pickType(text: String) {
    val types = availableFilterTypes()
    val f = searchFilter
    showPage(getString(R.string.search_filter_type), types.map { t ->
        page(typeName(t), if (t == f.type) CHOSEN else "") {
            searchFilter = SearchFilter(t, f.year, f.genre, f.adult, f.director, f.actor)
            openSearch(text, 3)
        }
    }, types.indexOf(f.type).coerceAtLeast(0)) { openSearch(text, 3) }
}

private fun MainActivity.isRadioChannel(d: ChannelDetail): Boolean =
    SearchChannelKind.isRadio(d.addon?.item?.type, COLLECTOR_PKGS[d.pkg])

private fun MainActivity.browseCollectors(filter: SearchFilter): List<Pair<Stremio.Title, String>> {
    val collectors = if (filter.type == "all") listOf("videos", "podcasts", "courses", "audiobooks", "events")
    else listOf(collectorOf(filter.type)).filter { it in listOf("videos", "podcasts", "courses", "audiobooks", "events") }
    if (collectors.isEmpty()) return emptyList()
    val addons = vodAddons().filter { !isAdultAddon(it.url) }
    return addons.flatMap { addon ->
        Stremio.shelves(filesDir, addon).filter { s ->
            extensionRoutes(addon.url, s.catalog.type) && collectorOf(s.catalog.type) in collectors
        }.flatMap { s -> s.titles.map { it to addon.url } }
    }.filter { (t, _) -> filter.accepts(t) { g -> genreName(g) } }.distinctBy { it.first.type + it.first.id }.take(100)
}

private fun MainActivity.searchShelves(query: String, filter: SearchFilter): List<Pair<Stremio.Title, String>> {
    return vodAddons().flatMap { a ->
        Stremio.shelves(filesDir, a).flatMap { s -> s.titles.map { it to a.url } }
    }.filter { (t, _) -> t.name.contains(query, ignoreCase = true) && filter.accepts(t) { g -> genreName(g) } }
        .distinctBy { it.first.type + it.first.id }
}

/** What a search with [f] and no words is for: "Films · 2020 · Drama", or Cinemeta's popular titles. */
private fun MainActivity.filterLabel(f: SearchFilter) = listOfNotNull(f.type.takeIf { it != "all" }?.let { typeName(it) }, f.year, f.genre?.let { genreName(it) },
    f.director?.let { "${getString(R.string.search_filter_director)}: $it" },
    f.actor?.let { "${getString(R.string.search_filter_actor)}: $it" },
    f.adult.takeIf { it != SearchFilter.ADULT_SHOW && adultSearchOffered() }?.let { adultFilterName(it) })
    .joinToString(" · ").ifEmpty { getString(R.string.tmdb_source_cinemeta) }

/** Adult content alone, without words: the adult add-ons' rows as last read, those [filter] lets through. Off the UI thread. */
private fun MainActivity.browseAdult(filter: SearchFilter): List<Pair<Stremio.Title, String>> =
    vodAddons().filter { isAdultAddon(it.url) }.flatMap { a ->
        if (Stremio.shelves(filesDir, a).isEmpty() && a.vod.any { it.listed }) {
            runCatching { Stremio.updateShelves(filesDir, a) }
        }
        Stremio.shelves(filesDir, a).flatMap { s -> s.titles.map { it to a.url } }
    }.filter { (t, _) -> filter.accepts(t) { g -> genreName(g) } }.distinctBy { it.first.type + it.first.id }.take(100)

/** Whether the search offers its Adult content filter: 18+ shows (unlocked, or no lock) and an adult add-on is there. */
private fun MainActivity.adultSearchOffered() = adultUnlocked() && shownAddonUrls().any { isAdultAddon(it) }

private fun MainActivity.adultFilterName(mode: String) = getString(when (mode) {
    SearchFilter.ADULT_HIDE -> R.string.search_adult_hide
    SearchFilter.ADULT_ONLY -> R.string.search_adult_only
    else -> R.string.search_adult_show
})

/**
 * The adult content the search keeps, by its filter: with the rest, none, or that alone. While 18+ is locked, none
 * whatever the filter says (the adult add-ons aren't asked then either: see shownAddonUrls).
 */
internal fun MainActivity.adultSearch(): String = if (!adultUnlocked()) SearchFilter.ADULT_HIDE else searchFilter.adult

/** The search's year: any, or one from this year back to 1920. */
private fun MainActivity.pickYear(text: String) {
    val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val years = listOf<String?>(null) + (now downTo 1920).map { it.toString() }
    val f = searchFilter
    showPage(getString(R.string.search_filter_year), years.map { y ->
        page(y ?: getString(R.string.search_filter_any), if (y == f.year) CHOSEN else "") {
            searchFilter = SearchFilter(f.type, y, f.genre, f.adult, f.director, f.actor)
            openSearch(text, 4)
        }
    }, years.indexOf(f.year).coerceAtLeast(0)) { openSearch(text, 4) }
    // Left/Right: ten years on or back, not a hundred rows one by one.
    pageSides = { step -> focusRow(((focusedRow() ?: 0) + step * 10).coerceIn(0, years.size - 1)) }
}

/** The search's genre: any, or one of Cinemeta's, in the TV's language. */
private fun MainActivity.pickGenre(text: String) {
    val genres = listOf<String?>(null) + libraryGenres()
    val f = searchFilter
    showPage(getString(R.string.search_filter_genre), genres.map { g ->
        page(g?.let { genreName(it) } ?: getString(R.string.search_filter_any), if (g == f.genre) CHOSEN else "") {
            searchFilter = SearchFilter(f.type, f.year, g, f.adult, f.director, f.actor)
            openSearch(text, 5)
        }
    }, genres.indexOf(f.genre).coerceAtLeast(0)) { openSearch(text, 5) }
}

private fun MainActivity.pickPersonFilter(isDirector: Boolean, text: String) {
    val f = searchFilter
    val current = if (isDirector) f.director else f.actor
    val title = getString(if (isDirector) R.string.search_filter_director else R.string.search_filter_actor)
    textPage(title, current.orEmpty(), getString(R.string.search_hint), null, typing = Typing.NAME, back = { openSearch(text, if (isDirector) 6 else 7) }) { typed ->
        listOfNotNull(
            page(getString(R.string.dialog_save)) {
                val value = typed().ifBlank { null }
                searchFilter = if (isDirector) SearchFilter(f.type, f.year, f.genre, f.adult, director = value, actor = f.actor)
                else SearchFilter(f.type, f.year, f.genre, f.adult, director = f.director, actor = value)
                openSearch(text, if (isDirector) 6 else 7)
            },
            if (current != null) page(getString(R.string.search_filter_any)) {
                searchFilter = if (isDirector) SearchFilter(f.type, f.year, f.genre, f.adult, director = null, actor = f.actor)
                else SearchFilter(f.type, f.year, f.genre, f.adult, director = f.director, actor = null)
                openSearch(text, if (isDirector) 6 else 7)
            } else null
        )
    }
}

/** Cinemeta's genres (IMDb's, in English) in the TV's language; one Zephyr TV doesn't know as it is. */
internal fun MainActivity.genreName(genre: String) = GENRES[genre]?.let { getString(it) } ?: genre

internal val GENRES = linkedMapOf(
    "Action" to R.string.genre_action, "Adventure" to R.string.genre_adventure, "Animation" to R.string.genre_animation,
    "Biography" to R.string.genre_biography, "Comedy" to R.string.genre_comedy, "Crime" to R.string.genre_crime,
    "Documentary" to R.string.genre_documentary, "Drama" to R.string.genre_drama, "Family" to R.string.genre_family,
    "Fantasy" to R.string.genre_fantasy, "History" to R.string.genre_history, "Horror" to R.string.genre_horror,
    "Mystery" to R.string.genre_mystery, "Romance" to R.string.genre_romance, "Sci-Fi" to R.string.genre_scifi,
    "Sport" to R.string.genre_sport, "Thriller" to R.string.genre_thriller, "War" to R.string.genre_war,
    "Western" to R.string.genre_western, "Reality-TV" to R.string.genre_reality, "Talk-Show" to R.string.genre_talk,
    "Game-Show" to R.string.genre_game,
    // TMDB's names for the same (Nuvio's catalog uses them)
    "Science Fiction" to R.string.genre_scifi, "Reality" to R.string.genre_reality, "Talk" to R.string.genre_talk,
)
