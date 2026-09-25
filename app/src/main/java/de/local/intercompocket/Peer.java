package de.local.intercompocket;

import org.json.JSONObject;
public final class Peer {
    public String id="", name="Kiosk", host, version="", status="Wird geprüft …", candidatePin="";
    public int adminPort=2324, port=2324, candidatePort=2324;
    public boolean adminTls, tls, ready, manual;
    public long checked;
    public Peer(String host,int port,boolean tls) {this.host=host;this.port=port;this.adminPort=port;this.tls=tls;this.adminTls=tls;}
    public String base(boolean admin) {return ((admin?adminTls:tls)?"https://":"http://")+(host.contains(":")?"["+host+"]":host)+":"+(admin?adminPort:port);}
    public String addressKey() { return host+":"+adminPort; }
    public JSONObject json() {return Wire.obj("id",id,"name",name,"address",host,"port",port,"version",version,"tls",tls);}
    public static Peer from(JSONObject j,String actualHost) {
        Peer p=new Peer(actualHost,j.optInt("port",2324),j.optBoolean("tls"));
        p.id=j.optString("id");p.name=j.optString("name","Kiosk");p.version=j.optString("version");return p;
    }
}
