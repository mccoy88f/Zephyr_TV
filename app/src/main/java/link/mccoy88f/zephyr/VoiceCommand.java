package link.mccoy88f.zephyr;
import java.util.Locale;

/** Text routing only: no shell commands and no substring app-name matching. */
public final class VoiceCommand {
    private VoiceCommand() {}
    private static String clean(String s){return s==null?"":s.replaceAll("[\\p{Cntrl}]"," ").trim().replaceAll("\\s+"," ");}
    public static String appForWords(String words,String[] labels,String[] packages){
        String q=clean(words).toLowerCase(Locale.ROOT);
        if(q.startsWith("apri "))q=q.substring(5).trim();
        else if(q.startsWith("open "))q=q.substring(5).trim();
        for(int i=0;i<Math.min(labels.length,packages.length);i++)if(q.equals(clean(labels[i]).toLowerCase(Locale.ROOT)))return packages[i];
        return null;
    }
    public static String contentWords(String words){
        String q=clean(words);
        if(q.toLowerCase(Locale.ROOT).startsWith("cerca "))q=q.substring(6).trim();
        else if(q.toLowerCase(Locale.ROOT).startsWith("search "))q=q.substring(7).trim();
        return q.length()>500?q.substring(0,500):q;
    }
    public static boolean isVoiceKey(int key){return key==84||key==219||key==231;}
}
