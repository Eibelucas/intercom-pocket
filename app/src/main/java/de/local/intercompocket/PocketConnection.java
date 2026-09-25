package de.local.intercompocket;

import android.telecom.*;

final class PocketConnection extends Connection {
    final PhoneCallSession session;
    PocketConnection(Engine e,String id,Runnable prepare,boolean focus){
        session=new PhoneCallSession(e,id,prepare,new PhoneCallSession.Display(){
            public void accepted(){setActive();}
            public void closed(int cause){setDisconnected(new DisconnectCause(cause));destroy();}
        },focus);
    }
    @Override public void onAnswer(){session.answer();}
    @Override public void onAnswer(int videoState){onAnswer();}
    @Override public void onReject(){session.end("Anruf abgelehnt",DisconnectCause.REJECTED);}
    @Override public void onReject(int reason){onReject();}
    @Override public void onReject(String message){onReject();}
    @Override public void onDisconnect(){session.end("In Telefon-App aufgelegt",DisconnectCause.LOCAL);}
    @Override public void onAbort(){session.end("Telefonanruf abgebrochen",DisconnectCause.CANCELED);}
    @Override public void onCallAudioStateChanged(CallAudioState audio){if(audio!=null)session.mute(audio.isMuted());}
    @Override public void onMuteStateChanged(boolean muted){session.mute(muted);}
    void close(int cause){session.close(cause);}
}
