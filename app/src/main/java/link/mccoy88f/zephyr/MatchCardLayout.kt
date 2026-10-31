package link.mccoy88f.zephyr

/** Fixture names and kickoff times must remain readable across the room. */
internal object MatchCardLayout {
    fun isFixture(uri: String?) = uri?.startsWith("file:///")==true && "/match-posters/" in uri
    fun columns(uri: String?, normal: Int) = if(isFixture(uri))minOf(3,normal) else normal
}
