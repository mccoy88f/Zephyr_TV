package link.mccoy88f.zephyr

import android.widget.ImageView
import link.mccoy88f.zephyr.MainActivity.Companion.HDMI
import link.mccoy88f.zephyr.MainActivity.Companion.SONY_INPUTS
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.view.View

private val SET_TOP_WORDS = listOf("sky", "tivo", "xfinity", "comcast", "directv", "dish", "stb", "set-top", "settop", "cable", "satellite", "freesat", "freeview")
private val AUDIO_WORDS = listOf("soundbar", "sound bar", "sonos", "avr", "receiver", "home theater", "home theatre", "bose", "denon", "marantz", "onkyo", "yamaha", "harman", "jbl")

/**
 * Icon key for an HDMI tile, from its name: the CEC device name, or the name the user gave the input in TV
 * settings. Android doesn't give apps the CEC device type, so the name is all there is. One brand word covers
 * the family ("PlayStation" is PS3 to PS5). [named]: a CEC device, which is a player when nothing else matches.
 */
internal fun hdmiIcon(name: String, named: Boolean): String {
    val n = name.lowercase()
    return when {
        "playstation" in n -> "playstation"
        "xbox" in n -> "xbox"
        "nintendo" in n || "switch" in n -> "nintendoswitch"
        "apple tv" in n || "appletv" in n -> "appletv"
        SET_TOP_WORDS.any { it in n } -> "settop"
        named -> "playback"
        else -> "hdmi"
    }
}

/** Brand logos for external devices (game consoles, etc.) that have dedicated marks. */
internal fun isPredefinedHdmiLogo(key: String): Boolean =
    key in setOf("playstation", "xbox", "nintendoswitch", "appletv")

/** Soundbars and AV receivers name themselves over CEC too, but there's nothing to switch to. */
internal fun isAudioDevice(name: String) = name.lowercase().let { n -> AUDIO_WORDS.any { it in n } }

/** A plain HDMI port (no CEC name) is in the home row only while it has a signal, unless it was pinned by hand. */
internal fun inRow(pkg: String, offline: Boolean, hidden: Set<String>, pinned: Set<String>) =
    pkg !in hidden && (!offline || pkg in pinned)

/** HDMI ports pinned to the row by hand: shown with or without a signal (some TVs never report one). */
internal fun MainActivity.pinnedInputs(): Set<String> = prefs.getStringSet(K.INPUTS_PINNED, emptySet())!!

/** Mode for TV inputs: "menu" (top bar button with popup menu) or "apps" (app row tiles). */
internal fun MainActivity.inputsMode() = prefs.getString(K.INPUTS_MODE, "menu")!!

/** Show connected HDMI inputs in apps row even when in menu mode. */
internal fun MainActivity.inputsInApps() = prefs.getBoolean(K.INPUTS_IN_APPS, false)

/**
 * One tile per HDMI port, keyed by the port's input id. A device that names itself over CEC (the PS5 shows up
 * as "PlayStation 5") takes over its port's tile; it's listed while asleep too, and opening the tile wakes it.
 * A port without one is a tile named as in TV settings ("HDMI 2"), in the row only while it has a signal.
 */
