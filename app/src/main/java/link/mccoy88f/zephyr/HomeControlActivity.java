package link.mccoy88f.zephyr;
import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.graphics.Color;
import android.widget.*;

/** Recovery stays reachable from the apps list and does not need ADB. */
public final class HomeControlActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);show();}
    private void show(){
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(70,40,70,40);column.setBackgroundColor(Color.rgb(22,25,32));
        TextView title=new TextView(this);title.setText(getString(R.string.firetv_home_title)+"\n"+getString(R.string.firetv_home_subtitle));title.setTextSize(27);title.setTextColor(Color.WHITE);column.addView(title);
        TextView info=new TextView(this);info.setText(getString(R.string.firetv_home_info,getString(HomeKeeper.enabled(this)?R.string.firetv_home_active:R.string.firetv_home_paused)));info.setTextColor(Color.LTGRAY);info.setTextSize(18);info.setPadding(0,20,0,24);column.addView(info);
        button(column,getString(R.string.firetv_home_enable),()->{if(!HomeKeeper.supported()){Toast.makeText(this,getString(R.string.firetv_home_unsupported),Toast.LENGTH_LONG).show();return;}if(!HomeKeeper.hasUsageAccess(this)){Toast.makeText(this,getString(R.string.firetv_home_access_needed,getPackageName()),Toast.LENGTH_LONG).show();return;}HomeKeeper.enabled(this,true);HomeKeeper.start(this);startActivity(new Intent(this,MainActivity.class));finish();});
        button(column,getString(R.string.firetv_home_pause),()->{HomeKeeper.enabled(this,false);stopService(new Intent(this,HomeKeeper.class));startActivity(new Intent().setComponent(new ComponentName("com.amazon.tv.launcher","com.amazon.tv.launcher.ui.HomeActivity_vNext")));finish();});
        button(column,getString(R.string.firetv_home_settings),()->startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)));
        setContentView(column);
    }
    private void button(LinearLayout root,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setTextSize(20);b.setOnClickListener(v->action.run());root.addView(b,new LinearLayout.LayoutParams(-1,85));}
}
