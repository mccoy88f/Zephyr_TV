package link.mccoy88f.zephyr

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Cloudflare WARP for Zephyr TV alone: a WireGuard tunnel to Cloudflare's free WARP network, as Android's VPN for
 * this app only (its other processes too: the player, the plugins', the extensions'; and the torrent engine), so the sites add-ons, plugins
 * and extensions read, and the servers of the videos, see one of Cloudflare's addresses instead of the device's.
 *
 * The account is made as Cloudflare's own app makes it (and wgcf, on servers): a key pair here, its public key sent to
 * Cloudflare's client API, which answers with the addresses and its own key. That API isn't documented: if it
 * changes, turning WARP on fails and says so. The tunnel is WireGuard's own Android library (GoBackend).
 */
internal object Warp {
    private const val API = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=" // Cloudflare's WARP servers
    private const val ENDPOINT = "engage.cloudflareclient.com:2408"

    private val tunnel = object : Tunnel {
        override fun getName() = "zephyr-warp"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    @Volatile private var backend: GoBackend? = null

    private fun backend(c: Context) = backend ?: synchronized(this) { backend ?: GoBackend(c.applicationContext).also { backend = it } }

    /** Whether the tunnel is up now. */
    fun isUp(c: Context) = runCatching { backend(c).getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)

    /**
     * The tunnel up, for this app only: with the account kept in [saved] (JSON), or a new one, which is returned to be
     * kept. Android's permission for the VPN must be given already (VpnService.prepare). Network: off the UI thread.
     */
    fun up(c: Context, saved: String?): String {
        val account = saved?.let { runCatching { JSONObject(it) }.getOrNull() } ?: register()
        backend(c).setState(tunnel, Tunnel.State.UP, config(c, account))
        return account.toString()
    }

    fun down(c: Context) {
        runCatching { backend(c).setState(tunnel, Tunnel.State.DOWN, null) }.onFailure { warn("WARP down: $it") }
    }

    /** A new free WARP account: our key pair, and what Cloudflare gives for it (addresses, its key, where to). */
    private fun register(): JSONObject {
        val keys = KeyPair()
        val body = JSONObject()
            .put("key", keys.publicKey.toBase64())
            .put("install_id", "").put("fcm_token", "")
            .put("tos", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
            .put("model", "Zephyr TV").put("serial_number", "").put("locale", "en_US")
        val reg = call("POST", API, body, null)
        val id = reg.getString("id")
        val token = reg.getString("token")
        // WARP itself on, not only 1.1.1.1's DNS (new accounts mostly have it already).
        runCatching { call("PATCH", "$API/$id", JSONObject().put("warp_enabled", true), token) }.onFailure { warn("WARP enable: $it") }
        val conf = reg.getJSONObject("config")
        val peer = conf.getJSONArray("peers").optJSONObject(0)
        val addresses = conf.getJSONObject("interface").getJSONObject("addresses")
        return JSONObject()
            .put("id", id).put("token", token)
            .put("private", keys.privateKey.toBase64())
            .put("v4", addresses.optString("v4")).put("v6", addresses.optString("v6"))
            .put("peer", peer?.optString("public_key")?.takeIf { it.isNotBlank() } ?: PEER_KEY)
            .put("endpoint", peer?.optJSONObject("endpoint")?.optString("host")?.takeIf { it.isNotBlank() } ?: ENDPOINT)
    }

    private fun call(method: String, url: String, body: JSONObject, token: String?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("User-Agent", "okhttp/3.12.1")
            conn.setRequestProperty("CF-Client-Version", "a-6.10-2158")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("WARP $method: $code ${text.take(200)}")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** The tunnel's configuration: everything of this app, and nothing else, through Cloudflare. */
    private fun config(c: Context, a: JSONObject): Config {
        val iface = Interface.Builder()
            .parsePrivateKey(a.getString("private"))
            .apply {
                a.optString("v4").takeIf { it.isNotBlank() }?.let { addAddress(InetNetwork.parse("$it/32")) }
                a.optString("v6").takeIf { it.isNotBlank() }?.let { addAddress(InetNetwork.parse("$it/128")) }
            }
            .addDnsServer(InetAddress.getByName("1.1.1.1"))
            .addDnsServer(InetAddress.getByName("1.0.0.1"))
            .setMtu(1280)
            .includeApplication(c.packageName) // Zephyr TV only: the other apps stay as they are
            // ...and its torrent engine, when it's installed: the peers then see Cloudflare's address, not the TV's.
            // (Installed after WARP went up, it's covered once WARP is turned off and on again.)
            .apply { if (Torrents.installed(c) != null) includeApplication(Torrents.enginePackage(c)) }
            .build()
        val peer = Peer.Builder()
            .parsePublicKey(a.optString("peer").ifBlank { PEER_KEY })
            .parseAllowedIPs("0.0.0.0/0, ::/0")
            .parseEndpoint(a.optString("endpoint").ifBlank { ENDPOINT })
            .setPersistentKeepalive(25)
            .build()
        return Config.Builder().setInterface(iface).addPeer(peer).build()
    }
}
