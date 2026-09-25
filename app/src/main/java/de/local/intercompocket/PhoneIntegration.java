package de.local.intercompocket;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.*;
import java.util.Collections;

/** System-managed account: the user's existing dialer owns the call UI and audio route. */
final class PhoneIntegration {
    static final String CALL_ID="de.local.intercompocket.CALL_ID";
    private final IntercomService service;
    private String attempted="";
    private PocketConnection connection;
    PhoneIntegration(IntercomService service){this.service=service;register(service);}
    static PhoneAccountHandle handle(Context c){return new PhoneAccountHandle(new ComponentName(c,IntercomConnectionService.class),"intercom-wlan");}
    static boolean register(Context c){
        try{
            TelecomManager manager=c.getSystemService(TelecomManager.class);
            if(manager==null)return false;
            manager.registerPhoneAccount(PhoneAccount.builder(handle(c),"Intercom Pocket")
                    .setShortDescription("Kiosk-Anrufe im WLAN")
                    .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                    .setSupportedUriSchemes(Collections.singletonList(PhoneAccount.SCHEME_SIP)).build());
            return true;
        }catch(RuntimeException ignored){return false;}
    }
    static boolean enabled(Context c){
        try{
            TelecomManager manager=c.getSystemService(TelecomManager.class);if(manager==null)return false;
            if(android.os.Build.VERSION.SDK_INT>=35){for(PhoneAccount a:manager.getRegisteredPhoneAccounts())if(handle(c).equals(a.getAccountHandle()))return a.isEnabled();return false;}
            if(c.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS)!=PackageManager.PERMISSION_GRANTED)return false;
            PhoneAccount a=manager.getPhoneAccount(handle(c));return a!=null&&a.isEnabled();
        }
        catch(RuntimeException ignored){return false;}
    }
    static boolean ready(Context c){return enabled(c)&&c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    void sync(Engine e){
        if(connection!=null&&!connection.session.current()){
            connection.close(e.detail.equals("Anruf verpasst")?DisconnectCause.MISSED:e.detail.equals("Anruf abgelehnt")?DisconnectCause.REJECTED:e.endedLocally?DisconnectCause.LOCAL:DisconnectCause.REMOTE);
            connection=null;
        }
        synchronized(e){
            if(e.state.equals("ringing")&&!e.outgoing&&!e.broadcast&&!attempted.equals(e.callId)){
                attempted=e.callId;
                if(!ready(service))return;
                e.phoneManaged=true;e.audio.telecomManaged(true);
                try{
                    Bundle extras=new Bundle();extras.putString(CALL_ID,e.callId);
                    extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,Uri.fromParts("sip",e.peer.id+"@intercom.local",null));
                    service.getSystemService(TelecomManager.class).addNewIncomingCall(handle(service),extras);
                    e.diagnostics.event("Anruf an Android-Telefon-App übergeben");
                }catch(RuntimeException ex){
                    e.phoneManaged=false;e.audio.telecomManaged(false);
                    e.diagnostics.error("Android-Anrufanzeige; App-Anzeige als Ersatz",ex);
                }
            }
        }
    }
    PocketConnection create(String id,IntercomConnectionService owner){
        Engine e=service.engine;
        if(e==null)return null;
        synchronized(e){
            if(!e.phoneManaged||!id.equals(e.callId)||!e.state.equals("ringing")||connection!=null)return null;
            connection=new PocketConnection(e,id,service::phoneMicrophone,owner.hasFocus);
            connection.setAddress(Uri.fromParts("sip",e.peer.id+"@intercom.local",null),TelecomManager.PRESENTATION_ALLOWED);
            connection.setCallerDisplayName(e.peer.name,TelecomManager.PRESENTATION_ALLOWED);
            connection.setAudioModeIsVoip(true);
            connection.setConnectionCapabilities(Connection.CAPABILITY_MUTE);
            connection.setInitialized();connection.setRinging();
            e.diagnostics.event("Telefon-App zeigt eingehenden Anruf");
            return connection;
        }
    }
    void failed(String id){Engine e=service.engine;if(e!=null)e.endIf(id,"Android kann diesen Anruf gerade nicht anzeigen",true);}
    void focus(boolean gained){if(connection!=null){if(gained)connection.session.focusGained();else connection.session.focusLost();}}
    void close(){if(connection!=null){connection.close(DisconnectCause.LOCAL);connection=null;}}
}
