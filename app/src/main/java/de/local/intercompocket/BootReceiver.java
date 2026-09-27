package de.local.intercompocket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context context,Intent intent){
        String action=intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action)&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))return;
        Config config=new Config(context);
        if(!config.autoStart()||!config.enabledBefore()||config.key().isEmpty())return;
        try{context.startForegroundService(new Intent(context,IntercomService.class).setAction(IntercomService.ENABLE));}
        catch(Exception ignored){}
    }
}
