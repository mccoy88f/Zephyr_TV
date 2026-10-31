package link.mccoy88f.zephyr;

/** Pure decision logic: never redirect Settings, playback, standby or stale events. */
public final class HomePolicy {
    private HomePolicy() {}
    @androidx.annotation.ChecksSdkIntAtLeast(api=26)
    public static boolean supportsTvChannels(int sdk) { return sdk >= 26; }
    public static boolean shouldRedirect(boolean enabled, boolean interactive, String pkg, String cls,
                                         long eventAt, long now, long lastRedirect) {
        return enabled && interactive
            && "com.amazon.tv.launcher".equals(pkg)
            && "com.amazon.tv.launcher.ui.HomeActivity_vNext".equals(cls)
            && eventAt <= now && now-eventAt <= 4000
            && now-lastRedirect >= 1500;
    }
}
