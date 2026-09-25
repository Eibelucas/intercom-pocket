package de.local.intercompocket;

import org.junit.*;
import okhttp3.mockwebserver.*;
import android.telecom.DisconnectCause;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real Engine lifecycle; hardware audio and system dialer display are replaced. */
public class PhoneCallSessionTest {
    Engine engine;AudioPipe audio;MockWebServer kiosk;Wire remote=new Wire("shared");
    AtomicInteger prepared=new AtomicInteger(),accepted=new AtomicInteger(),closed=new AtomicInteger();
    PhoneCallSession session;
    @Before public void setup()throws Exception{
        kiosk=new MockWebServer();kiosk.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest r){return new MockResponse().setBody("{\"ok\":true}");}});kiosk.start();
        Config config=mock(Config.class);when(config.key()).thenReturn("shared");when(config.id()).thenReturn("phone");
        audio=mock(AudioPipe.class);audio.microphoneAvailable=true;
        engine=new Engine(null,config,()->{},audio);engine.running=true;incoming("first");
        session=new PhoneCallSession(engine,"first",prepared::incrementAndGet,new PhoneCallSession.Display(){public void accepted(){accepted.incrementAndGet();}public void closed(int cause){closed.incrementAndGet();}},false);
    }
    void incoming(String id){
        assertEquals("ringing",engine.incoming(Wire.obj("call",id,"from",Wire.obj("id","kiosk","name","Kitchen","port",kiosk.getPort(),"tls",false)),remote.token(id,"kiosk"),"127.0.0.1").optString("status"));
        engine.phoneManaged=true;
    }
    @After public void cleanup()throws Exception{engine.stop();engine.io.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);kiosk.shutdown();}
    @Test public void answerWaitsForFocusAndRepeatedCallbacksDoNotStartTwice()throws Exception{
        session.answer();assertEquals(1,accepted.get());assertEquals(0,prepared.get());assertEquals("ringing",engine.state);verify(audio,never()).start(true);
        session.focusGained();assertEquals(1,prepared.get());assertEquals("connecting",engine.state);verify(audio).start(true);
        session.answer();session.focusGained();assertEquals(1,prepared.get());assertEquals(1,accepted.get());
    }
    @Test public void microphoneStartsAutomaticallyAndObeysDialerMute()throws Exception{
        session.focusGained();session.answer();engine.attach("first",mock(IntercomServer.Link.class));
        assertTrue(engine.handsFree);assertTrue(engine.sending);
        session.mute(true);assertFalse(engine.sending);session.mute(false);assertTrue(engine.sending);
    }
    @Test public void muteBeforeAudioConnectIsPreserved()throws Exception{
        session.mute(true);session.answer();session.focusGained();engine.attach("first",mock(IntercomServer.Link.class));
        assertTrue(engine.handsFree);assertFalse(engine.sending);
    }
    @Test public void rejectedOrStaleCallNeverStartsOrEndsNewCall()throws Exception{
        session.end("Anruf abgelehnt",DisconnectCause.REJECTED);assertEquals("idle",engine.state);assertEquals(1,closed.get());
        incoming("second");session.answer();session.focusGained();session.mute(true);session.end("stale",DisconnectCause.LOCAL);
        assertEquals("second",engine.callId);assertEquals("ringing",engine.state);assertFalse(engine.phoneMuted);verify(audio,never()).start(true);
    }
    @Test public void focusLossEndsAnsweredCallAndReleasesAudio()throws Exception{
        session.focusGained();session.answer();session.focusLost();assertEquals("idle",engine.state);verify(audio).stop();assertEquals(1,closed.get());
    }
    @Test public void backgroundMicrophoneDenialEndsCleanlyWithoutStartingAudio()throws Exception{
        session=new PhoneCallSession(engine,"first",()->{throw new SecurityException("sensitive");},new PhoneCallSession.Display(){public void accepted(){}public void closed(int cause){}},true);
        session.answer();assertEquals("idle",engine.state);verify(audio,never()).start(true);assertTrue(engine.diagnostics.report().contains("SecurityException"));assertFalse(engine.diagnostics.report().contains("sensitive"));
    }
}
