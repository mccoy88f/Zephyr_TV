package link.mccoy88f.zephyr

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import link.mccoy88f.zephyr.MainActivity.Companion.CHOSEN
import link.mccoy88f.zephyr.SettingsRows.CITY_ROW

// The weather under the clock and the weather location page.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

internal fun MainActivity.refreshWeather() {
    if (prefs.getString(K.WEATHER, "off") == "off") return
    if (System.currentTimeMillis() - prefs.getLong(K.WX_TIME, 0) < 25 * 60_000L) return
    netIo.execute {
        try {
            if (!manualCity() && System.currentTimeMillis() - prefs.getLong(K.GEO_TIME, 0) > 6 * 3600_000L) {
                Weather.locate()?.let { place ->
                    prefs.edit().putString(K.GEO_CITY, place.name).putFloat(K.GEO_LAT, place.lat).putFloat(K.GEO_LON, place.lon)
                        .putLong(K.GEO_TIME, System.currentTimeMillis()).apply()
                }
            }
            if (!prefs.contains(K.GEO_LAT)) return@execute
            val now = Weather.now(prefs.getFloat(K.GEO_LAT, 0f), prefs.getFloat(K.GEO_LON, 0f))
            prefs.edit().putFloat(K.WX_TEMP, now.celsius).putInt(K.WX_CODE, now.code)
                .putLong(K.WX_TIME, System.currentTimeMillis()).apply()
            ui.post { showWeather() }
        } catch (e: Exception) {
            warn("weather failed ($e)") // keep the last reading; next tick retries
        }
    }
}

// An IP's location is wherever the provider routes it, sometimes a whole region off (Rome showing as
// Sicily), so you can pick the city instead. Open-Meteo's geocoding, also free and keyless.

internal fun MainActivity.manualCity() = prefs.getBoolean(K.GEO_MANUAL, false)

/** Automatic first, then the search box; a search lists what it found under them. */
internal fun MainActivity.pickCity(query: String = "", found: List<Weather.Place>? = null, status: String = "") {
    val search = EditText(this).apply {
        id = View.generateViewId()
        setText(query)
        setSelection(query.length)
        hint = getString(R.string.city_hint)
        textSize = 14f
        setTextColor(fg)
        setHintTextColor(Color.argb(120, Color.red(fg), Color.green(fg), Color.blue(fg)))
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setPadding(dp(14), 0, dp(14), 0)
        val bg = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(field)
            setStroke(dp(1), fieldEdge())
        }
        background = bg
        setOnFocusChangeListener { _, focused -> bg.setStroke(if (focused) dp(2) else dp(1), if (focused) fg else fieldEdge()) }
        setOnEditorActionListener { v, _, _ -> searchCity(v.text.toString().trim()); true }
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, 0)
        addView(search, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
        if (status.isNotEmpty()) addView(label(status, 12f).apply { alpha = 0.6f; setPadding(dp(14), dp(8), 0, 0) })
    }
    val rows = listOf(page(getString(R.string.automatic_ip), if (manualCity()) "" else CHOSEN) {
        prefs.edit().remove(K.GEO_MANUAL).remove(K.GEO_TIME).remove(K.WX_TIME).apply()
        refreshWeather()
        renderSettings(CITY_ROW)
    }) + found.orEmpty().map { c ->
        page(c.name, c.where) {
            prefs.edit().putBoolean(K.GEO_MANUAL, true).putString(K.GEO_CITY, c.name)
                .putFloat(K.GEO_LAT, c.lat).putFloat(K.GEO_LON, c.lon).remove(K.WX_TIME).apply()
            refreshWeather()
            renderSettings(CITY_ROW)
        }
    }
    showPage(getString(R.string.weather_location), rows, if (found.isNullOrEmpty()) 0 else 1, top = top) { renderSettings(CITY_ROW) }
    // showPage pins Up on the first row to itself; here Up goes to the search box.
    settingsList.getChildAt(1).nextFocusUpId = search.id
    if (found.isNullOrEmpty()) search.requestFocus()
    // Results in: put the keyboard away, it would cover them.
    else getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
}

internal fun MainActivity.searchCity(query: String) {
    if (query.isEmpty()) return
    pickCity(query, status = getString(R.string.searching))
    netIo.execute {
        val found = try {
            Weather.search(query, java.util.Locale.getDefault().language)
        } catch (e: Exception) {
            warn("city search failed ($e)")
            null
        }
        ui.post {
            // Only if the page is still open: Back may have left it while the search ran.
            if (settingsScrim.visibility != View.VISIBLE || findViewById<TextView>(R.id.settingsTitle).text.toString() != getString(R.string.weather_location)) return@post
            when {
                found == null -> pickCity(query, status = getString(R.string.search_failed))
                found.isEmpty() -> pickCity(query, status = getString(R.string.no_places, query))
                else -> pickCity(query, found)
            }
        }
    }
}

internal fun MainActivity.showWeather() {
    val view = findViewById<TextView>(R.id.weather)
    val unit = prefs.getString(K.WEATHER, "off")
    if (unit == "off" || !prefs.contains(K.WX_TEMP)) {
        view.visibility = View.GONE
        return
    }
    val c = prefs.getFloat(K.WX_TEMP, 0f)
    val temp = Math.round(if (unit == "f") c * 9 / 5 + 32 else c)
    view.text = listOfNotNull("$temp° ${weatherWord(prefs.getInt(K.WX_CODE, 0))}".trim(), prefs.getString(K.GEO_CITY, null))
        .joinToString("  ·  ")
    view.visibility = View.VISIBLE
}

/** WMO weather codes, as Open-Meteo reports them. */
internal fun MainActivity.weatherWord(code: Int) = when (code) {
    0 -> getString(R.string.weather_clear)
    1, 2 -> getString(R.string.weather_partly_cloudy)
    3 -> getString(R.string.weather_cloudy)
    45, 48 -> getString(R.string.weather_fog)
    in 51..57 -> getString(R.string.weather_drizzle)
    in 61..67, in 80..82 -> getString(R.string.weather_rain)
    in 71..77, 85, 86 -> getString(R.string.weather_snow)
    in 95..99 -> getString(R.string.weather_thunderstorm)
    else -> ""
}
