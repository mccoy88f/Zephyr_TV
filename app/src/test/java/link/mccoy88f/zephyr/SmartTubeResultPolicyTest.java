package link.mccoy88f.zephyr;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SmartTubeResultPolicyTest {
    private String id(String image){
        try{return (String)Class.forName("link.mccoy88f.zephyr.SmartTubeResultPolicy").getMethod("videoId",String.class).invoke(null,image);}
        catch(ClassNotFoundException missing){return null;}catch(Exception e){throw new AssertionError(e);}
    }
    private boolean enabled(String q,String type,boolean adult,boolean provider){
        try{return (Boolean)Class.forName("link.mccoy88f.zephyr.SmartTubeResultPolicy")
            .getMethod("shouldQuery",String.class,String.class,boolean.class,boolean.class).invoke(null,q,type,adult,provider);}
        catch(ClassNotFoundException missing){return false;}catch(Exception e){throw new AssertionError(e);}
    }
    @Test public void mapsRealVideoThumbnailsToStableVideoIds(){assertEquals("BV7z0I90MJk",id("https://i.ytimg.com/vi/BV7z0I90MJk/hqdefault.jpg?x=1"));assertEquals("BV7z0I90MJk",id("https://i.ytimg.com/vi_webp/BV7z0I90MJk/hqdefault.webp"));}
    @Test public void rejectsNonVideoCardsAndForeignOrMalformedUrls(){assertNull(id(null));assertNull(id("https://evil.example/vi/BV7z0I90MJk/x"));assertNull(id("https://i.ytimg.com.evil.example/vi/BV7z0I90MJk/x"));assertNull(id("https://i.ytimg.com/vi/short/x"));assertNull(id("https://i.ytimg.com/channel/BV7z0I90MJk/x"));}
    @Test public void addsYouTubeForAllAndVideoSearches(){assertTrue(enabled("Inception","all",false,true));assertTrue(enabled("musica","videos",false,true));}
    @Test public void respectsExplicitFiltersAndMissingSmartTube(){assertFalse(enabled("Inception","movie",false,true));assertFalse(enabled("Inception","all",true,true));assertFalse(enabled("Inception","all",false,false));assertFalse(enabled(" ","all",false,true));}
    private boolean playable(long duration,boolean live){try{return (Boolean)Class.forName("link.mccoy88f.zephyr.SmartTubeResultPolicy").getMethod("playable",long.class,boolean.class).invoke(null,duration,live);}catch(NoSuchMethodException absent){return false;}catch(Exception e){throw new AssertionError(e);}}
    @Test public void excludesZeroDurationMixesButKeepsVideosAndLiveStreams(){assertTrue(playable(139000,false));assertTrue(playable(-1,false));assertTrue(playable(0,true));assertFalse(playable(0,false));}
}
