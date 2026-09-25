package de.local.intercompocket;

import org.junit.*;
import org.json.JSONObject;
import okhttp3.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Exercise the production call controller and both real HTTP/WebSocket listeners.
 * Only Android's hardware audio is replaced, so answer-to-audio transitions are tested. */
public class EngineCallTest {
    Engine engine;
    AudioPipe audio;
    IntercomServer phone,kiosk;
    OkHttpClient client=new OkHttpClient();
    Wire kioskWire=new Wire("shared");
    CountDownLatch invited=new CountDownLatch(1),socketOpened=new CountDownLatch(1);
    Peer target;
    @Before public void setup()throws Exception{
        Config config=mock(Config.class);when(config.key()).thenReturn("shared");when(config.id()).thenReturn("phone");when(config.name()).thenReturn("Phone");
        audio=mock(AudioPipe.class);audio.microphoneAvailable=true;
        engine=new Engine(null,config,()->{},audio);engine.running=true;
        phone=new IntercomServer(0,engine,false,false);phone.start(15000,true);
        kiosk=new IntercomServer(0,new IntercomServer.Listener(){
            public JSONObject identity(){return Wire.obj("id","kiosk");}
            public JSONObject incoming(JSONObject b,String t,String h){if(kioskWire.verify(t,b.optString("call"))==null)return Wire.obj("code",403);invited.countDown();return Wire.obj("status","ringing");}
            public JSONObject signal(String id,JSONObject b,String t,String h){return Wire.obj("ok",kioskWire.verify(t,id)!=null);}
            public boolean allowAudio(String id,String t,String h){return kioskWire.verify(t,id)!=null;}
            public void attach(String id,IntercomServer.Link l){socketOpened.countDown();l.text("{\"type\":\"talk\",\"on\":false}");}
            public void message(String id,IntercomServer.Link l,String t,byte[] b){}
            public void disconnected(String id,IntercomServer.Link l){}
        },false,false);kiosk.start(15000,true);
        target=new Peer("127.0.0.1",kiosk.getListeningPort(),false);target.id="kiosk";target.name="Kiosk";target.ready=true;
    }
    @After public void cleanup(){engine.stop();phone.stop();kiosk.stop();client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
    void answerFromKiosk()throws Exception{
        engine.call(target);assertTrue(invited.await(10,TimeUnit.SECONDS));String id=engine.callId;
        try(Response r=client.newCall(new Request.Builder().url("http://127.0.0.1:"+phone.getListeningPort()+"/api/intercom/call/"+id).header("Authorization","Bearer "+kioskWire.token(id,"kiosk")).post(RequestBody.create("{\"action\":\"answer\"}",MediaType.get("application/json"))).build()).execute()){assertEquals(200,r.code());assertTrue(new JSONObject(r.body().string()).getBoolean("ok"));}
        assertTrue(socketOpened.await(10,TimeUnit.SECONDS));
    }
    void awaitState(String state)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!state.equals(engine.state)&&System.nanoTime()<until)Thread.sleep(20);assertEquals(engine.diagnostics.report(),state,engine.state);}
    @Test public void outgoingCallStaysConnectedAfterKioskAccepts()throws Exception{
        answerFromKiosk();awaitState("in_call");assertNotNull(engine.link);verify(audio).start(true);
        engine.talk(true);assertTrue(engine.sending);verify(audio).sending(true);
        engine.finish("Gespräch beendet",true);assertEquals("idle",engine.state);
    }
    @Test public void unavailableMicrophoneKeepsListeningConnectionOpen()throws Exception{
        audio.microphoneAvailable=false;audio.microphoneProblem="AudioRecord unavailable";
        answerFromKiosk();awaitState("in_call");assertNotNull(engine.link);
        engine.talk(true);assertFalse(engine.sending);verify(audio).sending(false);
        assertTrue(engine.diagnostics.report().contains("Gespräch verbunden"));
    }
    @Test public void fatalAudioFailureKeepsStageAndExceptionForDiagnosis()throws Exception{
        audio.startupStep="Audiowiedergabe";doThrow(new IllegalStateException("secret-token-do-not-log")).when(audio).start(true);
        answerFromKiosk();awaitState("idle");String report=engine.diagnostics.report();
        assertTrue(report.contains("Audiowiedergabe"));assertTrue(report.contains("IllegalStateException"));assertFalse(report.contains("secret-token-do-not-log"));
        assertTrue(engine.detail.contains("Audiowiedergabe"));
    }
}
