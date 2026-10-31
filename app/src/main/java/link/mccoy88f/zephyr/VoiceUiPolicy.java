package link.mccoy88f.zephyr;

public final class VoiceUiPolicy {
    private VoiceUiPolicy(){}
    public static boolean needsRemoteHelp(String manufacturer,String model,boolean recognizer){
        return !recognizer && "Amazon".equalsIgnoreCase(manufacturer)
            && model!=null && model.startsWith("AFT");
    }
}
