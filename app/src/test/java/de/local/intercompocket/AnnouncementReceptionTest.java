package de.local.intercompocket;

import org.junit.*;
import org.json.JSONObject;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AnnouncementReceptionTest {
    Config config;AudioPipe audio;Engine engine;Wire caller=new Wire("shared");
    @Before public void setup(){
        config=mock(Config.class);when(config.key()).thenReturn("shared");when(config.id()).thenReturn("phone");
        audio=mock(AudioPipe.class);engine=new Engine(null,config,()->{},audio);engine.running=true;
    }
    @After public void cleanup(){engine.stop();}
    JSONObject invite(String id,String kind){return engine.incoming(Wire.obj("call",id,"kind",kind,"from",Wire.obj("id","kitchen","name","Kitchen","port",2324)),caller.token(id,"kitchen"),"127.0.0.1");}
    @Test public void disabledAnnouncementsRefuseAudioButOrdinaryCallsStillRing()throws Exception{
        when(config.acceptAnnouncements()).thenReturn(false);
        assertEquals("dnd",invite("broadcast-1","broadcast").optString("status"));assertEquals("idle",engine.state);verify(audio,never()).start(anyBoolean());
        assertEquals("ringing",invite("call-1","call").optString("status"));assertFalse(engine.broadcast);
    }
    @Test public void enabledAnnouncementIsReceiveOnly()throws Exception{
        when(config.acceptAnnouncements()).thenReturn(true);
        assertEquals("listening",invite("broadcast-1","broadcast").optString("status"));verify(audio).start(false);assertFalse(engine.sending);
    }
    @Test public void enabledAnnouncementsStillRespectDndAndQuietHourPolicy()throws Exception{
        when(config.acceptAnnouncements()).thenReturn(true);when(config.blocks("kitchen")).thenReturn(true);
        assertEquals("dnd",invite("broadcast-1","broadcast").optString("status"));verify(audio,never()).start(anyBoolean());assertEquals("idle",engine.state);
    }
}
