package de.local.intercompocket;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.net.wifi.WifiManager;
import android.os.*;

public final class IntercomService extends Service {
    static volatile IntercomService instance;
    volatile Engine engine;
    PhoneIntegration phone;
    private final Handler main=new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;
    private Ringtone ring;
    private String ringingId="";
    private boolean microphone;
    static volatile String lastError="";
    static final String ENABLE="enable", STOP="stop", DECLINE="decline", RELOAD="reload";
    public void onCreate(){
        super.onCreate();instance=this;phone=new PhoneIntegration(this);
        NotificationManager nm=getSystemService(NotificationManager.class);
        NotificationChannel active=new NotificationChannel("active","Intercom erreichbar",NotificationManager.IMPORTANCE_LOW);
        active.setDescription("Zeigt an, solange das Handy Anrufe empfangen kann.");nm.createNotificationChannel(active);
        NotificationChannel incoming=new NotificationChannel("calls","Eingehende Anrufe",NotificationManager.IMPORTANCE_HIGH);
        incoming.setSound(null,null);incoming.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);nm.createNotificationChannel(incoming);
        startForeground(1,ongoing("Wird gestartet …"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }
    public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?ENABLE:intent.getAction();
        if(STOP.equals(action)){stopSelf();return START_NOT_STICKY;}
        if(DECLINE.equals(action)){if(engine!=null&&engine.callId.equals(intent.getStringExtra("call")))engine.finish("Anruf abgelehnt",true);return START_NOT_STICKY;}
        if(RELOAD.equals(action)&&engine!=null){engine.stop();phone.close();engine=null;}
        if(engine==null){
            try{
                Config config=new Config(this);if(config.key().isEmpty())throw new Exception("Bitte zuerst den Intercom-Schlüssel eintragen.");
                engine=new Engine(this,config,()->main.post(this::update));engine.start();lastError="";
                if(wake==null){wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"IntercomPocket:reachable");wake.acquire();}
                WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
                if(wifi!=null&&wifiLock==null){wifiLock=wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"IntercomPocket:WiFi");wifiLock.acquire();}
            }catch(Exception e){lastError="Start fehlgeschlagen: "+e.getMessage();stopSelf();}
        }
        update();return START_NOT_STICKY;
    }
    void microphone(){
        startForeground(1,ongoing("Gespräch aktiv"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);microphone=true;
    }
    void phoneMicrophone(){
        startForeground(1,ongoing("Telefonat aktiv"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE|ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL|ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);microphone=true;
    }
    private Notification ongoing(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,10,new Intent(this,IntercomService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"active").setSmallIcon(R.drawable.ic_notification).setContentTitle("Intercom Pocket").setContentText(text).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Ausschalten",stop).build()).build();
    }
    void update(){
        if(instance!=this)return;Engine e=engine;if(e==null)return;
        NotificationManager nm=getSystemService(NotificationManager.class);
        phone.sync(e);
        boolean ringing=e.state.equals("ringing")&&!e.phoneManaged;
        if(ringing&&!ringingId.equals(e.callId)){
            stopRing();ringingId=e.callId;
            try{ring=RingtoneManager.getRingtone(this,RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));if(ring!=null){ring.setLooping(true);ring.play();}}catch(Exception ignored){}
            int request=e.callId.hashCode()&0x3fffffff;
            Intent show=new Intent(this,MainActivity.class).putExtra("call",e.callId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open=PendingIntent.getActivity(this,request,show,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent answer=PendingIntent.getActivity(this,request+1,new Intent(show).putExtra("answer",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent reject=PendingIntent.getService(this,request+2,new Intent(this,IntercomService.class).setAction(DECLINE).putExtra("call",e.callId),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Person caller=new Person.Builder().setName(e.peer==null?"Kiosk":e.peer.name).setImportant(true).build();
            Notification n=new Notification.Builder(this,"calls").setSmallIcon(R.drawable.ic_notification).setContentTitle(caller.getName()).setContentText("Intercom-Anruf").setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PUBLIC).setContentIntent(open).setFullScreenIntent(open,true).setOngoing(true).setStyle(Notification.CallStyle.forIncomingCall(caller,reject,answer)).build();
            nm.notify(2,n);
        }else if(!ringing){stopRing();nm.cancel(2);}
        if(!e.active()&&microphone){startForeground(1,ongoing("Im WLAN erreichbar"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);microphone=false;}
        else nm.notify(1,ongoing(e.active()?"Gespräch mit "+(e.peer==null?"Kiosk":e.peer.name):e.config.dnd()?"Nicht stören ist aktiv":"Im WLAN erreichbar"));
    }
    private void stopRing(){if(ring!=null){ring.stop();ring=null;}ringingId="";}
    public void onDestroy(){
        instance=null;if(engine!=null){engine.stop();engine=null;}phone.close();stopRing();getSystemService(NotificationManager.class).cancel(2);
        if(wake!=null&&wake.isHeld())wake.release();if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    public IBinder onBind(Intent intent){return null;}
}
