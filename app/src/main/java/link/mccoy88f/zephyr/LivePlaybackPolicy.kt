package link.mccoy88f.zephyr

/** Preferences for live TV; unknown languages/qualities remain usable fallbacks. */
internal object LivePlaybackPolicy {
    fun audioRank(language: String?, label: String?): Int {
        val lang = language.orEmpty().lowercase().substringBefore('-').substringBefore('_')
        val name = label.orEmpty().trim().lowercase()
        if (lang in setOf("it", "ita") || Regex("\\b(italiano|italian|ita)\\b").containsMatchIn(name)) return 2
        return if (lang.endsWith("ak") || name.endsWith("ak")) 1 else 0
    }

    fun showControls(vod:Boolean,quietZap:Boolean)=vod || !quietZap

    fun isFullHd(width: Int, height: Int) = width > 0 && height == 1080
    fun streamRank(height: Int) = if (height == 1080) 0 else 1
    fun refreshAtStartup(enabled: Boolean, requested: Boolean) = enabled && requested
}
