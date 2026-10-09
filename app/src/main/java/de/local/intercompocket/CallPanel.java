package de.local.intercompocket;

import android.view.MotionEvent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Locale;

/**
 * The in-call screen (incoming, outgoing, announcement). Views are created once and updated in
 * place.
 */
final class CallPanel {
    interface Host {
        Engine engine();

        void acceptCall();

        void trust(Peer peer);
    }

    private static final String GLYPH_ANNOUNCEMENT = "◉";
    private static final String GLYPH_UNKNOWN = "●";

    private final View root;
    private final Config config;
    private final Host host;
    private final TextView tag, glyph, name, status, timer, error, hint;
    private final LevelMeter meter;
    private final MaterialButton trust, accept, talk, decline;
    private final MaterialSwitch handsFree, speaker;
    private final View switches;
    private final CompoundButton.OnCheckedChangeListener handsFreeListener;
    private final CompoundButton.OnCheckedChangeListener speakerListener;

    private boolean touchHeld;
    private int finishReason = R.string.msg_call_ended;

    CallPanel(View root, Config config, Host host) {
        this.root = root;
        this.config = config;
        this.host = host;
        tag = root.findViewById(R.id.call_tag);
        glyph = root.findViewById(R.id.call_glyph);
        name = root.findViewById(R.id.call_name);
        status = root.findViewById(R.id.call_status);
        timer = root.findViewById(R.id.call_timer);
        error = root.findViewById(R.id.call_error);
        hint = root.findViewById(R.id.call_hint);
        meter = root.findViewById(R.id.level_meter);
        trust = root.findViewById(R.id.btn_trust);
        accept = root.findViewById(R.id.btn_accept);
        talk = root.findViewById(R.id.btn_talk);
        decline = root.findViewById(R.id.btn_decline);
        handsFree = root.findViewById(R.id.switch_hands_free);
        speaker = root.findViewById(R.id.switch_speaker);
        switches = root.findViewById(R.id.call_switches);

        meter.setLevelSource(
                () -> {
                    Engine e = host.engine();
                    return e == null ? 0 : e.audio.level;
                });
        accept.setOnClickListener(v -> host.acceptCall());
        trust.setOnClickListener(
                v -> {
                    Engine e = host.engine();
                    if (e != null && e.peer != null) host.trust(e.peer);
                });
        decline.setOnClickListener(
                v -> {
                    Engine e = host.engine();
                    if (e != null) e.finish(root.getContext().getString(finishReason), true);
                });
        setUpTalkButton();

        handsFreeListener =
                (button, on) -> {
                    Engine e = host.engine();
                    if (e == null) return;
                    e.handsFree(on);
                    updateTalk(e);
                };
        speakerListener =
                (button, on) -> {
                    Engine e = host.engine();
                    if (e == null) return;
                    e.speaker = on;
                    e.audio.speaker(on);
                };
        handsFree.setOnCheckedChangeListener(handsFreeListener);
        speaker.setOnCheckedChangeListener(speakerListener);
    }

