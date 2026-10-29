package de.local.intercompocket;

import fi.iki.elonen.NanoWSD;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.HashMap;
import java.util.Map;

final class IntercomServer extends NanoWSD {
    interface Listener {
        JSONObject identity();

        JSONObject incoming(JSONObject body, String token, String host);

        JSONObject signal(String id, JSONObject body, String token, String host);

        boolean allowAudio(String id, String token, String host);

        void attach(String id, Link link);

        void message(String id, Link link, String text, byte[] bytes);

        void disconnected(String id, Link link);

        default void httpEvent(String event) {}
    }

    interface Link {
        void text(String text);

        void bytes(byte[] bytes);

        void close();

        void ping();
    }

    private final Listener listener;
    private final boolean secure;
    private final boolean requireTls;
    private final Map<String, Long> identityTimes = new HashMap<>();

    IntercomServer(int port, Listener listener, boolean secure, boolean requireTls) {
        super(port);
        this.listener = listener;
        this.secure = secure;
        this.requireTls = requireTls;
    }

    static Response json(int code, JSONObject j) {
        Response.IStatus status = Response.Status.lookup(code);
        if (status == null)
            status =
                    new Response.IStatus() {
                        public int getRequestStatus() {
                            return code;
                        }

                        public String getDescription() {
                            return code + " Intercom";
                        }
                    };
        Response r = newFixedLengthResponse(status, "application/json", j.toString());
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    @Override
    public Response serve(IHTTPSession s) {
        String path = s.getUri();
        if (path.equals("/api/intercom/identity") && s.getMethod() == Method.GET) {
            synchronized (identityTimes) {
                long now = System.currentTimeMillis();
                identityTimes.entrySet().removeIf(e -> e.getValue() < now - 1000);
                if (identityTimes.containsKey(s.getRemoteIpAddress()))
                    return json(429, Wire.obj("error", "too many probes"));
                if (identityTimes.size() > 512) return json(503, Wire.obj("error", "busy"));
                identityTimes.put(s.getRemoteIpAddress(), now);
            }
            return json(200, listener.identity());
        }
        if (requireTls && !secure) return json(426, Wire.obj("status", "tls"));
        String audioPrefix = "/api/intercom/audio/";
        if (path.startsWith(audioPrefix)) {
            String id = path.substring(audioPrefix.length()), token = s.getParms().get("token");
            if (!Wire.validCallId(id)
                    || !"websocket".equalsIgnoreCase(s.getHeaders().get("upgrade"))
                    || !listener.allowAudio(id, token, s.getRemoteIpAddress()))
                return json(403, Wire.obj("error", "refused"));
            return super.serve(s);
        }
        if (s.getMethod() != Method.POST) return json(404, Wire.obj("error", "not found"));
        if (!path.equals("/api/intercom/call") && !path.startsWith("/api/intercom/call/"))
            return json(404, Wire.obj("error", "not found"));
        try {
            listener.httpEvent("Call signal received over HTTP");
            JSONObject body = new JSONObject(HttpBody.read(s.getInputStream(), s.getHeaders()));
            String auth = s.getHeaders().getOrDefault("authorization", "");
            String token = auth.startsWith("Bearer ") ? auth.substring(7) : "";
            JSONObject answer;
            if (path.equals("/api/intercom/call"))
                answer = listener.incoming(body, token, s.getRemoteIpAddress());
            else if (path.startsWith("/api/intercom/call/"))
                answer =
                        listener.signal(
                                path.substring("/api/intercom/call/".length()),
                                body,
                                token,
                                s.getRemoteIpAddress());
            else return json(404, Wire.obj("error", "not found"));
            int code = answer.optInt("code", 200);
            listener.httpEvent("Call signal answered (HTTP " + code + ")");
            return postResponse(code, answer);
        } catch (HttpBody.Invalid e) {
            listener.httpEvent("Call signal rejected: HTTP body format (HTTP " + e.code + ")");
            return postResponse(e.code, Wire.obj("error", "invalid body"));
        } catch (Exception e) {
            listener.httpEvent("Call signal rejected: unreadable request (HTTP 400)");
            return postResponse(400, Wire.obj("error", "invalid request"));
        }
    }

    private static Response postResponse(int code, JSONObject body) {
        Response r = json(code, body);
        r.closeConnection(true);
        return r;
    }

    @Override
    protected WebSocket openWebSocket(IHTTPSession s) {
        return new IncomingSocket(s, s.getUri().substring("/api/intercom/audio/".length()));
    }

    private final class IncomingSocket extends WebSocket implements Link {
        private static final int MAX_QUEUED_AUDIO_FRAMES = 6;

        final String id;
        // NanoWSD writes to the socket synchronously. Engine calls (UI thread, telecom callbacks)
        // must not do network I/O, so all writes run in order on this thread.
        private final java.util.concurrent.ExecutorService sender =
                java.util.concurrent.Executors.newSingleThreadExecutor(
                        r -> {
                            Thread t = new Thread(r, "intercom-ws-send");
                            t.setDaemon(true);
                            return t;
                        });
        private final java.util.concurrent.atomic.AtomicInteger queuedAudioFrames =
                new java.util.concurrent.atomic.AtomicInteger();

        IncomingSocket(IHTTPSession s, String id) {
            super(s);
            this.id = id;
        }

        protected void onOpen() {
            listener.attach(id, this);
        }

        protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean remote) {
            sender.shutdown();
            listener.disconnected(id, this);
        }

        protected void onMessage(WebSocketFrame frame) {
            if (frame.getOpCode() == WebSocketFrame.OpCode.Binary)
                listener.message(id, this, null, frame.getBinaryPayload());
            else if (frame.getOpCode() == WebSocketFrame.OpCode.Text)
                listener.message(id, this, frame.getTextPayload(), null);
        }

        protected void onPong(WebSocketFrame frame) {}

        protected void onException(IOException e) {
            sender.shutdown();
            listener.disconnected(id, this);
        }

        /** Queues a frame write; never blocks or touches the network on the calling thread. */
        private void enqueue(Runnable write) {
            try {
                sender.execute(write);
            } catch (RejectedExecutionException closed) {
                // Socket already shut down; nothing left to send.
            }
        }

        private void failed() {
            listener.disconnected(id, this);
        }

        public void text(String s) {
            enqueue(
                    () -> {
                        try {
                            send(s);
                        } catch (IOException e) {
                            failed();
                        }
                    });
        }

        public void bytes(byte[] b) {
            // Drop audio instead of building a backlog when the peer reads slowly.
            if (queuedAudioFrames.incrementAndGet() > MAX_QUEUED_AUDIO_FRAMES) {
                queuedAudioFrames.decrementAndGet();
                return;
            }
            enqueue(
                    () -> {
                        try {
                            send(b);
                        } catch (IOException e) {
                            failed();
                        } finally {
                            queuedAudioFrames.decrementAndGet();
                        }
                    });
        }

        public void ping() {
            enqueue(
                    () -> {
                        try {
                            ping(new byte[0]);
                        } catch (IOException e) {
                            failed();
                        }
                    });
        }

        public void close() {
            enqueue(
                    () -> {
                        try {
                            close(WebSocketFrame.CloseCode.NormalClosure, "ended", false);
                        } catch (IOException ignored) {
                        } finally {
                            sender.shutdown();
                        }
                    });
        }
    }
}
