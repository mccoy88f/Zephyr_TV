package link.mccoy88f.zephyr;
import org.junit.Test;
import static org.junit.Assert.*;
public final class SearchChannelKindTest {
    private boolean radio(String type,String collector){try{return (Boolean)Class.forName("link.mccoy88f.zephyr.SearchChannelKind").getMethod("isRadio",String.class,String.class).invoke(null,type,collector);}catch(ClassNotFoundException absent){return false;}catch(Exception e){throw new AssertionError(e);}}
    @Test public void explicitRadioAndRadioCollectorRemainRadio(){assertTrue(radio("radio",null));assertTrue(radio("tv","radio"));}
    @Test public void tvExtensionsAreNotRadioMerelyBecauseTheyAreExtensions(){assertFalse(radio("tv",null));assertFalse(radio("tv","tv"));}
}
