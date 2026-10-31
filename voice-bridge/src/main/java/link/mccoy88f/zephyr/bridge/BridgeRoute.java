package link.mccoy88f.zephyr.bridge;

import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.*;

/** Text/URI routing only. No microphone, shell commands or network access. */
public final class BridgeRoute {
    private BridgeRoute() {}
    public static String queryFromUri(String uri) {
        if(uri==null || uri.length()>32768)return null;
        Matcher m=Pattern.compile("[?&#](?:search_query|query|q|vq)=([^&#]*)").matcher(uri);
        if(!m.find())return null;
        try{return clean(URLDecoder.decode(m.group(1),"UTF-8"));}
        catch(java.io.UnsupportedEncodingException|IllegalArgumentException invalid){return null;}
    }
    private static String clean(String s){
        if(s==null)return "";
        String text=s.replaceAll("[\\p{Cntrl}]"," ").trim().replaceAll("\\s+"," ");
        return text.substring(0,Math.min(500,text.length()));
    }
    public static String[] route(String words,boolean zephyrDefault){
        String query=clean(words), lower=query.toLowerCase(Locale.ROOT);
        for(String prefix:new String[]{"zephyr","zephir","zefir","zefiro"})
            if(lower.equals(prefix)||lower.startsWith(prefix+" "))
                return new String[]{"zephyr",query.substring(prefix.length()).trim()};
        if(lower.equals("smarttube")||lower.startsWith("smarttube "))
            return new String[]{"smarttube",query.substring(9).trim()};
        return new String[]{zephyrDefault?"zephyr":"smarttube",query};
    }
}
