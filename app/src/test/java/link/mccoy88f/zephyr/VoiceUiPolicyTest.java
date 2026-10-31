package link.mccoy88f.zephyr;
import org.junit.Test;
import static org.junit.Assert.*;

public final class VoiceUiPolicyTest {
    private boolean help(String maker,String model,boolean recognizer){
        try{return (Boolean)Class.forName("link.mccoy88f.zephyr.VoiceUiPolicy")
            .getMethod("needsRemoteHelp",String.class,String.class,boolean.class).invoke(null,maker,model,recognizer);}
        catch(ClassNotFoundException absent){return false;}
        catch(Exception error){throw new AssertionError(error);}
    }
    @Test public void fireTvWithoutDictationOffersRemoteInstructions(){assertTrue(help("Amazon","AFTMM",false));}
    @Test public void realRecognitionServiceKeepsNativeDictation(){assertFalse(help("Amazon","AFTMM",true));}
    @Test public void otherAndroidTvsAndFireTabletsKeepTheirAssistant(){assertFalse(help("Sony","BRAVIA",false));assertFalse(help("Amazon","KFSUWI",false));}
}
