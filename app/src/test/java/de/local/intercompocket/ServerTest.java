package de.local.intercompocket;

import org.junit.*;
import org.json.JSONObject;
import okhttp3.*;
import okio.ByteString;
import java.util.Arrays;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ServerTest {
    IntercomServer server;
    OkHttpClient client;
    Wire phone=new Wire("shared-key"),kiosk=new Wire("shared-key");
    volatile String active="",receivedAction="";
    volatile boolean attached;
    @Before public void start()throws Exception{
        client=new OkHttpClient.Builder().readTimeout(3,TimeUnit.SECONDS).build();
        server=new IntercomServer(0,new IntercomServer.Listener(){
            public JSONObject identity(){return Wire.obj("id","phone","name","A56","enabled",true,"key",Wire.fingerprint("shared-key"),"dnd",false,"endpoint",Wire.obj("port",server.getListeningPort(),"tls",false));}
            public JSONObject incoming(JSONObject body,String token,String host){String id=body.optString("call");if(phone.verify(token,id)==null)return Wire.obj("code",403,"status","key");active=id;return Wire.obj("status","ringing");}
            public JSONObject signal(String id,JSONObject b,String token,String host){if(phone.verify(token,id)==null||!id.equals(active))return Wire.obj("code",403,"ok",false);receivedAction=b.optString("action");return Wire.obj("ok",true);}
            public boolean allowAudio(String id,String t,String host){return id.equals(active)&&phone.verify(t,id)!=null;}
            public void attach(String id,IntercomServer.Link link){attached=true;link.text("{\"type\":\"talk\",\"on\":false}");}
            public void message(String id,IntercomServer.Link l,String text,byte[] bytes){if(bytes!=null)l.bytes(bytes);else l.text(text);}
            public void disconnected(String id,IntercomServer.Link l){}
        },false,false);server.start(5000,true);
    }
    @After public void stop(){server.stop();client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
    String url(String path){return "http://127.0.0.1:"+server.getListeningPort()+path;}
    Response post(String path,String body,String token)throws Exception{return client.newCall(new Request.Builder().url(url(path)).header("Authorization","Bearer "+token).post(RequestBody.create(body,MediaType.get("application/json"))).build()).execute();}
    @Test public void publicIdentityMatchesKioskContractAndLimitsProbes()throws Exception{
        try(Response r=client.newCall(new Request.Builder().url(url("/api/intercom/identity")).build()).execute()){assertEquals(200,r.code());JSONObject j=new JSONObject(r.body().string());assertEquals("A56",j.getString("name"));assertTrue(j.getBoolean("enabled"));assertEquals(8,j.getString("key").length());assertEquals(server.getListeningPort(),j.getJSONObject("endpoint").getInt("port"));}
        try(Response r=client.newCall(new Request.Builder().url(url("/api/intercom/identity")).build()).execute()){assertEquals(429,r.code());}
    }
    @Test public void callAndHangupUseFreshAuthenticatedTokens()throws Exception{
        String token=kiosk.token("call-1","kitchen");
        try(Response r=post("/api/intercom/call","{\"call\":\"call-1\",\"kind\":\"call\",\"from\":{\"id\":\"kitchen\"}}",token)){assertEquals(200,r.code());assertEquals("ringing",new JSONObject(r.body().string()).getString("status"));}
        try(Response r=post("/api/intercom/call/call-1","{\"action\":\"hangup\"}",token)){assertEquals(403,r.code());}
        try(Response r=post("/api/intercom/call/call-1","{\"action\":\"hangup\"}",kiosk.token("call-1","kitchen"))){assertEquals(200,r.code());assertEquals("hangup",receivedAction);}
    }
    @Test public void chunkedAnswerCallbackIsAccepted()throws Exception{
        active="chunked-call";
        RequestBody body=new RequestBody(){public MediaType contentType(){return MediaType.get("application/json");}public void writeTo(okio.BufferedSink sink)throws java.io.IOException{sink.writeUtf8("{\"action\":\"answer\"}");}};
        try(Response r=client.newCall(new Request.Builder().url(url("/api/intercom/call/"+active)).header("Authorization","Bearer "+kiosk.token(active,"kitchen")).post(body).build()).execute()){
            assertEquals(200,r.code());assertEquals("answer",receivedAction);
        }
    }
    @Test public void binaryPcmAndTalkControlRoundTripOverRealWebSocket()throws Exception{
        active="audio-1";CountDownLatch binary=new CountDownLatch(1),talk=new CountDownLatch(1);byte[] expected=new byte[2560];for(int i=0;i<expected.length;i++)expected[i]=(byte)(i%251);
        Throwable[] failure={null};
        WebSocket socket=client.newWebSocket(new Request.Builder().url(url("/api/intercom/audio/audio-1?token="+java.net.URLEncoder.encode(kiosk.token("audio-1","kitchen"),"UTF-8"))).build(),new WebSocketListener(){
            public void onOpen(WebSocket s,Response r){s.send(ByteString.of(expected));}
            public void onMessage(WebSocket s,String t){if(t.contains("talk"))talk.countDown();}
            public void onMessage(WebSocket s,ByteString b){if(!Arrays.equals(expected,b.toByteArray()))failure[0]=new AssertionError("PCM changed");binary.countDown();}
            public void onFailure(WebSocket s,Throwable t,Response r){failure[0]=t;binary.countDown();}
        });
        assertTrue(binary.await(5,TimeUnit.SECONDS));assertNull(failure[0]);assertTrue(talk.await(2,TimeUnit.SECONDS));assertTrue(attached);socket.close(1000,"done");
    }
    @Test public void unauthenticatedSocketAndOversizedBodyAreRefused()throws Exception{
        try(Response r=client.newCall(new Request.Builder().url(url("/api/intercom/audio/call-1?token=bad")).header("Upgrade","websocket").header("Connection","Upgrade").build()).execute()){assertEquals(403,r.code());}
        try(Response r=post("/api/intercom/call","x".repeat(65537),"invalid")){assertEquals(413,r.code());}
    }
    @Test public void tlsRequiredDoesNotAcceptPlaintextInvites()throws Exception{
        IntercomServer secureOnly=new IntercomServer(0,new IntercomServer.Listener(){public JSONObject identity(){return Wire.obj("enabled",true);}public JSONObject incoming(JSONObject b,String t,String h){throw new AssertionError();}public JSONObject signal(String i,JSONObject b,String t,String h){throw new AssertionError();}public boolean allowAudio(String i,String t,String h){throw new AssertionError();}public void attach(String i,IntercomServer.Link l){}public void message(String i,IntercomServer.Link l,String t,byte[]b){}public void disconnected(String i,IntercomServer.Link l){}},false,true);
        try{secureOnly.start(5000,true);try(Response r=client.newCall(new Request.Builder().url("http://127.0.0.1:"+secureOnly.getListeningPort()+"/api/intercom/call").post(RequestBody.create("{}",MediaType.get("application/json"))).build()).execute()){assertEquals(426,r.code());}}
        finally{secureOnly.stop();}
    }
}
