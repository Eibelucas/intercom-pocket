package de.local.intercompocket;

import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.Set;

/**
 * Home screen (availability + kiosk list) and the in-call screen, which swap inside the same
 * activity.
 */
public final class MainActivity extends AppCompatActivity
        implements CallPanel.Host, KioskDialogs.Host {
    private static final String EXTRA_ANSWER = "answer";
    private static final String EXTRA_CALL = "call";
    private static final String EXTRA_QUICK_PEER = "quickPeer";
    private static final long TICK_MS = 200;
    private static final long QUICK_CALL_WINDOW_MS = 15_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PermissionHelper permissions = new PermissionHelper(this);
    private final Runnable ticker =
            new Runnable() {
                @Override
                public void run() {
                    if (!resumed) return;
                    refresh();
                    handler.postDelayed(this, TICK_MS);
                }
            };
    private final OnBackPressedCallback endCallOnBack =
            new OnBackPressedCallback(false) {
                @Override
                public void handleOnBackPressed() {
                    confirmEndCall();
                }
            };

    private Config config;
    private KioskDialogs dialogs;
    private CallPanel callPanel;
    private MaterialToolbar toolbar;
    private View homeContent;
    private View callContent;

    private TextView statusTitle, statusHint, dndUntil, textError, textDetail, emptyTitle;
    private MaterialButton btnPower,
            btnEnable,
            btnSnooze,
            btnPhoneSetup,
            btnRefresh,
            btnAnnounce,
            btnAddKiosk;
    private MaterialSwitch switchDnd;
    private View dndGroup, cardPhone, cardEmpty, actions;
    private android.widget.LinearLayout kioskList;

    private String fingerprint = "";
    private boolean resumed;
    private boolean phoneReady;
    private boolean answerPending;
    private boolean quickStarted;
    private String quickPeer = "";
    private long quickUntil;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        config = new Config(this);
        dialogs = new KioskDialogs(this, config, this);
        setContentView(R.layout.activity_main);
        bindViews();
        Ui.applyBottomInsets(findViewById(R.id.scroll));
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        getOnBackPressedDispatcher().addCallback(this, endCallOnBack);
        handleIntent(getIntent());
        render(engine());
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);
        toolbar.inflateMenu(R.menu.main_menu);
        toolbar.setOnMenuItemClickListener(this::onMenuItem);

        homeContent = findViewById(R.id.home_content);
        callContent = findViewById(R.id.call_content);
        callPanel = new CallPanel(callContent, config, this);

        statusTitle = homeContent.findViewById(R.id.status_title);
        statusHint = homeContent.findViewById(R.id.status_hint);
        btnPower = homeContent.findViewById(R.id.btn_power);
        btnEnable = homeContent.findViewById(R.id.btn_enable);
        dndGroup = homeContent.findViewById(R.id.dnd_group);
        switchDnd = homeContent.findViewById(R.id.switch_dnd);
        dndUntil = homeContent.findViewById(R.id.dnd_until);
        btnSnooze = homeContent.findViewById(R.id.btn_snooze);
        cardPhone = homeContent.findViewById(R.id.card_phone);
        btnPhoneSetup = homeContent.findViewById(R.id.btn_phone_setup);
        textError = homeContent.findViewById(R.id.text_error);
        textDetail = homeContent.findViewById(R.id.text_detail);
        btnRefresh = homeContent.findViewById(R.id.btn_refresh);
        kioskList = homeContent.findViewById(R.id.kiosk_list);
        cardEmpty = homeContent.findViewById(R.id.card_empty);
        emptyTitle = homeContent.findViewById(R.id.empty_title);
        actions = homeContent.findViewById(R.id.actions);
        btnAnnounce = homeContent.findViewById(R.id.btn_announce);
        btnAddKiosk = homeContent.findViewById(R.id.btn_add_kiosk);

        btnPower.setOnClickListener(v -> IntercomControl.stop(this));
        btnSnooze.setOnClickListener(v -> dialogs.snooze());
        btnPhoneSetup.setOnClickListener(v -> PhoneSetupFlow.start(this, permissions));
        btnRefresh.setOnClickListener(
                v -> {
                    Engine e = engine();
                    if (e != null) e.refresh();
                });
        btnAnnounce.setOnClickListener(v -> dialogs.chooseAnnouncement());
        btnAddKiosk.setOnClickListener(v -> dialogs.addPeer());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        invalidate();
    }

    private void handleIntent(Intent intent) {
        answerPending = intent.getBooleanExtra(EXTRA_ANSWER, false);
        String wanted = intent.getStringExtra(EXTRA_QUICK_PEER);
        if (wanted != null && !wanted.isEmpty()) {
            quickPeer = wanted;
            quickUntil = System.currentTimeMillis() + QUICK_CALL_WINDOW_MS;
            quickStarted = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        KioskWidget.refreshAll(this);
        PhoneIntegration.register(this);
        phoneReady = PhoneIntegration.ready(this);
        invalidate();
        resumed = true;
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(ticker);
        Engine e = engine();
        if (e != null && !e.handsFree) e.talk(false);
        super.onPause();
    }

    @Override
    public Engine engine() {
        IntercomService service = IntercomService.instance;
        return service == null ? null : service.engine;
    }

    @Override
    public void invalidate() {
        fingerprint = "";
    }

    // ---------------------------------------------------------------- polling

    private void refresh() {
        Engine e = engine();
        String current = fingerprint(e);
        if (!current.equals(fingerprint)) {
            fingerprint = current;
            render(e);
        }
        if (e != null && e.active()) callPanel.tick(e);
        if (answerPending
                && e != null
                && e.state.equals("ringing")
                && e.callId.equals(getIntent().getStringExtra(EXTRA_CALL))) {
            answerPending = false;
            acceptCall();
        }
        if (!quickPeer.isEmpty()) continueQuickCall(e);
    }

    /**
     * Everything that changes what is on screen; the UI is only rebuilt when this string changes.
     */
    private String fingerprint(Engine e) {
        if (e == null) return "off" + IntercomService.lastError;
        StringBuilder f = new StringBuilder();
        f.append(e.state)
                .append(e.callId)
                .append(e.detail)
                .append(e.phoneManaged)
                .append(e.broadcast)
                .append(e.announcementTitle)
                .append(e.audio.microphoneAvailable)
                .append(config.dnd())
                .append(config.dndUntil())
                .append(config.quiet())
                .append(IntercomService.lastError);
        for (Peer p : Peers.sorted(e, config)) {
            f.append(p.name)
                    .append(p.status)
                    .append(p.ready)
                    .append(p.host)
                    .append(p.candidatePin)
                    .append(config.alias(p.id))
                    .append(config.favorite(p.id));
        }
        if (e.peer != null) f.append(e.peer.candidatePin);
        return f.toString();
    }

    // ---------------------------------------------------------------- rendering

    private void render(Engine e) {
        boolean calling = e != null && e.active();
        homeContent.setVisibility(calling ? View.GONE : View.VISIBLE);
        callContent.setVisibility(calling ? View.VISIBLE : View.GONE);
        toolbar.getMenu().setGroupVisible(R.id.group_main, !calling);
        endCallOnBack.setEnabled(calling);
        // Only a call may appear above the lock screen or wake the display. Otherwise the app
        // would stay on top of the keyguard whenever it was open while the phone locked.
        setShowWhenLocked(calling);
        setTurnScreenOn(calling && e.state.equals("ringing"));
        if (calling) callPanel.render(e);
        else renderHome(e);
    }

    private void renderHome(Engine e) {
        boolean on = e != null;
        renderAvailability(e);

        cardPhone.setVisibility(phoneReady ? View.GONE : View.VISIBLE);
        showText(textError, IntercomService.lastError);
        showText(textDetail, on ? e.detail : "");

        btnRefresh.setVisibility(on ? View.VISIBLE : View.GONE);
        actions.setVisibility(on ? View.VISIBLE : View.GONE);
        btnAnnounce.setEnabled(on && Peers.anyReady(e));

        kioskList.removeAllViews();
        boolean hasPeers = on && !e.peers.isEmpty();
        cardEmpty.setVisibility(hasPeers ? View.GONE : View.VISIBLE);
        emptyTitle.setText(on ? R.string.empty_searching_title : R.string.empty_off_title);
        if (hasPeers) {
            for (Peer p : Peers.sorted(e, config)) kioskList.addView(createKioskRow(e, p));
        }
    }

    private void renderAvailability(Engine e) {
        boolean on = e != null;
        int title =
                !on
                        ? R.string.status_offline
                        : config.dnd()
                                ? R.string.status_dnd
                                : config.quiet() ? R.string.status_quiet : R.string.status_online;
        statusTitle.setText(title);
        statusTitle.setTextColor(on ? Ui.primary(this) : Ui.onSurfaceVariant(this));
        statusHint.setText(
                on
                        ? getString(R.string.status_online_hint, config.name(), localAddress())
                        : getString(R.string.status_offline_hint));

        btnPower.setVisibility(on ? View.VISIBLE : View.GONE);
        boolean needsKey = config.key().isEmpty();
        btnEnable.setVisibility(on ? View.GONE : View.VISIBLE);
        btnEnable.setText(needsKey ? R.string.action_setup : R.string.action_enable);
        btnEnable.setOnClickListener(
                v -> {
                    if (needsKey) openSettings();
                    else IntercomControl.enable(this, permissions);
                });

        dndGroup.setVisibility(on ? View.VISIBLE : View.GONE);
        CompoundButton.OnCheckedChangeListener dndListener =
                (button, checked) -> {
                    config.setDnd(checked);
                    Engine current = engine();
                    if (current != null) current.changed();
                    invalidate();
                };
        switchDnd.setOnCheckedChangeListener(null);
        switchDnd.setChecked(config.dnd());
        switchDnd.setOnCheckedChangeListener(dndListener);
        if (config.dnd()) {
            long until = config.dndUntil();
            dndUntil.setText(
                    until == 0
                            ? getString(R.string.dnd_until_manual)
                            : getString(
                                    R.string.dnd_until_time,
                                    DateFormat.getDateTimeInstance(
                                                    DateFormat.SHORT, DateFormat.SHORT)
                                            .format(new Date(until))));
            dndUntil.setVisibility(View.VISIBLE);
        } else {
            dndUntil.setVisibility(View.GONE);
        }
    }

    private String localAddress() {
        String ip = Discovery.localIp();
        return ip.isEmpty() ? getString(R.string.wifi_not_connected) : ip;
    }

    private static void showText(TextView view, String text) {
        view.setText(text);
        view.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View createKioskRow(Engine e, Peer p) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_kiosk, kioskList, false);
        ((TextView) row.findViewById(R.id.kiosk_glyph)).setText(config.icon(p.id));
        ((TextView) row.findViewById(R.id.kiosk_name)).setText(config.display(p));
        row.findViewById(R.id.kiosk_favorite)
                .setVisibility(config.favorite(p.id) ? View.VISIBLE : View.GONE);
        TextView status = row.findViewById(R.id.kiosk_status);
        status.setText(p.status.label);
        status.setTextColor(p.ready ? Ui.primary(this) : Ui.onSurfaceVariant(this));
        ((TextView) row.findViewById(R.id.kiosk_host)).setText(p.host);

        MaterialButton call = row.findViewById(R.id.kiosk_call);
        MaterialButton info = row.findViewById(R.id.kiosk_info);
        call.setVisibility(p.ready ? View.VISIBLE : View.GONE);
        info.setVisibility(p.ready ? View.GONE : View.VISIBLE);
        info.setText(p.candidatePin.isEmpty() ? R.string.kiosk_info : R.string.kiosk_verify);
        call.setOnClickListener(
                v -> {
                    if (!p.candidatePin.isEmpty()) dialogs.trust(p);
                    else callPeer(e, p);
                });
        info.setOnClickListener(
                v -> {
                    if (!p.candidatePin.isEmpty()) dialogs.trust(p);
                    else dialogs.explain(p);
                });
        row.findViewById(R.id.kiosk_more).setOnClickListener(v -> dialogs.roomOptions(e, p));
        return row;
    }

    // ---------------------------------------------------------------- actions

    private boolean onMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_history) startActivity(new Intent(this, HistoryActivity.class));
        else if (id == R.id.action_groups) startActivity(new Intent(this, GroupsActivity.class));
        else if (id == R.id.action_settings) openSettings();
        else return false;
        return true;
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void callPeer(Engine e, Peer p) {
        permissions.requireMicrophone(
                () -> {
                    IntercomService service = IntercomService.instance;
                    if (service == null) return;
                    service.microphone();
                    e.call(p);
                });
    }

    @Override
    public void acceptCall() {
        Engine current = engine();
        if (current == null || current.phoneManaged) return;
        String expected = current.callId;
        permissions.requireMicrophone(
                () -> {
                    IntercomService service = IntercomService.instance;
                    if (service == null || service.engine == null) return;
                    Engine e = service.engine;
                    if (e.callId.equals(expected) && !e.phoneManaged) {
                        service.microphone();
                        e.answer();
                    }
                });
    }

    @Override
    public void trust(Peer peer) {
        dialogs.trust(peer);
    }

    @Override
    public void startAnnouncement(Set<String> members, String title) {
        permissions.requireMicrophone(
                () -> {
                    IntercomService service = IntercomService.instance;
                    if (service == null || service.engine == null) {
                        Ui.toast(this, R.string.toast_enable_intercom);
                        return;
                    }
                    Engine e = service.engine;
                    if (e.active()) {
                        Ui.toast(this, R.string.toast_finish_call_first);
                        return;
                    }
                    service.microphone();
                    e.announce(
                            members == null ? null : new java.util.LinkedHashSet<>(members), title);
                    if (!e.active()) e.changed();
                });
    }

    private void confirmEndCall() {
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.end_call_confirm)
                .setNegativeButton(R.string.keep_talking, null)
                .setPositiveButton(
                        R.string.end,
                        (d, w) -> {
                            Engine e = engine();
                            if (e != null) e.finish(getString(R.string.msg_call_ended), true);
                        })
                .show();
    }

    // ---------------------------------------------------------------- widget / history quick call

    /**
     * Calls the kiosk requested by a widget or the call history once it is reachable (max. 15 s).
     */
    private void continueQuickCall(Engine e) {
        if (System.currentTimeMillis() > quickUntil) {
            quickPeer = "";
            Ui.toast(this, R.string.toast_peer_unreachable);
            return;
        }
        if (e == null) {
            if (!quickStarted && !config.key().isEmpty()) {
                quickStarted = true;
                IntercomControl.enable(this, permissions);
            }
            return;
        }
        if (e.active()) {
            quickPeer = "";
            Ui.toast(this, R.string.toast_finish_call_first);
            return;
        }
        for (Peer p : e.peers.values()) {
            if (quickPeer.equals(p.id) && p.ready) {
                quickPeer = "";
                callPeer(e, p);
                return;
            }
        }
        JSONObject known = findKnownPeer(quickPeer);
        if (known == null) {
            quickPeer = "";
            Ui.toast(this, R.string.toast_peer_not_saved);
            return;
        }
        String host = known.optString("host");
        int port = known.optInt("port", 2324);
        for (Peer p : e.peers.values()) {
            if (host.equals(p.host) && port == p.adminPort) return; // already probing, wait
        }
        try {
            e.add((known.optBoolean("tls") ? "https://" : "http://") + host + ":" + port);
        } catch (Exception ex) {
            quickPeer = "";
            Ui.toast(this, R.string.toast_peer_address_invalid);
        }
    }

    private JSONObject findKnownPeer(String id) {
        JSONArray items = config.known();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return item;
        }
        return null;
    }
}
