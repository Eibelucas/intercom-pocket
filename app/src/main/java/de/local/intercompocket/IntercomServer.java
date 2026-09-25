package de.local.intercompocket;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

final class IntercomServer extends NanoWSD {
    interface Listener {
        JSONObject identity();
        JSONObject incoming(JSONObject body,String token,String host);
        JSONObject signal(String id,JSONObject body,String token,String host);
        boolean allowAudio(String id,String token,String host);
        void attach(String id,Link link);
        void message(String id,Link link,String text,byte[] bytes);
        void disconnected(String id,Link link);
        default void httpEvent(String event){}
    }
    interface Link {void text(String text);void bytes(byte[] bytes);void close();void ping();}
    private final Listener listener;
    private final boolean secure;
    private final boolean requireTls;
    private final Map<String,Long> identityTimes=new HashMap<>();
    IntercomServer(int port,Listener listener,boolean secure,boolean requireTls){super(port);this.listener=listener;this.secure=secure;this.requireTls=requireTls;}
    static Response json(int code,JSONObject j){Response.IStatus status=Response.Status.lookup(code);if(status==null)status=new Response.IStatus(){public int getRequestStatus(){return code;}public String getDescription(){return code+" Intercom";}};Response r=newFixedLengthResponse(status,"application/json",j.toString());r.addHeader("Cache-Control","no-store");return r;}
    @Override public Response serve(IHTTPSession s){
        String path=s.getUri();
        if(path.equals("/api/intercom/identity")&&s.getMethod()==Method.GET){
            synchronized(identityTimes){long now=System.currentTimeMillis();identityTimes.entrySet().removeIf(e->e.getValue()<now-1000);if(identityTimes.containsKey(s.getRemoteIpAddress()))return json(429,Wire.obj("error","too many probes"));if(identityTimes.size()>512)return json(503,Wire.obj("error","busy"));identityTimes.put(s.getRemoteIpAddress(),now);}
            return json(200,listener.identity());
        }
        if(requireTls&&!secure)return json(426,Wire.obj("status","tls"));
        String audioPrefix="/api/intercom/audio/";
        if(path.startsWith(audioPrefix)){
            String id=path.substring(audioPrefix.length()),token=s.getParms().get("token");
            if(!Wire.validCallId(id)||!"websocket".equalsIgnoreCase(s.getHeaders().get("upgrade"))||!listener.allowAudio(id,token,s.getRemoteIpAddress()))return json(403,Wire.obj("error","refused"));
            return super.serve(s);
        }
        if(s.getMethod()!=Method.POST)return json(404,Wire.obj("error","not found"));
        if(!path.equals("/api/intercom/call")&&!path.startsWith("/api/intercom/call/"))return json(404,Wire.obj("error","not found"));
        try{
            listener.httpEvent("Anrufsignal über HTTP empfangen");
            JSONObject body=new JSONObject(HttpBody.read(s.getInputStream(),s.getHeaders()));
            String auth=s.getHeaders().getOrDefault("authorization","");String token=auth.startsWith("Bearer ")?auth.substring(7):"";
            JSONObject answer;
            if(path.equals("/api/intercom/call"))answer=listener.incoming(body,token,s.getRemoteIpAddress());
            else if(path.startsWith("/api/intercom/call/"))answer=listener.signal(path.substring("/api/intercom/call/".length()),body,token,s.getRemoteIpAddress());
            else return json(404,Wire.obj("error","not found"));
            int code=answer.optInt("code",200);listener.httpEvent("Anrufsignal beantwortet (HTTP "+code+")");
            return postResponse(code,answer);
        }catch(HttpBody.Invalid e){listener.httpEvent("Anrufsignal abgelehnt: HTTP-Datenformat (HTTP "+e.code+")");return postResponse(e.code,Wire.obj("error","invalid body"));}
        catch(Exception e){listener.httpEvent("Anrufsignal abgelehnt: Anfrage nicht lesbar (HTTP 400)");return postResponse(400,Wire.obj("error","invalid request"));}
    }
    private static Response postResponse(int code,JSONObject body){Response r=json(code,body);r.closeConnection(true);return r;}
    @Override protected WebSocket openWebSocket(IHTTPSession s){return new IncomingSocket(s,s.getUri().substring("/api/intercom/audio/".length()));}
    private final class IncomingSocket extends WebSocket implements Link{
        final String id;
        IncomingSocket(IHTTPSession s,String id){super(s);this.id=id;}
        protected void onOpen(){listener.attach(id,this);}
        protected void onClose(WebSocketFrame.CloseCode code,String reason,boolean remote){listener.disconnected(id,this);}
        protected void onMessage(WebSocketFrame frame){if(frame.getOpCode()==WebSocketFrame.OpCode.Binary)listener.message(id,this,null,frame.getBinaryPayload());else if(frame.getOpCode()==WebSocketFrame.OpCode.Text)listener.message(id,this,frame.getTextPayload(),null);}
        protected void onPong(WebSocketFrame frame){}
        protected void onException(IOException e){listener.disconnected(id,this);}
        public void text(String s){try{send(s);}catch(IOException e){listener.disconnected(id,this);}}
        public void bytes(byte[] b){try{send(b);}catch(IOException e){listener.disconnected(id,this);}}
        public void ping(){try{ping(new byte[0]);}catch(IOException e){listener.disconnected(id,this);}}
        public void close(){try{close(WebSocketFrame.CloseCode.NormalClosure,"ended",false);}catch(IOException ignored){}}
    }
}
