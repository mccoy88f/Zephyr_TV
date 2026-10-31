package link.mccoy88f.zephyr;
import android.content.*;
public final class HomeKeeperBoot extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){HomeKeeper.start(c);}
}
