package de.local.intercompocket;

import org.junit.*;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AnnouncementTest {
    Engine engine;
    AudioPipe audio;
    IntercomServer accepting,declining;
    CountDownLatch connected=new CountDownLatch(1);
    Wire wire=new Wire("shared");
    AtomicInteger goodInvites=new AtomicInteger(),badInvites=new AtomicInteger();
    IntercomServer.Listener kiosk(String status){return kiosk(status,true);}
    IntercomServer.Listener kiosk(String status,boolean acceptsAudio){return new IntercomServer.Listener(){
        public JSONObject identity(){return Wire.obj("id",status);}
        public JSONObject incoming(JSONObject body,String token,String host){if(wire.verify(token,body.optString("call"))==null)return Wire.obj("code",403);(status.equals("listening")?goodInvites:badInvites).incrementAndGet();return Wire.obj("status",status);}
        public JSONObject signal(String id,JSONObject body,String token,String host){return Wire.obj("ok",wire.verify(token,id)!=null);}
        public boolean allowAudio(String id,String token,String host){return acceptsAudio&&wire.verify(token,id)!=null;}
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
        Peer good=new Peer("127.0.0.1",accepting.getListeningPort(),false);good.id="good";good.name="Küche";good.ready=true;
        Peer bad=new Peer("127.0.0.1",declining.getListeningPort(),false);bad.id="bad";bad.name="Flur";bad.ready=true;
        engine.peers.put(good.addressKey(),good);engine.peers.put(bad.addressKey(),bad);
    }
    @After public void cleanup(){engine.stop();accepting.stop();declining.stop();}
    @Test public void rejectionOfOneKioskKeepsOtherAnnouncementOpen()throws Exception{
        engine.announce();assertTrue(connected.await(10,TimeUnit.SECONDS));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(engine.announcementLinks.isEmpty()&&System.nanoTime()<until)Thread.sleep(10);
        assertEquals("broadcasting",engine.state);assertEquals(1,engine.announcementLinks.size());verify(audio).start(true);
        awaitDetail("Flur · Kiosk ist besetzt");assertEquals("broadcasting",engine.state);
        engine.talk(true);assertTrue(engine.sending);verify(audio).sending(true);
        engine.finish("Durchsage beendet",true);assertEquals("idle",engine.state);
    }
    void awaitConnected()throws Exception{
        assertTrue(connected.await(10,TimeUnit.SECONDS));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(engine.announcementLinks.isEmpty()&&System.nanoTime()<until)Thread.sleep(10);
        assertEquals("broadcasting",engine.state);assertEquals(1,engine.announcementLinks.size());
    }
    void finishAndDrain()throws Exception{engine.finish("Test beendet",true);engine.io.shutdown();assertTrue(engine.io.awaitTermination(10,TimeUnit.SECONDS));}
    void awaitDetail(String expected)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!engine.detail.contains(expected)&&System.nanoTime()<until)Thread.sleep(10);assertTrue(engine.detail,engine.detail.contains(expected));}
    @Test public void selectedGroupNeverInvitesOtherAvailableKiosks()throws Exception{
        engine.announce(Set.of("good"),"Erdgeschoss");awaitConnected();
        assertEquals("Erdgeschoss",engine.announcementTitle);assertTrue(engine.detail.contains("Küche · Verbunden"));assertFalse(engine.detail.contains("Flur"));
        finishAndDrain();assertEquals(1,goodInvites.get());assertEquals(0,badInvites.get());
    }
    @Test public void emptyOrOfflineGroupNeverFallsBackToEveryone()throws Exception{
        engine.announce(Set.of(),"Leer");assertEquals("idle",engine.state);
        engine.announce(Set.of("offline"),"Offline");assertEquals("idle",engine.state);assertTrue(engine.detail.contains("erreichbar"));
        assertEquals(0,goodInvites.get());assertEquals(0,badInvites.get());verify(audio,never()).start(anyBoolean());
    }
    @Test public void sameIdentityOnTwoAddressesIsInvitedOnlyOnce()throws Exception{
        Peer duplicate=new Peer("localhost",accepting.getListeningPort(),false);duplicate.id="good";duplicate.ready=true;
        engine.peers.put(duplicate.addressKey(),duplicate);engine.announce(Set.of("good"),"Küche");awaitConnected();
        finishAndDrain();assertEquals(1,goodInvites.get());assertEquals(0,badInvites.get());
    }
    @Test public void offlineMemberIsShownWithoutStoppingAvailableRoom()throws Exception{
        Peer offline=new Peer("192.0.2.10",2324,false);offline.id="bedroom";offline.name="Schlafzimmer";offline.status="Nicht erreichbar";
        engine.peers.put(offline.addressKey(),offline);engine.announce(Set.of("good","bedroom"),"Unten");awaitConnected();
        assertTrue(engine.detail.contains("1 von 2 Kiosks verbunden"));assertTrue(engine.detail.contains("Schlafzimmer · Nicht erreichbar"));
    }
    @Test public void allRejectedEndsWithVisibleReasonButNoRoomNameInDiagnostics()throws Exception{
        engine.announce(Set.of("bad"),"Oben");awaitDetail("Durchsage beendet");
        assertEquals("idle",engine.state);assertTrue(engine.detail.contains("Flur · Kiosk ist besetzt"));assertFalse(engine.diagnostics.report().contains("Flur"));
    }
    @Test public void failedAudioHandshakeDoesNotInterruptOtherRecipients()throws Exception{
        IntercomServer broken=new IntercomServer(0,kiosk("listening",false),false,false);broken.start(15000,true);
        try{
            Peer peer=new Peer("127.0.0.1",broken.getListeningPort(),false);peer.id="broken";peer.name="Büro";peer.ready=true;engine.peers.put(peer.addressKey(),peer);
            engine.announce(Set.of("good","broken"),"Unten");awaitConnected();awaitDetail("Büro · Verbindung beendet");
            assertEquals("broadcasting",engine.state);assertEquals(1,engine.announcementLinks.size());engine.talk(true);assertTrue(engine.sending);
        }finally{broken.stop();}
    }
}
