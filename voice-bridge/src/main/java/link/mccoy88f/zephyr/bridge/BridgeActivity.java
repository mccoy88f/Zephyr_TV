package link.mccoy88f.zephyr.bridge;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.net.Uri;
import android.widget.Toast;
import org.json.*;

/** Receives YouTube requests already transcribed by Alexa. Does not capture audio. */
public final class BridgeActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);dispatch(getIntent());finish();}
    @Override public void onNewIntent(Intent intent){super.onNewIntent(intent);dispatch(intent);finish();}
    private String installed(String[] packages){
        for(String pkg:packages)try{getPackageManager().getApplicationInfo(pkg,0);return pkg;}
        catch(android.content.pm.PackageManager.NameNotFoundException absent){}
        return null;
    }
    private String words(Intent intent){
        String text=intent.getStringExtra(SearchManager.QUERY);
        if(text!=null)return text;
        Uri uri=intent.getData();
        if(uri==null)return null;
        text=BridgeRoute.queryFromUri(uri.toString());
        if(text!=null)return text;
        try {
            String voice=uri.getQueryParameter("voice");
            if(voice!=null){JSONObject request=new JSONObject(voice).optJSONObject("youtubeAssistantRequest");
                if(request!=null)return request.optString("query",null);}
        }catch(JSONException|UnsupportedOperationException|IllegalArgumentException ignored){}
        return null;
    }
    private void dispatch(Intent incoming){
        try{
            String text=words(incoming);
            String[] route=BridgeRoute.route(text,getSharedPreferences("routing",0).getBoolean("zephyr",false));
            // Video/channel links have no search text and always keep going to SmartTube.
            if(text!=null && "zephyr".equals(route[0])){
                String pkg=installed(new String[]{"link.mccoy88f.zephyr.debug","link.mccoy88f.zephyr"});
                if(pkg==null){Toast.makeText(this,R.string.zephyr_missing,Toast.LENGTH_LONG).show();return;}
                Intent target=new Intent(Intent.ACTION_SEARCH).setComponent(new ComponentName(pkg,"link.mccoy88f.zephyr.MainActivity"));
                target.putExtra(SearchManager.QUERY,route[1]);
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
                android.util.Log.i("ZephyrBridge","Search routed to Zephyr");
            }else{
                String pkg=installed(new String[]{"org.smarttube.stable","org.smarttube.beta","app.smarttube.fdroid"});
                if(pkg==null){Toast.makeText(this,R.string.smarttube_missing,Toast.LENGTH_LONG).show();return;}
                Intent target=new Intent(incoming);
                target.setComponent(new ComponentName(pkg,"com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity")).setPackage(pkg);
                if(text!=null){target.setAction(Intent.ACTION_VIEW);target.setData(Uri.parse("youtube://search").buildUpon().appendQueryParameter("query",route[1]).build());}
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
                android.util.Log.i("ZephyrBridge","Request routed to SmartTube");
            }
        }catch(ActivityNotFoundException|SecurityException|android.os.BadParcelableException failure){
            Toast.makeText(this,R.string.routing_failed,Toast.LENGTH_LONG).show();
            android.util.Log.w("ZephyrBridge","Destination unavailable");
        }
    }
}
