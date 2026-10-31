package link.mccoy88f.zephyr.bridge;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.*;

public final class SettingsActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);show();}
    private void show(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(70,45,70,45);
        TextView title=new TextView(this);title.setText(R.string.bridge_title);title.setTextSize(28);title.setTextColor(Color.WHITE);box.addView(title);
        TextView info=new TextView(this);info.setText(getString(R.string.bridge_info)+"\n\n"+getString(R.string.current_target,getSharedPreferences("routing",0).getBoolean("zephyr",false)?"Zephyr":"SmartTube"));info.setTextSize(20);info.setPadding(0,20,0,20);box.addView(info);
        button(box,R.string.use_zephyr,true);button(box,R.string.use_smarttube,false);setContentView(box);
    }
    private void button(LinearLayout box,int label,boolean zephyr){Button b=new Button(this);b.setText(label);b.setTextSize(20);box.addView(b,new LinearLayout.LayoutParams(-1,85));b.setOnClickListener(v->{getSharedPreferences("routing",0).edit().putBoolean("zephyr",zephyr).commit();show();});}
}
