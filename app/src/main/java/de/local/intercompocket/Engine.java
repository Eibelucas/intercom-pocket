package de.local.intercompocket;

import android.content.Context;

import okhttp3.*;

import okio.ByteString;

import org.json.*;

import java.util.*;
import java.util.concurrent.*;

final class Engine implements IntercomServer.Listener {
    interface Observer {
        void changed();
    }

    final Config config;
    final Tls tls;
    final Wire wire;
    final ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();
    final ExecutorService io = Executors.newFixedThreadPool(4);
    final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    final AudioPipe audio;
    final CallDiagnostics diagnostics = new CallDiagnostics();
    final Observer observer;
    volatile String state = "idle", detail = "", callId = "";
    volatile Peer peer;
    volatile boolean outgoing, broadcast, sending, handsFree, speaker = false, running;
    volatile boolean phoneManaged, phoneMuted, endedLocally;
    volatile long since;
    volatile IntercomServer.Link link;
    final ConcurrentHashMap<String, IntercomServer.Link> announcementLinks =
            new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Peer> announcementPeers = new ConcurrentHashMap<>();
    private final Map<String, String> announcementNames = new LinkedHashMap<>(),
            announcementStates = new LinkedHashMap<>();
    volatile String announcementTitle;
    final CallHistory history;
    volatile long started;
    private IntercomServer plain, encrypted;
    private ScheduledFuture<?> timeout;
    private Discovery discovery;
    private final Context context;
    final Messages msg;
    final String appVersion;

    Engine(Context c, Config config, Observer observer) {
        this(c, new Messages(c), config, observer, null);
    }

    Engine(
            Context c,
            Messages messages,
            Config config,
            Observer observer,
            AudioPipe suppliedAudio) {
        this.context = c;
        this.msg = messages;
        this.appVersion = AppInfo.versionName(c);
        this.announcementTitle = msg.get(R.string.announce_title_all);
        diagnostics.setVersion(appVersion);
        this.config = config;
        this.observer = observer;
        wire = new Wire(config.key());
        tls = new Tls(config);
        history = new CallHistory(config.prefs);
        audio =
                suppliedAudio != null
                        ? suppliedAudio
                        : new AudioPipe(
                                c,
                                new AudioPipe.Sink() {
                                    public void frame(byte[] b) {
                                        if (!sending) return;
                                        if (state.equals("broadcasting")) {
                                            for (IntercomServer.Link l : announcementLinks.values())
                                                l.bytes(b);
                                        } else {
                                            IntercomServer.Link l = link;
                                            if (l != null) l.bytes(b);
                                        }
                                    }

                                    public void failed(String m) {
                                        diagnostics.event(m);
                                        io.execute(() -> finish(m, true));
                                    }
                                });
        audio.diagnostics(diagnostics);
    }

