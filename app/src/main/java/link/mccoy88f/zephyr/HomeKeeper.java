package link.mccoy88f.zephyr;

import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.os.*;

/** Local Fire OS 6 home redirect. No network, root, system writes or wake locks. */
// Started only on Android 7.0/7.1; modern foreground-service types do not exist there.
@android.annotation.SuppressLint("ForegroundServiceType")
public final class HomeKeeper extends Service {
    static boolean enabled(Context c) { return c.getSharedPreferences("firetv_home",0).getBoolean("enabled",false); }
    static void enabled(Context c, boolean value) { c.getSharedPreferences("firetv_home",0).edit().putBoolean("enabled",value).commit(); }
    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops=(AppOpsManager)c.getSystemService(APP_OPS_SERVICE);
        return ops.checkOpNoThrow("android:get_usage_stats",android.os.Process.myUid(),c.getPackageName())==AppOpsManager.MODE_ALLOWED;
    }
    static boolean supported() {return Build.VERSION.SDK_INT>=24 && Build.VERSION.SDK_INT<=25;}
    public static void start(Context c) {
        if(!supported() || !enabled(c))return;
        if(!hasUsageAccess(c))return;
        c.startService(new Intent(c,HomeKeeper.class));
    }
    private HandlerThread thread;
    private Handler worker;
    private long lastRedirect;
    private PowerManager power;
    private UsageStatsManager usage;
    @Override public void onCreate() {
        super.onCreate();
        power=(PowerManager)getSystemService(POWER_SERVICE);
        usage=(UsageStatsManager)getSystemService(USAGE_STATS_SERVICE);
        PendingIntent controls=PendingIntent.getActivity(this,7,new Intent(this,HomeControlActivity.class),PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(7,new Notification.Builder(this).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.firetv_home_title)).setContentText(getString(R.string.firetv_home_notification))
            .setContentIntent(controls).setOngoing(true).build());
        thread=new HandlerThread("ZephyrHomeKeeper");thread.start();worker=new Handler(thread.getLooper());
        worker.post(poll);
        worker.postDelayed(()->{
            if(!enabled(this)||!power.isInteractive())return;
            UsageEvents.Event latest=latest(60000);
            if(latest==null||"com.amazon.tv.launcher".equals(latest.getPackageName()))launch();
        },2500);
        android.util.Log.i("ZephyrHome","Keeper avviato");
    }
    private UsageEvents.Event latest(long lookback) {
        if(usage==null)return null;
        long now=System.currentTimeMillis();
        UsageEvents events=usage.queryEvents(now-lookback,now);UsageEvents.Event last=null;
        while(events.hasNextEvent()) {
            UsageEvents.Event event=new UsageEvents.Event();events.getNextEvent(event);
            if(event.getEventType()==UsageEvents.Event.MOVE_TO_FOREGROUND)last=event;
        }
        return last;
    }
    private final Runnable poll=new Runnable(){public void run(){
        try {
            if(enabled(HomeKeeper.this)&&power.isInteractive()){
                UsageEvents.Event e=latest(4000);long now=System.currentTimeMillis();
                if(e!=null&&HomePolicy.shouldRedirect(true,true,e.getPackageName(),e.getClassName(),e.getTimeStamp(),now,lastRedirect))launch();
            }
        }catch(RuntimeException failure){android.util.Log.w("ZephyrHome","Controllo Home non riuscito",failure);}
        worker.postDelayed(this,500);
    }};
    private void launch(){
        lastRedirect=System.currentTimeMillis();
        ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
        for(ActivityManager.AppTask task:manager.getAppTasks()) {
            ComponentName base=task.getTaskInfo().baseActivity;
            if(base!=null&&MainActivity.class.getName().equals(base.getClassName())) {
                try {task.moveToFront();android.util.Log.i("ZephyrHome","Zephyr riportato davanti");return;}
                catch(RuntimeException ignored) {break;}
            }
        }
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi=PendingIntent.getActivity(this,8,open,PendingIntent.FLAG_UPDATE_CURRENT);
        ((AlarmManager)getSystemService(ALARM_SERVICE)).setExact(AlarmManager.RTC,System.currentTimeMillis()+50,pi);
        android.util.Log.i("ZephyrHome","Apertura Zephyr programmata");
    }
    @Override public int onStartCommand(Intent i,int flags,int id){return START_STICKY;}
    @Override public void onDestroy(){if(thread!=null)thread.quitSafely();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