    /** Hold to talk, or tap to toggle the microphone while hands-free is on. */
    private void setUpTalkButton() {
        talk.setOnTouchListener(
                (v, event) -> {
                    Engine e = host.engine();
                    if (e == null) return true;
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        if (!e.handsFree) {
                            touchHeld = true;
                            e.talk(true);
                            updateTalk(e);
                        }
                        return true;
                    }
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        if (touchHeld) {
                            e.talk(false);
                            updateTalk(e);
                        }
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        if (action == MotionEvent.ACTION_UP) v.performClick();
                        else touchHeld = false;
                    }
                    return true;
                });
        talk.setOnClickListener(
                v -> {
                    if (touchHeld) {
                        touchHeld = false;
                        return;
                    }
                    Engine e = host.engine();
                    if (e == null) return;
                    if (e.handsFree) e.talk(!e.sending);
                    else e.handsFree(true);
                    updateTalk(e);
                });
    }

    /** Structural update: which controls are visible and what the texts say. */
    void render(Engine e) {
        boolean ringing = e.state.equals("ringing");
        boolean live = e.state.equals("in_call") || e.state.equals("broadcasting");
        boolean phone = e.phoneManaged;

        tag.setText(
                ringing
                        ? R.string.tag_incoming
                        : e.broadcast ? R.string.tag_announcement : R.string.tag_call);
        glyph.setText(
                e.broadcast
                        ? GLYPH_ANNOUNCEMENT
                        : e.peer == null ? GLYPH_UNKNOWN : config.icon(e.peer.id));
        name.setText(
                e.state.equals("broadcasting")
                        ? e.announcementTitle
                        : e.peer == null
                                ? root.getContext().getString(R.string.fallback_kiosk)
                                : config.display(e.peer));
        status.setText(statusText(e));

        boolean showError = !e.detail.isEmpty() && !live;
        error.setVisibility(showError ? View.VISIBLE : View.GONE);
        error.setText(e.detail);
        trust.setVisibility(
                e.peer != null && !e.peer.candidatePin.isEmpty() ? View.VISIBLE : View.GONE);

        accept.setVisibility(!phone && ringing ? View.VISIBLE : View.GONE);
        talk.setVisibility(!phone && live ? View.VISIBLE : View.GONE);
        switches.setVisibility(!phone && live ? View.VISIBLE : View.GONE);
        hint.setVisibility(phone || live ? View.VISIBLE : View.GONE);

        if (phone) {
            hint.setText(
                    ringing
                            ? R.string.phone_hint_ringing
                            : !e.audio.microphoneAvailable && e.state.equals("in_call")
                                    ? R.string.phone_hint_no_mic
                                    : R.string.phone_hint_active);
        } else if (live) {
            updateTalk(e);
        }

        if (phone) {
            decline.setText(ringing ? R.string.decline : R.string.end_call);
            finishReason = ringing ? R.string.msg_call_declined : R.string.msg_hung_up_phone;
        } else if (ringing) {
            decline.setText(R.string.decline);
            finishReason = R.string.msg_call_declined;
        } else if (live) {
            decline.setText(R.string.end_call);
            finishReason = R.string.msg_call_ended;
        } else {
            decline.setText(e.broadcast ? R.string.close_announcement : R.string.cancel_call);
            finishReason = R.string.msg_call_ended;
        }
    }

    /** Cheap per-tick update: elapsed time and level meter. */
    void tick(Engine e) {
        long seconds = e.since == 0 ? 0 : (System.currentTimeMillis() - e.since) / 1000;
        timer.setText(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60));
        meter.invalidate();
    }

    private String statusText(Engine e) {
        int res;
        switch (e.state) {
            case "calling":
                res = R.string.status_calling;
                break;
            case "ringing":
                res = R.string.status_ringing;
                break;
            case "connecting":
                res = R.string.status_connecting;
                break;
            case "listening":
                res = R.string.status_listening;
                break;
            case "broadcasting":
                return e.detail;
            default:
                res = R.string.status_connected;
        }
        return root.getContext().getString(res);
    }

    private void updateTalk(Engine e) {
        boolean mic = e.audio.microphoneAvailable;
        talk.setEnabled(mic);
        handsFree.setEnabled(mic);
        if (!mic) {
            talk.setText(R.string.talk_unavailable);
            hint.setText(R.string.mic_unavailable_hint);
        } else if (e.handsFree) {
            talk.setText(e.sending ? R.string.talk_mute : R.string.talk_unmute);
            hint.setText(e.sending ? R.string.hint_handsfree_on : R.string.hint_muted);
        } else {
            talk.setText(e.sending ? R.string.talk_speaking : R.string.talk_hold);
            hint.setText(e.sending ? R.string.hint_transmitting : R.string.hint_hold);
        }
        setSilently(handsFree, handsFreeListener, e.handsFree);
        setSilently(speaker, speakerListener, e.speaker);
    }

    private static void setSilently(
            CompoundButton button,
            CompoundButton.OnCheckedChangeListener listener,
            boolean checked) {
        if (button.isChecked() == checked) return;
        button.setOnCheckedChangeListener(null);
        button.setChecked(checked);
        button.setOnCheckedChangeListener(listener);
    }
}
