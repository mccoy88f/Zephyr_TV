package link.mccoy88f.zephyr.bridge;

public final class BridgeRouteTest {
    private static String query(String uri) {
        try { return (String)Class.forName("link.mccoy88f.zephyr.bridge.BridgeRoute")
            .getMethod("queryFromUri",String.class).invoke(null,uri); }
        catch(ClassNotFoundException missing) {return null;}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static String[] route(String text, boolean zephyr) {
        try {return (String[])Class.forName("link.mccoy88f.zephyr.bridge.BridgeRoute")
            .getMethod("route",String.class,boolean.class).invoke(null,text,zephyr);}
        catch(ClassNotFoundException missing){return new String[]{"", ""};}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static void eq(Object actual,Object expected){if(!java.util.Objects.equals(actual,expected))throw new AssertionError("expected "+expected+", got "+actual);}
    public static void main(String[] args){
        eq(query("youtube://search?query=Inception&isVoice=true"),"Inception");
        eq(query("https://www.youtube.com/results?search_query=caff%C3%A8+musica"),"caffè musica");
        eq(query("https://www.youtube.com/tv?launch=voice&vq=apri%20Stremio"),"apri Stremio");
        eq(query("youtube://search?query=%ZZ"),null);
        eq(query("https://www.youtube.com/watch?v=abcdefghijk"),null);
        eq(query(null),null);
        eq(route("Inception",true)[0],"zephyr");
        eq(route("Inception",false)[0],"smarttube");
        eq(route("  Zephyr   apri Stremio ",false)[1],"apri Stremio");
        eq(route("zefiro Inception",false)[0],"zephyr");
        eq(route("SmartTube musica",true)[0],"smarttube");
        eq(route("SmartTube musica",true)[1],"musica");
        eq(route("Zephyranthes",false)[0],"smarttube");
        eq(route("Zephyr",false)[1],"");
        eq(route("a".repeat(501),true)[1].length(),500);
        System.out.println("Bridge route: 15 behavioral checks passed");
    }
}
