package de.local.intercompocket;

import org.junit.*;
import org.json.JSONObject;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AnnouncementTest {
    Engine engine;
    AudioPipe audio;
    IntercomServer accepting,declining;
    CountDownLatch connected=new CountDownLatch(1);
    Wire wire=new Wire("shared");
    IntercomServer.Listener kiosk(String status){return new IntercomServer.Listener(){
        public JSONObject identity(){return Wire.obj("id",status);}
        public JSONObject incoming(JSONObject body,String token,String host){return wire.verify(token,body.optString("call"))==null?Wire.obj("code",403):Wire.obj("status",status);}
        public JSONObject signal(String id,JSONObject body,String token,String host){return Wire.obj("ok",wire.verify(token,id)!=null);}
        public boolean allowAudio(String id,String token,String host){return wire.verify(token,id)!=null;}
        public void attach(String id,IntercomServer.Link link){connected.countDown();}
        public void message(String id,IntercomServer.Link link,String text,byte[] bytes){}
        public void disconnected(String id,IntercomServer.Link link){}
    };}
    @Before public void setup()throws Exception{
        Config config=mock(Config.class);when(config.key()).thenReturn("shared");when(config.id()).thenReturn("phone");when(config.name()).thenReturn("Phone");
        audio=mock(AudioPipe.class);audio.microphoneAvailable=true;
        engine=new Engine(null,config,()->{},audio);engine.running=true;
        accepting=new IntercomServer(0,kiosk("listening"),false,false);accepting.start(15000,true);
        declining=new IntercomServer(0,kiosk("busy"),false,false);declining.start(15000,true);
        Peer good=new Peer("127.0.0.1",accepting.getListeningPort(),false);good.id="good";good.ready=true;
        Peer bad=new Peer("127.0.0.1",declining.getListeningPort(),false);bad.id="bad";bad.ready=true;
        engine.peers.put(good.addressKey(),good);engine.peers.put(bad.addressKey(),bad);
    }
    @After public void cleanup(){engine.stop();accepting.stop();declining.stop();}
    @Test public void rejectionOfOneKioskKeepsOtherAnnouncementOpen()throws Exception{
        engine.announce();assertTrue(connected.await(10,TimeUnit.SECONDS));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(engine.announcementLinks.isEmpty()&&System.nanoTime()<until)Thread.sleep(10);
        assertEquals("broadcasting",engine.state);assertEquals(1,engine.announcementLinks.size());verify(audio).start(true);
        engine.talk(true);assertTrue(engine.sending);verify(audio).sending(true);
        engine.finish("Durchsage beendet",true);assertEquals("idle",engine.state);
    }
}
