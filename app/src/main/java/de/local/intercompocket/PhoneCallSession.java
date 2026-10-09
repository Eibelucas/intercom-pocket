package de.local.intercompocket;

/** Call-id-scoped lifecycle. Android grants focus before we open audio. */
final class PhoneCallSession {
    interface Display {
        void accepted();

        void closed(int cause);
    }

    private final Engine engine;
    private final String id;
    private final Runnable prepare;
    private final Display display;
    private boolean focus, answered, started, closed;

    PhoneCallSession(Engine e, String id, Runnable prepare, Display display, boolean focus) {
        this.engine = e;
        this.id = id;
        this.prepare = prepare;
        this.display = display;
        this.focus = focus;
    }

    boolean current() {
        synchronized (engine) {
            return !closed && engine.phoneManaged && id.equals(engine.callId) && engine.active();
        }
    }

    /** Localized text for the call, e.g. the reason shown after the call ended. */
    String text(int resource) {
        return engine.msg.get(resource);
    }

    void answer() {
        synchronized (engine) {
            if (!current() || answered || !engine.state.equals("ringing")) return;
            if (engine.peer.tls && engine.tls.saved(engine.peer, false).isEmpty()) {
                engine.endIf(
                        id,
                        text(R.string.err_confirm_cert_phone),
                        true);
                return;
            }
            answered = true;
            engine.diagnostics.event("Accepted in phone app");
            engine.arm(id, 12000, text(R.string.err_android_audio_denied));
            display.accepted();
            startIfReady();
        }
    }

    void focusGained() {
        synchronized (engine) {
            if (!current()) return;
            focus = true;
            engine.diagnostics.event("Android call focus granted");
            startIfReady();
        }
    }

    private void startIfReady() {
        if (!current() || !answered || !focus || started) return;
        started = true;
        try {
            prepare.run();
            engine.answer();
        } catch (RuntimeException ex) {
            engine.diagnostics.error("Phone microphone release", ex);
            engine.endIf(
                    id,
                    text(R.string.err_mic_blocked),
                    true);
        }
    }

    void mute(boolean muted) {
        synchronized (engine) {
            if (current()) engine.phoneMute(muted);
        }
    }

    void end(String reason, int cause) {
        synchronized (engine) {
            if (!current()) return;
            engine.endIf(id, reason, true);
            close(cause);
        }
    }

    void focusLost() {
        synchronized (engine) {
            if (current() && answered)
                end(text(R.string.err_interrupted_phone), android.telecom.DisconnectCause.LOCAL);
            focus = false;
        }
    }

    void close(int cause) {
        synchronized (engine) {
            if (closed) return;
            closed = true;
            display.closed(cause);
        }
    }
}
