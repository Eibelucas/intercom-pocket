package de.local.intercompocket;

import android.telecom.*;

public final class IntercomConnectionService extends ConnectionService {
    boolean hasFocus;
    private PhoneIntegration bridge(){IntercomService s=IntercomService.instance;return s==null?null:s.phone;}
    private String id(ConnectionRequest r){return r.getExtras()==null?"":r.getExtras().getString(PhoneIntegration.CALL_ID,"");}
    @Override public Connection onCreateIncomingConnection(PhoneAccountHandle manager,ConnectionRequest request){
        PhoneIntegration b=bridge();
        if(b!=null&&PhoneIntegration.handle(this).equals(request.getAccountHandle())){
            PocketConnection c=b.create(id(request),this);if(c!=null)return c;
        }
        return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.CANCELED));
    }
    @Override public void onCreateIncomingConnectionFailed(PhoneAccountHandle manager,ConnectionRequest request){PhoneIntegration b=bridge();if(b!=null)b.failed(id(request));}
    @Override public Connection onCreateOutgoingConnection(PhoneAccountHandle manager,ConnectionRequest request){
        return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR,"Bitte den Kiosk in Intercom Pocket anrufen."));
    }
    @Override public void onConnectionServiceFocusGained(){hasFocus=true;PhoneIntegration b=bridge();if(b!=null)b.focus(true);}
    @Override public void onConnectionServiceFocusLost(){hasFocus=false;PhoneIntegration b=bridge();if(b!=null)b.focus(false);connectionServiceFocusReleased();}
    @Override public void onDestroy(){PhoneIntegration b=bridge();if(b!=null)b.focus(false);super.onDestroy();}
}
