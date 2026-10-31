package link.mccoy88f.zephyr;
import java.util.regex.*;

/** Only real YouTube video thumbnails become playable cards; never provider-supplied intents. */
public final class SmartTubeResultPolicy {
    private SmartTubeResultPolicy(){}
    public static boolean playable(long duration,boolean live){return live || duration!=0;}
    public static String videoId(String image){
        if(image==null)return null;
        Matcher match=Pattern.compile("^https://(?:i\\.ytimg\\.com|img\\.youtube\\.com)/(?:vi|vi_webp)/([A-Za-z0-9_-]{11})/").matcher(image);
        return match.find()?match.group(1):null;
    }
    public static boolean shouldQuery(String query,String type,boolean adultOnly,boolean provider){
        return provider && !adultOnly && query!=null && !query.trim().isEmpty() && ("all".equals(type)||"videos".equals(type));
    }
}
