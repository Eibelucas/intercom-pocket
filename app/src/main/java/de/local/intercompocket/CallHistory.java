package de.local.intercompocket;

import android.content.SharedPreferences;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

final class CallHistory {
    private final SharedPreferences prefs;
    CallHistory(SharedPreferences prefs){this.prefs=prefs;}
    synchronized void add(Peer peer,boolean outgoing,boolean announcement,long started,long connected,String result){
        if(prefs==null||peer==null)return;
        JSONArray old=read(),items=new JSONArray();
        items.put(Wire.obj("id",peer.id,"name",peer.name,"host",peer.host,"port",peer.adminPort,"tls",peer.adminTls,"out",outgoing,"announcement",announcement,"at",started,"seconds",connected==0?0:Math.max(0,(System.currentTimeMillis()-connected)/1000),"result",result));
        for(int i=0;i<old.length()&&i<99;i++)items.put(old.optJSONObject(i));
        prefs.edit().putString("history",items.toString()).apply();
    }
    synchronized ArrayList<JSONObject> entries(){ArrayList<JSONObject> out=new ArrayList<>();JSONArray data=read();for(int i=0;i<data.length();i++){JSONObject row=data.optJSONObject(i);if(row!=null)out.add(row);}return out;}
    synchronized void clear(){if(prefs!=null)prefs.edit().remove("history").apply();}
    private JSONArray read(){try{return new JSONArray(prefs==null?"[]":prefs.getString("history","[]"));}catch(Exception ignored){return new JSONArray();}}
}
