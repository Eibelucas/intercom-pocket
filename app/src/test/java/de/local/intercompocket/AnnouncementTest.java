package de.local.intercompocket;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.json.JSONObject;
import org.junit.*;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AnnouncementTest {
    Engine engine;
    AudioPipe audio;
    IntercomServer accepting, declining;
    CountDownLatch connected = new CountDownLatch(1);
    Wire wire = new Wire("shared");
    AtomicInteger goodInvites = new AtomicInteger(), badInvites = new AtomicInteger();

    IntercomServer.Listener kiosk(String status) {
        return kiosk(status, true);
    }

    IntercomServer.Listener kiosk(String status, boolean acceptsAudio) {
        return new IntercomServer.Listener() {
            public JSONObject identity() {
                return Wire.obj("id", status);
            }

            public JSONObject incoming(JSONObject body, String token, String host) {
                if (wire.verify(token, body.optString("call")) == null)
                    return Wire.obj("code", 403);
                (status.equals("listening") ? goodInvites : badInvites).incrementAndGet();
                return Wire.obj("status", status);
            }

            public JSONObject signal(String id, JSONObject body, String token, String host) {
                return Wire.obj("ok", wire.verify(token, id) != null);
            }

            public boolean allowAudio(String id, String token, String host) {
                return acceptsAudio && wire.verify(token, id) != null;
            }

            public void attach(String id, IntercomServer.Link link) {
                connected.countDown();
            }

            public void message(String id, IntercomServer.Link link, String text, byte[] bytes) {}

            public void disconnected(String id, IntercomServer.Link link) {}
        };
    }

    @Before
    public void setup() throws Exception {
        Config config = mock(Config.class);
        when(config.key()).thenReturn("shared");
        when(config.id()).thenReturn("phone");
        when(config.name()).thenReturn("Phone");
        audio = mock(AudioPipe.class);
        audio.microphoneAvailable = true;
        engine = new Engine(null, new TestMessages(), config, () -> {}, audio);
        engine.running = true;
        accepting = new IntercomServer(0, kiosk("listening"), false, false);
        accepting.start(15000, true);
        declining = new IntercomServer(0, kiosk("busy"), false, false);
        declining.start(15000, true);
        Peer good = new Peer("127.0.0.1", accepting.getListeningPort(), false);
        good.id = "good";
        good.name = "Kitchen";
        good.ready = true;
        Peer bad = new Peer("127.0.0.1", declining.getListeningPort(), false);
        bad.id = "bad";
        bad.name = "Hall";
        bad.ready = true;
        engine.peers.put(good.addressKey(), good);
        engine.peers.put(bad.addressKey(), bad);
    }

    @After
    public void cleanup() {
        engine.stop();
        accepting.stop();
        declining.stop();
    }

    @Test
    public void rejectionOfOneKioskKeepsOtherAnnouncementOpen() throws Exception {
        engine.announce();
        assertTrue(connected.await(10, TimeUnit.SECONDS));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (engine.announcementLinks.isEmpty() && System.nanoTime() < until) Thread.sleep(10);
        assertEquals("broadcasting", engine.state);
        assertEquals(1, engine.announcementLinks.size());
        verify(audio).start(true);
        awaitDetail("Hall · Kiosk is busy");
        assertEquals("broadcasting", engine.state);
        engine.talk(true);
        assertTrue(engine.sending);
        verify(audio).sending(true);
        engine.finish("Announcement ended", true);
        assertEquals("idle", engine.state);
    }

    void awaitConnected() throws Exception {
        assertTrue(connected.await(10, TimeUnit.SECONDS));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (engine.announcementLinks.isEmpty() && System.nanoTime() < until) Thread.sleep(10);
        assertEquals("broadcasting", engine.state);
        assertEquals(1, engine.announcementLinks.size());
    }

    void finishAndDrain() throws Exception {
        engine.finish("Test ended", true);
        engine.io.shutdown();
        assertTrue(engine.io.awaitTermination(10, TimeUnit.SECONDS));
    }

    void awaitDetail(String expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!engine.detail.contains(expected) && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(engine.detail, engine.detail.contains(expected));
    }

    @Test
    public void selectedGroupNeverInvitesOtherAvailableKiosks() throws Exception {
        engine.announce(Set.of("good"), "Ground floor");
        awaitConnected();
        assertEquals("Ground floor", engine.announcementTitle);
        assertTrue(engine.detail.contains("Kitchen · Connected"));
        assertFalse(engine.detail.contains("Hall"));
        finishAndDrain();
        assertEquals(1, goodInvites.get());
        assertEquals(0, badInvites.get());
    }

    @Test
    public void emptyOrOfflineGroupNeverFallsBackToEveryone() throws Exception {
        engine.announce(Set.of(), "Empty");
        assertEquals("idle", engine.state);
        engine.announce(Set.of("offline"), "Offline");
        assertEquals("idle", engine.state);
        assertTrue(engine.detail.contains("reachable"));
        assertEquals(0, goodInvites.get());
        assertEquals(0, badInvites.get());
        verify(audio, never()).start(anyBoolean());
    }

    @Test
    public void sameIdentityOnTwoAddressesIsInvitedOnlyOnce() throws Exception {
        Peer duplicate = new Peer("localhost", accepting.getListeningPort(), false);
        duplicate.id = "good";
        duplicate.ready = true;
        engine.peers.put(duplicate.addressKey(), duplicate);
        engine.announce(Set.of("good"), "Kitchen");
        awaitConnected();
        finishAndDrain();
        assertEquals(1, goodInvites.get());
        assertEquals(0, badInvites.get());
    }

    @Test
    public void offlineMemberIsShownWithoutStoppingAvailableRoom() throws Exception {
        Peer offline = new Peer("192.0.2.10", 2324, false);
        offline.id = "bedroom";
        offline.name = "Bedroom";
        offline.status = Peer.Status.UNREACHABLE;
        engine.peers.put(offline.addressKey(), offline);
        engine.announce(Set.of("good", "bedroom"), "Downstairs");
        awaitConnected();
        assertTrue(engine.detail.contains("1 of 2 kiosks connected"));
        assertTrue(engine.detail.contains("Bedroom · Unreachable"));
    }

    @Test
    public void allRejectedEndsWithVisibleReasonButNoRoomNameInDiagnostics() throws Exception {
        engine.announce(Set.of("bad"), "Upstairs");
        awaitDetail("Announcement ended");
        assertEquals("idle", engine.state);
        assertTrue(engine.detail.contains("Hall · Kiosk is busy"));
        assertFalse(engine.diagnostics.report().contains("Hall"));
    }

    @Test
    public void failedAudioHandshakeDoesNotInterruptOtherRecipients() throws Exception {
        IntercomServer broken = new IntercomServer(0, kiosk("listening", false), false, false);
        broken.start(15000, true);
        try {
            Peer peer = new Peer("127.0.0.1", broken.getListeningPort(), false);
            peer.id = "broken";
            peer.name = "Office";
            peer.ready = true;
            engine.peers.put(peer.addressKey(), peer);
            engine.announce(Set.of("good", "broken"), "Downstairs");
            awaitConnected();
            awaitDetail("Office · Connection ended");
            assertEquals("broadcasting", engine.state);
            assertEquals(1, engine.announcementLinks.size());
            engine.talk(true);
            assertTrue(engine.sending);
        } finally {
            broken.stop();
        }
    }
}
