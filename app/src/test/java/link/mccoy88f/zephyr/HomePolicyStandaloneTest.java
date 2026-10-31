package link.mccoy88f.zephyr;

public final class HomePolicyStandaloneTest {
    static boolean decide(boolean enabled, boolean screen, String pkg, String cls, long at, long now, long last) {
        try {
            return (Boolean) Class.forName("link.mccoy88f.zephyr.HomePolicy")
                .getMethod("shouldRedirect", boolean.class, boolean.class, String.class, String.class, long.class, long.class, long.class)
                .invoke(null, enabled, screen, pkg, cls, at, now, last);
        } catch (ClassNotFoundException missing) { return false; }
        catch (Exception e) { throw new AssertionError(e); }
    }
    static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
    public static void main(String[] args) {
        boolean sdk26=false,sdk25=false;
        try {
            java.lang.reflect.Method api=Class.forName("link.mccoy88f.zephyr.HomePolicy").getMethod("supportsTvChannels",int.class);
            sdk26=(Boolean)api.invoke(null,26);sdk25=(Boolean)api.invoke(null,25);
        }catch(ReflectiveOperationException ignored){}
        check(sdk26,"Android 8 TV channels must remain supported");
        check(!sdk25,"Android 7 must not call Android 8 TV channel APIs");
        String p="com.amazon.tv.launcher", h="com.amazon.tv.launcher.ui.HomeActivity_vNext";
        check(decide(true,true,p,h,10000,10100,0),"Amazon Home must redirect to Zephyr");
        check(!decide(true,true,p,"com.amazon.tv.launcher.ui.MainSettingsActivity",10000,10100,0),"Settings must remain usable");
        check(!decide(true,true,"com.nuvio.tv","Player",10000,10100,0),"Playback must not be interrupted");
        check(!decide(false,true,p,h,10000,10100,0),"Paused keeper must not redirect");
        check(!decide(true,false,p,h,10000,10100,0),"Standby must not wake the TV");
        check(!decide(true,true,p,h,5000,10100,0),"Stale Home event must not redirect");
        check(!decide(true,true,p,h,11000,10100,0),"Future event must not redirect");
        check(!decide(true,true,p,h,10000,10100,9900),"Debounce must prevent a launch loop");
        System.out.println("Home policy: 10 behavioral checks passed");
    }
}