    void start() throws Exception {
        try {
            plain = new IntercomServer(2324, this, false, config.tls());
            plain.start(45000, true);
            if (config.tls()) {
                encrypted = new IntercomServer(2325, this, true, true);
                encrypted.makeSecure(tls.server(), null);
                encrypted.start(45000, true);
            }
            running = true;
            JSONArray saved = new JSONArray(config.prefs.getString("peers", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject j = saved.getJSONObject(i);
                Peer p = new Peer(j.getString("host"), j.getInt("port"), j.optBoolean("tls"));
                p.manual = true;
                peers.put(p.addressKey(), p);
            }
            discovery = new Discovery(context, this);
            discovery.start();
            timer.scheduleWithFixedDelay(this::refresh, 1, 20, TimeUnit.SECONDS);
            timer.scheduleWithFixedDelay(
                    () -> {
                        IntercomServer.Link l = link;
                        if (l != null) l.ping();
                        for (IntercomServer.Link other : announcementLinks.values()) other.ping();
                    },
                    15,
                    15,
                    TimeUnit.SECONDS);
            changed();
        } catch (Exception e) {
            stop();
            throw e;
        }
    }

    void changed() {
        observer.changed();
    }

    boolean active() {
        return !state.equals("idle");
    }

    int port() {
        return config.tls() ? 2325 : 2324;
    }

    public JSONObject identity() {
        return Wire.obj(
                "id",
                config.id(),
                "name",
                config.name(),
                "version",
                appVersion,
                "enabled",
                running && !config.key().isEmpty(),
                "key",
                Wire.fingerprint(config.key()),
                "dnd",
                config.advertisedDnd(),
                "endpoint",
                Wire.obj("port", port(), "tls", config.tls()));
    }

    private JSONObject self() {
        return Wire.obj(
                "id",
                config.id(),
                "name",
                config.name(),
                "address",
                Discovery.localIp(),
                "port",
                port(),
                "version",
                appVersion,
                "tls",
                config.tls());
    }

    void found(Peer p) {
        if (config.id().equals(p.id)) return;
        peers.compute(p.addressKey(), (k, old) -> old == null ? p : old);
        io.execute(() -> probe(peers.get(p.addressKey())));
    }

    void add(String address) throws Exception {
        HttpUrl url = HttpUrl.parse(address.contains("://") ? address : "http://" + address);
        if (url == null
                || !url.username().isEmpty()
                || !url.password().isEmpty()
                || !url.encodedPath().equals("/")
                || url.query() != null)
            throw new Exception(msg.get(R.string.error_address_invalid));
        int port = url.port();
        if (port == 80 || port == 443) port = 2324;
        Peer p = new Peer(url.host(), port, url.isHttps());
        p.manual = true;
        peers.put(p.addressKey(), p);
        savePeers();
        io.execute(() -> probe(p));
    }

    void remove(Peer p) {
        peers.remove(p.addressKey());
        savePeers();
        changed();
    }

    void savePeers() {
        JSONArray a = new JSONArray();
        for (Peer p : peers.values())
            if (p.manual) a.put(Wire.obj("host", p.host, "port", p.adminPort, "tls", p.adminTls));
        config.prefs.edit().putString("peers", a.toString()).apply();
    }

    void refresh() {
        if (!running) return;
        for (Peer p : peers.values()) io.execute(() -> probe(p));
    }

    void probe(Peer p) {
        if (p == null || !running) return;
        synchronized (p) {
            if (System.currentTimeMillis() - p.checked < 1500) return;
            p.checked = System.currentTimeMillis();
            p.ready = false;
            try {
                if (p.adminTls && !checkPin(p, true)) {
                    changed();
                    return;
                }
                JSONObject j = get(p, true, "/api/intercom/identity");
                p.id = j.getString("id");
                p.name = j.optString("name", "");
                p.version = j.optString("version");
                if (config.id().equals(p.id)) {
                    peers.remove(p.addressKey());
                    return;
                }
                JSONObject endpoint = j.optJSONObject("endpoint");
                p.port = endpoint == null ? p.adminPort : endpoint.getInt("port");
                p.tls = endpoint == null ? p.adminTls : endpoint.getBoolean("tls");
                if (p.port < 1 || p.port > 65535) throw new Exception("Invalid port");
                if (!j.optBoolean("enabled")) p.status = Peer.Status.OFF;
                else if (!Wire.fingerprint(config.key()).equals(j.optString("key")))
                    p.status = Peer.Status.KEY_MISMATCH;
                else if (p.tls != config.tls()) p.status = Peer.Status.TLS_MISMATCH;
                else if (j.optBoolean("dnd")) p.status = Peer.Status.DND;
                else if (p.tls && !checkPin(p, false)) {
                } else {
                    p.status = Peer.Status.READY;
                    p.ready = true;
                    config.remember(p);
                }
            } catch (Exception e) {
                p.status = Peer.Status.UNREACHABLE;
            }
        }
        changed();
    }

    private boolean checkPin(Peer p, boolean admin) throws Exception {
        String current = tls.inspect(p, admin);
        if (!current.equals(tls.saved(p, admin))) {
            p.candidatePin = current;
            p.candidatePort = admin ? p.adminPort : p.port;
            p.status = Peer.Status.CONFIRM_CERT;
            return false;
        }
        p.candidatePin = "";
        return true;
    }

    void trust(Peer p) {
        config.prefs
                .edit()
                .putString("pin:" + p.host + ":" + p.candidatePort, p.candidatePin)
                .apply();
        p.candidatePin = "";
        p.checked = 0;
        io.execute(() -> probe(p));
    }

    private JSONObject get(Peer p, boolean admin, String path) throws Exception {
        OkHttpClient client = tls.client(p, admin);
        try (Response r =
                client.newCall(new Request.Builder().url(p.base(admin) + path).build()).execute()) {
            if (r.code() != 200) throw new Exception("HTTP " + r.code());
            return new JSONObject(r.body().string());
        } finally {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }

    private JSONObject post(Peer p, String path, JSONObject body, String id) throws Exception {
        if (p.tls != config.tls()) throw new Exception("TLS setting mismatch");
        OkHttpClient client = tls.client(p, false);
        try (Response r =
                client.newCall(
                                new Request.Builder()
                                        .url(p.base(false) + path)
                                        .header(
                                                "Authorization",
                                                "Bearer " + wire.token(id, config.id()))
                                        .post(
                                                RequestBody.create(
                                                        body.toString(),
                                                        MediaType.get(
                                                                "application/json; charset=utf-8")))
                                        .build())
                        .execute()) {
            diagnostics.event("Call request answered (HTTP " + r.code() + ")");
            if (r.code() == 403) return Wire.obj("status", "key");
            if (!r.isSuccessful()) throw new java.io.IOException("HTTP " + r.code());
            return new JSONObject(r.body().string());
        } finally {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }

    synchronized void call(Peer target) {
        if (!running || active() || !target.ready) return;
        diagnostics.clear();
        diagnostics.event("Outgoing call");
        callId = UUID.randomUUID().toString();
        peer = target;
        outgoing = true;
        broadcast = false;
        since = 0;
        started = System.currentTimeMillis();
        handsFree = false;
        sending = false;
        speaker = false;
        state = "calling";
        detail = "";
        String id = callId;
        arm(id, 65000, msg.get(R.string.err_no_accept));
        changed();
        io.execute(
                () -> {
                    try {
                        JSONObject r =
                                post(
                                        target,
                                        "/api/intercom/call",
                                        Wire.obj("call", id, "kind", "call", "from", self()),
                                        id);
                        String status = r.optString("status");
                        diagnostics.event("Invitation answered: " + reasonForDiagnostics(status));
                        if (!status.equals("ringing") && !status.equals("auto"))
                            endIf(id, reason(status), false);
                    } catch (Exception e) {
                        diagnostics.error("Sending call", e);
                        endIf(id, msg.get(R.string.err_peer_unreachable), false);
                    }
                });
    }

    synchronized void announce() {
        announce(null, msg.get(R.string.announce_title_all));
    }

    synchronized void announce(Set<String> selected, String title) {
        if (!running || active()) return;
        if (selected != null && (selected.isEmpty() || selected.size() > RoomGroups.MAX_MEMBERS)) {
            detail = msg.get(R.string.toast_select_1_32);
            changed();
            return;
        }
        Map<String, Peer> available = new TreeMap<>();
        for (Peer p : peers.values())
            if (!p.id.isEmpty() && (selected == null || selected.contains(p.id))) {
                Peer previous = available.get(p.id);
                if (previous == null || !previous.ready && p.ready) available.put(p.id, p);
            }
        announcementNames.clear();
        announcementStates.clear();
        Collection<String> ids = selected == null ? available.keySet() : new TreeSet<>(selected);
        ArrayList<Peer> targets = new ArrayList<>();
        for (String id : ids) {
            Peer p = available.get(id);
            if (selected == null
                    && (p == null || !p.ready || targets.size() >= RoomGroups.MAX_MEMBERS))
                continue;
            String name = p == null ? config.peerName(id) : config.display(p);
            if (name == null || name.isEmpty()) name = p == null ? config.unknownKiosk() : p.name;
            announcementNames.put(id, name);
            if (p != null && p.ready) {
                targets.add(p);
                announcementStates.put(id, msg.get(R.string.ann_connecting));
            } else announcementStates.put(
                        id, p == null ? msg.get(R.string.ann_not_found) : msg.get(p.status.label));
        }
        if (targets.isEmpty()) {
            detail = msg.get(R.string.err_no_selected_reachable);
            changed();
            return;
        }
        diagnostics.clear();
        diagnostics.event("Announcement to " + targets.size() + " kiosks");
        callId = UUID.randomUUID().toString();
        peer = null;
        outgoing = true;
        broadcast = true;
        since = 0;
        started = System.currentTimeMillis();
        handsFree = false;
        sending = false;
        state = "broadcasting";
        announcementTitle = title;
        updateAnnouncementDetail();
        String id = callId;
        arm(id, 12000, msg.get(R.string.err_no_kiosk_connected));
        changed();
        for (Peer target : targets)
            io.execute(
                    () -> {
                        try {
                            JSONObject reply =
                                    post(
                                            target,
                                            "/api/intercom/call",
                                            Wire.obj(
                                                    "call",
                                                    id,
                                                    "kind",
                                                    "broadcast",
                                                    "from",
                                                    self()),
                                            id);
                            if (reply.optString("status").equals("listening")) {
                                synchronized (Engine.this) {
                                    if (id.equals(callId) && state.equals("broadcasting")) {
                                        announcementPeers.put(target.id, target);
                                        connectAnnouncement(target, id);
                                    } else notifyAnnouncementEnd(target, id);
                                }
                            } else
                                announcementFailed(id, target, reason(reply.optString("status")));
                        } catch (Exception e) {
                            announcementFailed(id, target, msg.get(R.string.peer_unreachable));
                        }
                    });
    }

    private void updateAnnouncementDetail() {
        StringBuilder text =
                new StringBuilder(
                        msg.get(
                                R.string.ann_progress,
                                announcementLinks.size(),
                                announcementNames.size()));
        for (String id : announcementNames.keySet())
            text.append("\n")
                    .append(announcementNames.get(id))
                    .append(" · ")
                    .append(announcementStates.get(id));
        detail = text.toString();
    }

    private synchronized void announcementFailed(String id, Peer target, String status) {
        if (!id.equals(callId) || !state.equals("broadcasting")) return;
        announcementPeers.remove(target.id);
        announcementStates.put(target.id, status);
        updateAnnouncementDetail();
        changed();
        boolean pending = announcementStates.containsValue(msg.get(R.string.ann_connecting));
        if (announcementLinks.isEmpty() && !pending) {
            String recipients = detail;
            finish(msg.get(R.string.err_no_more_kiosks), false);
            detail = msg.get(R.string.ann_ended, recipients);
            changed();
        }
    }

    private void notifyAnnouncementEnd(Peer target, String id) {
        io.execute(
                () -> {
                    try {
                        post(target, "/api/intercom/call/" + id, Wire.obj("action", "hangup"), id);
                    } catch (Exception ignored) {
                    }
                });
    }

    private void connectAnnouncement(Peer target, String id) {
        try {
            OkHttpClient client = tls.client(target, false);
            Request request =
                    new Request.Builder()
                            .url(
                                    target.base(false)
                                            + "/api/intercom/audio/"
                                            + id
                                            + "?token="
                                            + java.net.URLEncoder.encode(
                                                    wire.token(id, config.id()), "UTF-8"))
                            .build();
            client.newWebSocket(
                    request,
                    new WebSocketListener() {
                        IntercomServer.Link wrapped;

                        void clean() {
                            client.dispatcher().executorService().shutdown();
                            client.connectionPool().evictAll();
                        }

                        public void onOpen(WebSocket s, Response r) {
                            wrapped =
                                    new IntercomServer.Link() {
                                        public void text(String t) {
                                            s.send(t);
                                        }

                                        public void bytes(byte[] b) {
                                            if (s.queueSize() > Wire.FRAME_BYTES * 6L) {
                                                s.cancel();
                                                return;
                                            }
                                            s.send(ByteString.of(b));
                                        }

                                        public void close() {
                                            s.close(1000, "ended");
                                        }

                                        public void ping() {}
                                    };
                            synchronized (Engine.this) {
                                if (!id.equals(callId)
                                        || !state.equals("broadcasting")
                                        || !announcementPeers.containsKey(target.id)) {
                                    wrapped.close();
                                    clean();
                                    return;
                                }
                                announcementLinks.put(target.id, wrapped);
                                if (since == 0) {
                                    try {
                                        audio.start(true);
                                        if (timeout != null) timeout.cancel(false);
                                        since = System.currentTimeMillis();
                                    } catch (Exception e) {
                                        audioFailed(e);
                                        return;
                                    }
                                }
                                announcementStates.put(target.id, msg.get(R.string.status_connected));
                                updateAnnouncementDetail();
                                changed();
                            }
                            wrapped.text(Wire.obj("type", "talk", "on", sending).toString());
                        }

                        public void onMessage(WebSocket s, String message) {
                            try {
                                if (new JSONObject(message).optString("type").equals("end")) {
                                    drop();
                                    s.close(1000, "ended");
                                }
                            } catch (Exception ignored) {
                            }
                        }

                        public void onClosing(WebSocket s, int code, String reason) {
                            drop();
                            s.close(1000, "ended");
                        }

                        public void onClosed(WebSocket s, int code, String reason) {
                            drop();
                            clean();
                        }

                        public void onFailure(WebSocket s, Throwable t, Response r) {
                            drop();
                            clean();
                        }

                        void drop() {
                            synchronized (Engine.this) {
                                if (!id.equals(callId) || !state.equals("broadcasting")) return;
                                if (wrapped != null
                                        && !announcementLinks.remove(target.id, wrapped)) return;
                                announcementFailed(id, target, msg.get(R.string.err_connection_ended));
                            }
                        }
                    });
        } catch (Exception e) {
            announcementFailed(id, target, msg.get(R.string.err_audio_not_connected));
        }
    }

    public synchronized JSONObject incoming(JSONObject body, String token, String host) {
        String id = body.optString("call");
        JSONObject from = body.optJSONObject("from");
        if (!Wire.validCallId(id) || from == null)
            return Wire.obj("code", 400, "status", "invalid");
        JSONObject claims = wire.verify(token, id);
        if (claims == null || !claims.optString("from").equals(from.optString("id")))
            return Wire.obj("code", 403, "status", "key");
        if (!running) return Wire.obj("status", "off");
        if (from.optBoolean("tls") != config.tls()) return Wire.obj("code", 409, "status", "tls");
        if (config.blocks(from.optString("id"))) return Wire.obj("status", "dnd");
        if (active()) return Wire.obj("status", "busy");
        Peer incoming = Peer.from(from, host);
        if (incoming.port < 1 || incoming.port > 65535)
            return Wire.obj("code", 400, "status", "invalid");
        String kind = body.optString("kind", "call");
        if (!kind.equals("call") && !kind.equals("broadcast"))
            return Wire.obj("code", 400, "status", "invalid");
        if (kind.equals("broadcast") && !config.acceptAnnouncements())
            return Wire.obj("status", "dnd");
        diagnostics.clear();
        diagnostics.event("Incoming call");
        peer = incoming;
        callId = id;
        outgoing = false;
        broadcast = kind.equals("broadcast");
        handsFree = false;
        sending = false;
        speaker = broadcast && !audio.headsetConnected();
        since = 0;
        started = System.currentTimeMillis();
        detail = "";
        config.remember(incoming);
        if (broadcast) {
            state = "listening";
            arm(id, 12000, msg.get(R.string.err_announcement_not_connected));
            try {
                audio.start(false);
            } catch (Exception e) {
                finish(msg.get(R.string.err_playback_unavailable), false);
                return Wire.obj("status", "off");
            }
        } else {
            state = "ringing";
            arm(id, 45000, msg.get(R.string.msg_call_missed));
            if (incoming.tls)
                io.execute(
                        () -> {
                            try {
                                checkPin(incoming, false);
                            } catch (Exception ignored) {
                            }
                            changed();
                        });
        }
        changed();
        return Wire.obj("status", broadcast ? "listening" : "ringing");
    }

    synchronized void answer() {
        if (!state.equals("ringing")) return;
        if (peer.tls && tls.saved(peer, false).isEmpty()) {
            detail = msg.get(R.string.err_confirm_cert_first);
            changed();
            return;
        }
        String id = callId;
        Peer p = peer;
        try {
            audio.start(true);
        } catch (Exception e) {
            audioFailed(e);
            return;
        }
        state = "connecting";
        arm(id, 12000, msg.get(R.string.err_audio_connection_failed));
        changed();
        diagnostics.event("Sending accept to caller");
        io.execute(
                () -> {
                    try {
                        JSONObject r =
                                post(
                                        p,
                                        "/api/intercom/call/" + id,
                                        Wire.obj("action", "answer"),
                                        id);
                        if (!r.optBoolean("ok", false))
                            endIf(id, msg.get(R.string.err_accept_failed), true);
                    } catch (Exception e) {
                        diagnostics.error("Accept acknowledgement", e);
                        endIf(id, msg.get(R.string.err_caller_unreachable), true);
                    }
                });
    }

    public synchronized JSONObject signal(String id, JSONObject body, String token, String host) {
        JSONObject claims = wire.verify(token, id);
        if (id.equals(callId) && state.equals("broadcasting") && claims != null) {
            Peer member = announcementPeers.get(claims.optString("from"));
            if (member != null) {
                String action = body.optString("action");
                if (action.equals("hangup")
                        || action.equals("decline")
                        || action.equals("cancel")) {
                    IntercomServer.Link l = announcementLinks.remove(member.id);
                    if (l != null) l.close();
                    announcementFailed(id, member, msg.get(R.string.ann_closed));
                    return Wire.obj("ok", true);
                }
            }
        }
        if (claims == null
                || !id.equals(callId)
                || peer == null
                || !peer.id.equals(claims.optString("from"))) {
            diagnostics.event("Call signal rejected: invalid credentials or call mismatch");
            return Wire.obj("code", 403, "ok", false);
        }
        String action = body.optString("action");
        if (action.equals("answer")) {
            diagnostics.event("Accept received from kiosk");
            if (!outgoing || !state.equals("calling")) return Wire.obj("code", 409, "ok", false);
            state = "connecting";
            arm(id, 12000, msg.get(R.string.err_audio_connection_failed));
            changed();
            Peer p = peer;
            io.execute(() -> connect(p, id));
            return Wire.obj("ok", true);
        }
        if (action.equals("decline")
                || action.equals("missed")
                || action.equals("cancel")
                || action.equals("hangup")) {
            diagnostics.event("Remote party ended: " + action);
            finish(reason(action), false);
            return Wire.obj("ok", true);
        }
        return Wire.obj("code", 400, "ok", false);
    }

    public synchronized boolean allowAudio(String id, String token, String host) {
        JSONObject claims = wire.verify(token, id);
        return claims != null
                && id.equals(callId)
                && !outgoing
                && peer != null
                && peer.id.equals(claims.optString("from"))
                && (state.equals("connecting") || state.equals("listening"))
                && link == null;
    }

    private void connect(Peer p, String id) {
        try {
            diagnostics.event("Opening audio WebSocket");
            OkHttpClient client = tls.client(p, false);
            Request request =
                    new Request.Builder()
                            .url(
                                    p.base(false)
                                            + "/api/intercom/audio/"
                                            + id
                                            + "?token="
                                            + java.net.URLEncoder.encode(
                                                    wire.token(id, config.id()), "UTF-8"))
                            .build();
            client.newWebSocket(
                    request,
                    new WebSocketListener() {
                        IntercomServer.Link wrapped;

                        public void onOpen(WebSocket s, Response r) {
                            diagnostics.event("Audio WebSocket connected (HTTP " + r.code() + ")");
                            wrapped =
                                    new IntercomServer.Link() {
                                        public void text(String t) {
                                            s.send(t);
                                        }

                                        public void bytes(byte[] b) {
                                            if (s.queueSize() > Wire.FRAME_BYTES * 6L) {
                                                s.cancel();
                                                return;
                                            }
                                            s.send(ByteString.of(b));
                                        }

                                        public void close() {
                                            s.close(1000, "ended");
                                            client.dispatcher().executorService().shutdown();
                                            client.connectionPool().evictAll();
                                        }

                                        public void ping() {}
                                    };
                            attach(id, wrapped);
                        }

                        public void onMessage(WebSocket s, String text) {
                            message(id, wrapped, text, null);
                        }

                        public void onMessage(WebSocket s, ByteString b) {
                            message(id, wrapped, null, b.toByteArray());
                        }

                        public void onClosing(WebSocket s, int code, String reason) {
                            diagnostics.event(
                                    "Remote closes audio WebSocket (code " + code + ")");
                            s.close(1000, "ended");
                            disconnected(id, wrapped);
                        }

                        public void onClosed(WebSocket s, int code, String reason) {
                            disconnected(id, wrapped);
                            client.dispatcher().executorService().shutdown();
                            client.connectionPool().evictAll();
                        }

                        public void onFailure(WebSocket s, Throwable t, Response r) {
                            diagnostics.error(
                                    "Audio WebSocket" + (r == null ? "" : " HTTP " + r.code()), t);
                            endIf(
                                    id,
                                    msg.get(R.string.err_audio_interrupted)
                                            + (r == null ? "" : " (HTTP " + r.code() + ")"),
                                    false);
                            client.dispatcher().executorService().shutdown();
                            client.connectionPool().evictAll();
                        }
                    });
        } catch (Exception e) {
            diagnostics.error("Opening audio WebSocket", e);
            endIf(id, msg.get(R.string.err_audio_connection_failed), true);
        }
    }

    public synchronized void attach(String id, IntercomServer.Link l) {
        if (!id.equals(callId)
                || !(state.equals("connecting") || state.equals("listening"))
                || link != null) {
            l.close();
            return;
        }
        try {
            audio.start(!broadcast);
        } catch (Exception e) {
            l.close();
            audioFailed(e);
            return;
        }
        link = l;
        if (timeout != null) timeout.cancel(false);
        since = System.currentTimeMillis();
        state = broadcast ? "listening" : "in_call";
        if (!broadcast && !audio.microphoneAvailable)
            detail = msg.get(R.string.listen_only, audio.microphoneProblem);
        diagnostics.event("Call connected");
        if (phoneManaged && !broadcast) {
            handsFree = true;
            talk(!phoneMuted);
        } else l.text(Wire.obj("type", "talk", "on", false).toString());
        changed();
    }

    public void message(String id, IntercomServer.Link l, String text, byte[] bytes) {
        if (!id.equals(callId) || l != link) return;
        if (bytes != null) {
            audio.receive(bytes);
            return;
        }
        try {
            JSONObject j = new JSONObject(text);
            if (j.optString("type").equals("end")) endIf(id, msg.get(R.string.msg_call_ended), false);
            else if (j.optString("type").equals("talk")) {
                detail =
                        msg.get(
                                j.optBoolean("on")
                                        ? R.string.other_speaking
                                        : R.string.status_connected);
                changed();
            }
        } catch (Exception ignored) {
        }
    }

    public void disconnected(String id, IntercomServer.Link l) {
        if (l != null && l == link) endIf(id, msg.get(R.string.err_connection_ended), false);
    }

    synchronized void talk(boolean on) {
        if (!state.equals("in_call") && !state.equals("broadcasting")) return;
        sending = on && audio.microphoneAvailable;
        audio.sending(sending);
        String message = Wire.obj("type", "talk", "on", sending).toString();
        if (state.equals("broadcasting")) {
            for (IntercomServer.Link l : announcementLinks.values()) l.text(message);
        } else if (link != null) link.text(message);
        changed();
    }

    synchronized void handsFree(boolean on) {
        handsFree = on;
        talk(on);
    }

    synchronized void phoneMute(boolean muted) {
        phoneMuted = muted;
        if (phoneManaged
                && state.equals("in_call")
                && sending != (!muted && audio.microphoneAvailable)) talk(!muted);
    }

    public void httpEvent(String event) {
        if (active()) diagnostics.event(event);
    }

    synchronized void endIf(String id, String reason, boolean notify) {
        if (id.equals(callId)) finish(reason, notify);
    }

    synchronized void finish(String reason, boolean notify) {
        if (!active()) return;
        diagnostics.event(
                (notify ? "Ended locally: " : "Ended by remote or connection: ") + reason);
        if (state.equals("calling"))
            diagnostics.event("No accept confirmation received before the call ended");
        endedLocally = notify;
        String id = callId;
        Peer old = peer;
        String oldState = state;
        IntercomServer.Link oldLink = link;
        if (oldState.equals("broadcasting")) {
            Peer all = new Peer("", 2324, false);
            all.id = "";
            all.name = announcementTitle;
            history.add(all, true, true, started, since, reason);
        } else history.add(old, outgoing, broadcast, started, since, reason);
        if (oldState.equals("broadcasting")) {
            for (Peer p : announcementPeers.values()) if (notify) notifyAnnouncementEnd(p, id);
            for (IntercomServer.Link l : announcementLinks.values()) {
                if (notify) l.text(Wire.obj("type", "end").toString());
                l.close();
            }
            announcementLinks.clear();
            announcementPeers.clear();
        }
        state = "idle";
        callId = "";
        link = null;
        peer = null;
        sending = false;
        handsFree = false;
        since = 0;
        detail = reason;
        if (timeout != null) timeout.cancel(false);
        audio.stop();
        phoneManaged = false;
        phoneMuted = false;
        if (oldLink != null) {
            if (notify) oldLink.text(Wire.obj("type", "end").toString());
            oldLink.close();
        }
        if (notify && old != null)
            io.execute(
                    () -> {
                        try {
                            post(
                                    old,
                                    "/api/intercom/call/" + id,
                                    Wire.obj(
                                            "action",
                                            oldState.equals("ringing")
                                                    ? (reason.equals(msg.get(R.string.msg_call_missed))
                                                            ? "missed"
                                                            : "decline")
                                                    : oldState.equals("calling")
                                                            ? "cancel"
                                                            : "hangup"),
                                    id);
                        } catch (Exception ignored) {
                        }
                    });
        changed();
    }

    void arm(String id, long ms, String reason) {
        if (timeout != null) timeout.cancel(false);
        timeout = timer.schedule(() -> endIf(id, reason, true), ms, TimeUnit.MILLISECONDS);
    }

    private void audioFailed(Exception e) {
        diagnostics.error("Audio start / " + audio.startupStep, e);
        finish(
                msg.get(
                        R.string.err_audio_unavailable,
                        audio.startupStep,
                        e.getClass().getSimpleName()),
                true);
    }

    private String reasonForDiagnostics(String status) {
        return switch (status) {
            case "ringing", "auto", "listening", "busy", "dnd", "off", "key", "tls" -> status;
            default -> "unexpected response";
        };
    }

    void stop() {
        running = false;
        finish(msg.get(R.string.msg_intercom_off), true);
        if (discovery != null) discovery.stop();
        if (plain != null) plain.stop();
        if (encrypted != null) encrypted.stop();
        timer.shutdownNow();
        io.shutdown();
        changed();
    }

    /** Human-readable reason for a wire status or signal action. */
    String reason(String s) {
        return msg.get(
                switch (s) {
                    case "key" -> R.string.peer_key_mismatch;
                    case "tls" -> R.string.peer_tls_mismatch;
                    case "busy" -> R.string.reason_busy;
                    case "dnd" -> R.string.reason_dnd;
                    case "off" -> R.string.reason_off;
                    case "decline" -> R.string.msg_call_declined;
                    case "missed" -> R.string.reason_missed;
                    case "cancel" -> R.string.reason_cancelled;
                    case "hangup" -> R.string.msg_call_ended;
                    default -> R.string.reason_unavailable;
                });
    }
}
