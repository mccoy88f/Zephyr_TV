package link.mccoy88f.zephyr;
public final class VoiceCommandStandaloneTest {
    static Object call(String name,Class<?>[] types,Object...args){
        try{return Class.forName("link.mccoy88f.zephyr.VoiceCommand").getMethod(name,types).invoke(null,args);}
        catch(ClassNotFoundException missing){return null;}catch(Exception e){throw new AssertionError(e);}
    }
    static void same(Object expected,Object actual){if(!java.util.Objects.equals(expected,actual))throw new AssertionError("Expected "+expected+", got "+actual);}
    public static void main(String[] args){
        Class<?>[] signature={String.class,String[].class,String[].class};
        String[] labels={"Stremio","Nuvio"},packages={"com.stremio.one","com.nuvio.tv"};
        same("com.stremio.one",call("appForWords",signature,"  Apri STREMIO  ",labels,packages));
        same("com.nuvio.tv",call("appForWords",signature,"open Nuvio",labels,packages));
        same(null,call("appForWords",signature,"apri Stremio e cancella tutto",labels,packages));
        same(null,call("appForWords",signature,"cerca Inception",labels,packages));
        same("Inception",call("contentWords",new Class<?>[]{String.class},"cerca Inception"));
        same("",call("contentWords",new Class<?>[]{String.class},(Object)null));
        same(true,call("isVoiceKey",new Class<?>[]{int.class},231));
        same(true,call("isVoiceKey",new Class<?>[]{int.class},84));
        same(false,call("isVoiceKey",new Class<?>[]{int.class},23));
        System.out.println("Voice routing: 9 behavioral checks passed");
    }
}