internal fun MainActivity.hdmiInputs(): List<MainActivity.App> = try {
    val tv = getSystemService(TvInputManager::class.java) ?: return emptyList()
    val inputs = tv.tvInputList.filter { (it.type == TvInputInfo.TYPE_HDMI || it.isPassthroughInput) && !it.isHidden(this) }
    val devices = inputs.filter { it.parentId != null }
    val audio = devices.filter { isAudioDevice(it.loadLabel(this)?.toString().orEmpty()) }.mapNotNull { it.parentId }.toSet()
    val on = connectedInputs()
    // Devices first, so distinctBy keeps the device over its port.
    (devices + inputs.filter { it.parentId == null }).filter { (it.parentId ?: it.id) !in audio }.map { input ->
        val port = input.parentId ?: input.id
        val named = input.parentId != null
        val label = (if (named) input.loadLabel(this) else input.loadCustomLabel(this) ?: input.loadLabel(this))?.toString()?.takeIf { it.isNotBlank() } ?: port
        val parent = if (named) inputs.firstOrNull { it.id == input.parentId } else null
        val portName = (parent?.loadCustomLabel(this) ?: parent?.loadLabel(this) ?: if (!named) input.loadCustomLabel(this) ?: input.loadLabel(this) else null)?.toString()?.takeIf { it.isNotBlank() } ?: label
        val key = hdmiIcon(label, named)
        val predefined = isPredefinedHdmiLogo(key)
        val iconRes = if (predefined) MainActivity.HDMI_ICONS[key] ?: R.drawable.ic_input else R.drawable.ic_input
        val icon = getDrawable(iconRes)!!
        val isConnected = port in on || (named && !isAudioDevice(label))
        MainActivity.App(
            MainActivity.HDMI + port,
            label,
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(port)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            icon,
            offline = !isConnected,
            sublabel = if (predefined) null else portName,
        )
    }.distinctBy { it.pkg }
} catch (e: Exception) {
    emptyList()
}

/** Whether the device has external inputs (HDMI and the like) at all: a stick, a phone or a tablet has none. */
internal fun MainActivity.hasInputs(): Boolean = runCatching {
    getSystemService(TvInputManager::class.java)?.tvInputList.orEmpty().any { it.type == TvInputInfo.TYPE_HDMI || it.isPassthroughInput }
}.getOrDefault(false)

/** The top bar's TV inputs button: in the menu mode, on a device with inputs. */
internal fun MainActivity.showInputsButton() {
    findViewById<View>(R.id.tvInputsButton)?.visibility = if (inputsMode() == "apps" || !hasInputs()) View.GONE else View.VISIBLE
}

/** Updates top bar button visibility and reloads apps according to the active mode. */
internal fun MainActivity.applyInputsMode() {
    showInputsButton()
    updateInputsDot()
    refreshApps()
}

/**
 * The external inputs (HDMI, AV) with a device connected, by input id. Sony TVs keep the real state in their
 * external inputs app's own table, readable by any app; Android's own input state there says "connected" for
 * every HDMI input. Elsewhere, Android's state (connected, not standby or disconnected).
 */
internal fun MainActivity.connectedInputs(): Set<String> {
    val sony = runCatching {
        contentResolver.query(SONY_INPUTS,
            arrayOf("input_id", "connect_state"), null, null, null)?.use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) if (c.getInt(1) == 1) c.getString(0)?.let { out += it }
            out
        }
    }.getOrNull()
    if (sony != null) return sony

    val tm = getSystemService(TvInputManager::class.java) ?: return emptySet()
    return runCatching {
        val cec = tm.tvInputList.filter { it.parentId != null }.mapNotNull { it.parentId }.toSet()
        val hdmi = tm.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI || it.isPassthroughInput }
        val connected = hdmi.filter { tm.getInputState(it.id) == TvInputManager.INPUT_STATE_CONNECTED }.map { it.id }.toSet()
        if (hdmi.isNotEmpty() && connected.size == hdmi.size && cec.isNotEmpty()) cec else cec + connected
    }.getOrDefault(emptySet())
}

/** A green dot on the TV inputs button while at least one external input has an active device (see hdmiInputs). */
internal fun MainActivity.updateInputsDot() {
    val icon = findViewById<ImageView>(R.id.tvInputsButton) ?: return
    val on = hdmiInputs().any { !it.offline }
    icon.overlay.remove(inputsDot)
    if (!on) return
    if (icon.width == 0) {
        icon.post { updateInputsDot() }
        return
    }
    val size = dp(9)
    inputsDot.setBounds(icon.width - size - dp(3), dp(3), icon.width - dp(3), dp(3) + size)
    icon.overlay.add(inputsDot)
}
