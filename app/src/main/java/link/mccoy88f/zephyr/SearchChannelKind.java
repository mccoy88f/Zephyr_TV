package link.mccoy88f.zephyr;

/** Placement in a collector is not a declaration that every extension channel is radio. */
public final class SearchChannelKind {
    private SearchChannelKind(){}
    public static boolean isRadio(String type,String collector){return "radio".equals(type)||"radio".equals(collector);}
}
