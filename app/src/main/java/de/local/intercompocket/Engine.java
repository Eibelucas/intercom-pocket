package de.local.intercompocket;

import android.content.Context;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import okhttp3.*;
import okio.ByteString;

final class Engine implements IntercomServer.Listener {
    interface Observer{void changed();}
    final Config config;
    final Tls tls;
    final Wire wire;
    final ConcurrentHashMap<String,Peer> peers=new ConcurrentHashMap<>();
    final ExecutorService io=Executors.newFixedThreadPool(4);
    final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    final AudioPipe audio;
    final CallDiagnostics diagnostics=new CallDiagnostics();
    final Observer observer;
    volatile String state="idle", detail="", callId="";
    volatile Peer peer;
    volatile boolean outgoing, broadcast, sending, handsFree, speaker=true, running;
    volatile boolean phoneManaged,phoneMuted,endedLocally;
    volatile long since;
    volatile IntercomServer.Link link;
    private IntercomServer plain, encrypted;
    private ScheduledFuture<?> timeout;
    private Discovery discovery;
    private final Context context;
    Engine(Context c,Config config,Observer observer){this(c,config,observer,null);}
    Engine(Context c,Config config,Observer observer,AudioPipe suppliedAudio){
        this.context=c;this.config=config;this.observer=observer;wire=new Wire(config.key());tls=new Tls(config);
        audio=suppliedAudio!=null?suppliedAudio:new AudioPipe(c,new AudioPipe.Sink(){public void frame(byte[] b){IntercomServer.Link l=link;if(l!=null&&sending)l.bytes(b);}public void failed(String m){diagnostics.event(m);io.execute(()->finish(m,true));}});
        audio.diagnostics(diagnostics);
    }
    void start() throws Exception {
        try {
            plain=new IntercomServer(2324,this,false,config.tls());plain.start(45000,true);
            if(config.tls()){encrypted=new IntercomServer(2325,this,true,true);encrypted.makeSecure(tls.server(),null);encrypted.start(45000,true);}
            running=true;
            JSONArray saved=new JSONArray(config.prefs.getString("peers","[]"));
            for(int i=0;i<saved.length();i++){JSONObject j=saved.getJSONObject(i);Peer p=new Peer(j.getString("host"),j.getInt("port"),j.optBoolean("tls"));p.manual=true;peers.put(p.addressKey(),p);}
            discovery=new Discovery(context,this);discovery.start();
            timer.scheduleWithFixedDelay(this::refresh,1,20,TimeUnit.SECONDS);
            timer.scheduleWithFixedDelay(()->{IntercomServer.Link l=link;if(l!=null)l.ping();},15,15,TimeUnit.SECONDS);
            changed();
        }catch(Exception e){stop();throw e;}
    }
    void changed(){observer.changed();}
    boolean active(){return !state.equals("idle");}
    int port(){return config.tls()?2325:2324;}
    public JSONObject identity(){return Wire.obj("id",config.id(),"name",config.name(),"version","2026.9.78-pocket.1","enabled",running&&!config.key().isEmpty(),"key",Wire.fingerprint(config.key()),"dnd",config.dnd(),"endpoint",Wire.obj("port",port(),"tls",config.tls()));}
    private JSONObject self(){return Wire.obj("id",config.id(),"name",config.name(),"address",Discovery.localIp(),"port",port(),"version","2026.9.78-pocket.1","tls",config.tls());}
    void found(Peer p){if(config.id().equals(p.id))return;peers.compute(p.addressKey(),(k,old)->old==null?p:old);io.execute(()->probe(peers.get(p.addressKey())));}
    void add(String address) throws Exception {
        HttpUrl url=HttpUrl.parse(address.contains("://")?address:"http://"+address);
        if(url==null||!url.username().isEmpty()||!url.password().isEmpty()||!url.encodedPath().equals("/")||url.query()!=null)throw new Exception("Bitte nur IP oder Hostname mit optionalem Port eingeben.");
        int port=url.port();if(port==80||port==443)port=2324;
        Peer p=new Peer(url.host(),port,url.isHttps());p.manual=true;peers.put(p.addressKey(),p);savePeers();io.execute(()->probe(p));
    }
    void remove(Peer p){peers.remove(p.addressKey());savePeers();changed();}
    void savePeers(){JSONArray a=new JSONArray();for(Peer p:peers.values())if(p.manual)a.put(Wire.obj("host",p.host,"port",p.adminPort,"tls",p.adminTls));config.prefs.edit().putString("peers",a.toString()).apply();}
    void refresh(){if(!running)return;for(Peer p:peers.values())io.execute(()->probe(p));}
    void probe(Peer p){
        if(p==null||!running)return;
        synchronized(p){
            if(System.currentTimeMillis()-p.checked<1500)return;p.checked=System.currentTimeMillis();p.ready=false;
            try{
                if(p.adminTls&&!checkPin(p,true)){changed();return;}
                JSONObject j=get(p,true,"/api/intercom/identity");
                p.id=j.getString("id");p.name=j.optString("name","Kiosk");p.version=j.optString("version");
                if(config.id().equals(p.id)){peers.remove(p.addressKey());return;}
                JSONObject endpoint=j.optJSONObject("endpoint");
                p.port=endpoint==null?p.adminPort:endpoint.getInt("port");p.tls=endpoint==null?p.adminTls:endpoint.getBoolean("tls");
                if(p.port<1||p.port>65535)throw new Exception("Ungültiger Port");
                if(!j.optBoolean("enabled"))p.status="Intercom ausgeschaltet";
                else if(!Wire.fingerprint(config.key()).equals(j.optString("key")))p.status="Anderer Intercom-Schlüssel";
                else if(p.tls!=config.tls())p.status="TLS-Einstellung unterschiedlich";
                else if(j.optBoolean("dnd"))p.status="Nicht stören";
                else if(p.tls&&!checkPin(p,false)){}
                else{p.status="Bereit";p.ready=true;}
            }catch(Exception e){p.status="Nicht erreichbar";}
        }
        changed();
    }
    private boolean checkPin(Peer p,boolean admin)throws Exception{
        String current=tls.inspect(p,admin);
        if(!current.equals(tls.saved(p,admin))){p.candidatePin=current;p.candidatePort=admin?p.adminPort:p.port;p.status="Zertifikat bestätigen";return false;}
        p.candidatePin="";return true;
    }
    void trust(Peer p){config.prefs.edit().putString("pin:"+p.host+":"+p.candidatePort,p.candidatePin).apply();p.candidatePin="";p.checked=0;io.execute(()->probe(p));}
    private JSONObject get(Peer p,boolean admin,String path)throws Exception{
        OkHttpClient client=tls.client(p,admin);
        try(Response r=client.newCall(new Request.Builder().url(p.base(admin)+path).build()).execute()){
            if(r.code()!=200)throw new Exception("HTTP "+r.code());return new JSONObject(r.body().string());
        }finally{client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
    }
    private JSONObject post(Peer p,String path,JSONObject body,String id)throws Exception{
        if(p.tls!=config.tls())throw new Exception("TLS-Einstellung unterschiedlich");
        OkHttpClient client=tls.client(p,false);
        try(Response r=client.newCall(new Request.Builder().url(p.base(false)+path).header("Authorization","Bearer "+wire.token(id,config.id())).post(RequestBody.create(body.toString(),MediaType.get("application/json; charset=utf-8"))).build()).execute()){
            diagnostics.event("Anruf-Anfrage beantwortet (HTTP "+r.code()+")");
            if(r.code()==403)return Wire.obj("status","key");
            if(!r.isSuccessful())throw new java.io.IOException("HTTP "+r.code());
            return new JSONObject(r.body().string());
        }finally{client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
    }
    synchronized void call(Peer target){
        if(!running||active()||!target.ready)return;
        diagnostics.clear();diagnostics.event("Ausgehender Anruf");
        callId=UUID.randomUUID().toString();peer=target;outgoing=true;broadcast=false;since=0;handsFree=false;sending=false;speaker=true;state="calling";detail="";
        String id=callId;arm(id,65000,"Keine Annahmebestätigung vom Kiosk empfangen – Rückweg zum Handy prüfen");changed();
        io.execute(()->{
            try{JSONObject r=post(target,"/api/intercom/call",Wire.obj("call",id,"kind","call","from",self()),id);String status=r.optString("status");diagnostics.event("Einladung beantwortet: "+reasonForDiagnostics(status));if(!status.equals("ringing")&&!status.equals("auto"))endIf(id,reason(status),false);}
            catch(Exception e){diagnostics.error("Anruf senden",e);endIf(id,"Kiosk nicht erreichbar",false);}
        });
    }
    public synchronized JSONObject incoming(JSONObject body,String token,String host){
        String id=body.optString("call");JSONObject from=body.optJSONObject("from");
        if(!Wire.validCallId(id)||from==null)return Wire.obj("code",400,"status","invalid");
        JSONObject claims=wire.verify(token,id);
        if(claims==null||!claims.optString("from").equals(from.optString("id")))return Wire.obj("code",403,"status","key");
        if(!running)return Wire.obj("status","off");
        if(from.optBoolean("tls")!=config.tls())return Wire.obj("code",409,"status","tls");
        if(config.dnd())return Wire.obj("status","dnd");if(active())return Wire.obj("status","busy");
        Peer incoming=Peer.from(from,host);if(incoming.port<1||incoming.port>65535)return Wire.obj("code",400,"status","invalid");
        String kind=body.optString("kind","call");if(!kind.equals("call")&&!kind.equals("broadcast"))return Wire.obj("code",400,"status","invalid");
        diagnostics.clear();diagnostics.event("Eingehender Anruf");
        peer=incoming;callId=id;outgoing=false;broadcast=kind.equals("broadcast");handsFree=false;sending=false;speaker=true;since=0;detail="";
        if(broadcast){state="listening";arm(id,12000,"Durchsage nicht verbunden");try{audio.start(false);}catch(Exception e){finish("Audiowiedergabe nicht verfügbar",false);return Wire.obj("status","off");}}
        else{state="ringing";arm(id,45000,"Anruf verpasst");if(incoming.tls)io.execute(()->{try{checkPin(incoming,false);}catch(Exception ignored){}changed();});}
        changed();return Wire.obj("status",broadcast?"listening":"ringing");
    }
    synchronized void answer(){
        if(!state.equals("ringing"))return;
        if(peer.tls&&tls.saved(peer,false).isEmpty()){detail="Bitte zuerst das Zertifikat bestätigen";changed();return;}
        String id=callId;Peer p=peer;
        try{audio.start(true);}catch(Exception e){audioFailed(e);return;}
        state="connecting";arm(id,12000,"Audioverbindung fehlgeschlagen");changed();
        diagnostics.event("Annahme wird an den Anrufer gesendet");
        io.execute(()->{try{JSONObject r=post(p,"/api/intercom/call/"+id,Wire.obj("action","answer"),id);if(!r.optBoolean("ok",false))endIf(id,"Anruf konnte nicht angenommen werden",true);}catch(Exception e){diagnostics.error("Annahme-Rückmeldung",e);endIf(id,"Anrufer nicht erreichbar",true);}});
    }
    public synchronized JSONObject signal(String id,JSONObject body,String token,String host){
        JSONObject claims=wire.verify(token,id);
        if(claims==null||!id.equals(callId)||peer==null||!peer.id.equals(claims.optString("from"))){diagnostics.event("Anrufsignal abgelehnt: Anmeldung oder Anrufzuordnung ungültig");return Wire.obj("code",403,"ok",false);}
        String action=body.optString("action");
        if(action.equals("answer")){
            diagnostics.event("Annahme des Kiosks empfangen");
            if(!outgoing||!state.equals("calling"))return Wire.obj("code",409,"ok",false);
            state="connecting";arm(id,12000,"Audioverbindung fehlgeschlagen");changed();Peer p=peer;
            io.execute(()->connect(p,id));return Wire.obj("ok",true);
        }
        if(action.equals("decline")||action.equals("missed")||action.equals("cancel")||action.equals("hangup")){diagnostics.event("Gegenstelle beendet: "+reason(action));finish(reason(action),false);return Wire.obj("ok",true);}
        return Wire.obj("code",400,"ok",false);
    }
    public synchronized boolean allowAudio(String id,String token,String host){
        JSONObject claims=wire.verify(token,id);
        return claims!=null&&id.equals(callId)&&!outgoing&&peer!=null&&peer.id.equals(claims.optString("from"))&&(state.equals("connecting")||state.equals("listening"))&&link==null;
    }
    private void connect(Peer p,String id){
        try{
            diagnostics.event("Audio-WebSocket wird geöffnet");
            OkHttpClient client=tls.client(p,false);
            Request request=new Request.Builder().url(p.base(false)+"/api/intercom/audio/"+id+"?token="+java.net.URLEncoder.encode(wire.token(id,config.id()),"UTF-8")).build();
            client.newWebSocket(request,new WebSocketListener(){
                IntercomServer.Link wrapped;
                public void onOpen(WebSocket s,Response r){
                    diagnostics.event("Audio-WebSocket verbunden (HTTP "+r.code()+")");
                    wrapped=new IntercomServer.Link(){public void text(String t){s.send(t);}public void bytes(byte[] b){if(s.queueSize()>Wire.FRAME_BYTES*6L){s.cancel();return;}s.send(ByteString.of(b));}public void close(){s.close(1000,"ended");client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();}public void ping(){}};
                    attach(id,wrapped);
                }
                public void onMessage(WebSocket s,String text){message(id,wrapped,text,null);}
                public void onMessage(WebSocket s,ByteString b){message(id,wrapped,null,b.toByteArray());}
                public void onClosing(WebSocket s,int code,String reason){diagnostics.event("Gegenstelle schließt Audio-WebSocket (Code "+code+")");s.close(1000,"ended");disconnected(id,wrapped);}
                public void onClosed(WebSocket s,int code,String reason){disconnected(id,wrapped);client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();}
                public void onFailure(WebSocket s,Throwable t,Response r){diagnostics.error("Audio-WebSocket"+(r==null?"":" HTTP "+r.code()),t);endIf(id,"Audioverbindung unterbrochen"+(r==null?"":" (HTTP "+r.code()+")"),false);client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();}
            });
        }catch(Exception e){diagnostics.error("Audio-WebSocket öffnen",e);endIf(id,"Audioverbindung fehlgeschlagen",true);}
    }
    public synchronized void attach(String id,IntercomServer.Link l){
        if(!id.equals(callId)||!(state.equals("connecting")||state.equals("listening"))||link!=null){l.close();return;}
        try{audio.start(!broadcast);}catch(Exception e){l.close();audioFailed(e);return;}
        link=l;if(timeout!=null)timeout.cancel(false);since=System.currentTimeMillis();state=broadcast?"listening":"in_call";if(!broadcast&&!audio.microphoneAvailable)detail="Nur Hören: "+audio.microphoneProblem;diagnostics.event("Gespräch verbunden");
        if(phoneManaged&&!broadcast){handsFree=true;talk(!phoneMuted);}else l.text(Wire.obj("type","talk","on",false).toString());changed();
    }
    public void message(String id,IntercomServer.Link l,String text,byte[] bytes){
        if(!id.equals(callId)||l!=link)return;
        if(bytes!=null){audio.receive(bytes);return;}
        try{JSONObject j=new JSONObject(text);if(j.optString("type").equals("end"))endIf(id,"Gespräch beendet",false);else if(j.optString("type").equals("talk")){detail=j.optBoolean("on")?"Die andere Seite spricht":"Verbunden";changed();}}catch(Exception ignored){}
    }
    public void disconnected(String id,IntercomServer.Link l){if(l!=null&&l==link)endIf(id,"Verbindung beendet",false);}
    synchronized void talk(boolean on){if(!state.equals("in_call"))return;sending=on&&audio.microphoneAvailable;audio.sending(sending);if(link!=null)link.text(Wire.obj("type","talk","on",sending).toString());changed();}
    synchronized void handsFree(boolean on){handsFree=on;talk(on);}
    synchronized void phoneMute(boolean muted){phoneMuted=muted;if(phoneManaged&&state.equals("in_call")&&sending!=(!muted&&audio.microphoneAvailable))talk(!muted);}
    public void httpEvent(String event){if(active())diagnostics.event(event);}
    synchronized void endIf(String id,String reason,boolean notify){if(id.equals(callId))finish(reason,notify);}
    synchronized void finish(String reason,boolean notify){
        if(!active())return;
        diagnostics.event((notify?"Lokal beendet: ":"Gegenstelle/Verbindung beendet: ")+reason);
        if(state.equals("calling"))diagnostics.event("Bis zum Ende keine Annahmebestätigung empfangen");
        endedLocally=notify;
        String id=callId;Peer old=peer;String oldState=state;IntercomServer.Link oldLink=link;
        state="idle";callId="";link=null;peer=null;sending=false;handsFree=false;since=0;detail=reason;
        if(timeout!=null)timeout.cancel(false);audio.stop();phoneManaged=false;phoneMuted=false;
        if(oldLink!=null){if(notify)oldLink.text(Wire.obj("type","end").toString());oldLink.close();}
        if(notify&&old!=null)io.execute(()->{try{post(old,"/api/intercom/call/"+id,Wire.obj("action",oldState.equals("ringing")?(reason.equals("Anruf verpasst")?"missed":"decline"):oldState.equals("calling")?"cancel":"hangup"),id);}catch(Exception ignored){}});
        changed();
    }
    void arm(String id,long ms,String reason){if(timeout!=null)timeout.cancel(false);timeout=timer.schedule(()->endIf(id,reason,true),ms,TimeUnit.MILLISECONDS);}
    private void audioFailed(Exception e){diagnostics.error("Audiostart / "+audio.startupStep,e);finish("Audio nicht verfügbar: "+audio.startupStep+" ("+e.getClass().getSimpleName()+")",true);}
    private String reasonForDiagnostics(String status){return switch(status){case "ringing","auto","listening","busy","dnd","off","key","tls"->status;default->"unerwartete Antwort";};}
    void stop(){running=false;finish("Intercom ausgeschaltet",true);if(discovery!=null)discovery.stop();if(plain!=null)plain.stop();if(encrypted!=null)encrypted.stop();timer.shutdownNow();io.shutdown();changed();}
    static String reason(String s){return switch(s){case "key"->"Anderer Intercom-Schlüssel";case "tls"->"TLS-Einstellung unterschiedlich";case "busy"->"Kiosk ist besetzt";case "dnd"->"Nicht stören ist aktiv";case "off"->"Intercom ist ausgeschaltet";case "decline"->"Anruf abgelehnt";case "missed"->"Keine Antwort";case "cancel"->"Anruf abgebrochen";case "hangup"->"Gespräch beendet";default->"Verbindung nicht möglich";};}
}
