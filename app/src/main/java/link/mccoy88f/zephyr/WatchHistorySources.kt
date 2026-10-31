package link.mccoy88f.zephyr

/** Installed native extensions publish channels too; removed extensions must not leave dead cards. */
internal fun hasHistorySource(url: String, addons: List<String>, extensions: List<String>) =
    url in addons || url in extensions

/** Local playback survives missing/unsupported Android TV providers (notably Fire OS 6). */
internal fun <T> watchHistorySources(sdk: Int, canReadSystem: Boolean,
    system: () -> List<T>, local: () -> List<T>): List<T> {
    val remote = if (sdk >= 26 && canReadSystem) runCatching(system).getOrDefault(emptyList()) else emptyList()
    return remote + local()
}
