package de.local.intercompocket;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dialogs of the home screen: adding, editing and explaining kiosks, snooze and announcement
 * targets.
 */
final class KioskDialogs {
    interface Host {
        Engine engine();

        void startAnnouncement(Set<String> members, String title);

        /** Something visible changed (alias, favorite, DND ...); re-render on the next tick. */
        void invalidate();
    }

    static final String[] GLYPHS = {"⌂", "✦", "●", "♪", "☼", "★"};

    private final AppCompatActivity activity;
    private final Config config;
    private final Host host;

    KioskDialogs(AppCompatActivity activity, Config config, Host host) {
        this.activity = activity;
        this.config = config;
        this.host = host;
    }

    void addPeer() {
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_text_input, null);
        TextInputLayout layout = content.findViewById(R.id.input_layout);
        EditText input = content.findViewById(R.id.input_field);
        layout.setHint(activity.getString(R.string.field_address));
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.add_kiosk)
                .setMessage(R.string.add_kiosk_message)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.add,
                        (d, w) -> {
                            Engine e = host.engine();
                            if (e == null) return;
                            try {
                                e.add(input.getText().toString().trim());
                            } catch (Exception ex) {
                                Ui.toast(activity, String.valueOf(ex.getMessage()));
                            }
                        })
                .show();
    }

    void roomOptions(Engine e, Peer p) {
        String[] options = {
            activity.getString(
                    config.favorite(p.id) ? R.string.favorite_remove : R.string.favorite_add),
            activity.getString(R.string.edit_name_symbol),
            activity.getString(R.string.remove_kiosk)
        };
        new MaterialAlertDialogBuilder(activity)
                .setTitle(config.display(p))
                .setItems(
                        options,
                        (d, which) -> {
                            if (which == 0) {
                                config.setFavorite(p.id, !config.favorite(p.id));
                                host.invalidate();
                            } else if (which == 1) {
                                editRoom(p);
                            } else if (p.manual) {
                                new MaterialAlertDialogBuilder(activity)
                                        .setMessage(R.string.remove_kiosk_confirm)
                                        .setNegativeButton(R.string.cancel, null)
                                        .setPositiveButton(
                                                R.string.remove, (dialog, w) -> e.remove(p))
                                        .show();
                            } else {
                                Ui.toast(activity, R.string.toast_auto_peers);
                            }
                        })
                .show();
    }

    private void editRoom(Peer p) {
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_room, null);
        EditText name = content.findViewById(R.id.input_field);
        name.setText(config.alias(p.id));
        ChipGroup group = content.findViewById(R.id.glyph_group);
        String current = config.icon(p.id);
        for (String glyph : GLYPHS) {
            Chip chip =
                    (Chip)
                            LayoutInflater.from(activity)
                                    .inflate(R.layout.chip_choice, group, false);
            chip.setId(View.generateViewId());
            chip.setText(glyph);
            chip.setTag(glyph);
            group.addView(chip);
            if (glyph.equals(current)) group.check(chip.getId());
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.edit_name_symbol)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.save,
                        (d, w) -> {
                            String alias = name.getText().toString().trim();
                            if (alias.length() > 40) {
                                Ui.toast(activity, R.string.toast_name_too_long);
                                return;
                            }
                            Chip checked = group.findViewById(group.getCheckedChipId());
                            String glyph = checked == null ? current : (String) checked.getTag();
                            config.setAliasAndIcon(p.id, alias, glyph);
                            KioskWidget.refreshAll(activity);
                            host.invalidate();
                        })
                .show();
    }

    void snooze() {
        String[] labels = activity.getResources().getStringArray(R.array.snooze_options);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dnd_snooze)
                .setItems(
                        labels,
                        (dialog, choice) -> {
                            if (choice == 3) {
                                config.setDnd(true);
                            } else {
                                long until =
                                        choice == 2
                                                ? ZonedDateTime.now()
                                                        .plusDays(1)
                                                        .withHour(7)
                                                        .withMinute(0)
                                                        .withSecond(0)
                                                        .withNano(0)
                                                        .toInstant()
                                                        .toEpochMilli()
                                                : System.currentTimeMillis()
                                                        + (choice == 0 ? 30 : 120) * 60_000L;
                                config.snoozeUntil(until);
                            }
                            Engine e = host.engine();
                            if (e != null) e.changed();
                            host.invalidate();
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    void chooseAnnouncement() {
        List<RoomGroups.Group> groups = new RoomGroups(config.prefs).list();
        ArrayList<String> labels =
                new ArrayList<>(
                        Arrays.asList(
                                activity.getString(R.string.announce_all),
                                activity.getString(R.string.announce_select)));
        for (RoomGroups.Group group : groups) {
            labels.add(
                    activity.getString(
                            R.string.announce_group_label, group.name, group.members.size()));
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.announce_to)
                .setItems(
                        labels.toArray(new String[0]),
                        (dialog, choice) -> {
                            if (choice == 0) {
                                host.startAnnouncement(
                                        null, activity.getString(R.string.announce_title_all));
                            } else if (choice == 1) {
                                selectAnnouncementPeers();
                            } else {
                                RoomGroups.Group group = groups.get(choice - 2);
                                host.startAnnouncement(group.members, group.name);
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void selectAnnouncementPeers() {
        Engine e = host.engine();
        if (e == null) return;
        LinkedHashMap<String, Peer> unique = new LinkedHashMap<>();
        for (Peer peer : Peers.sorted(e, config)) {
            if (peer.ready && !peer.id.isEmpty()) unique.putIfAbsent(peer.id, peer);
        }
        ArrayList<Peer> peers = new ArrayList<>(unique.values());
        if (peers.isEmpty()) {
            Ui.toast(activity, R.string.toast_no_peer_ready);
            return;
        }
        String[] labels = new String[peers.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = config.display(peers.get(i));
        Set<String> selected = new LinkedHashSet<>();
        AlertDialog dialog =
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.announce_select)
                        .setMultiChoiceItems(
                                labels,
                                new boolean[labels.length],
                                (d, i, on) -> {
                                    if (on) selected.add(peers.get(i).id);
                                    else selected.remove(peers.get(i).id);
                                })
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.start_announcement, null)
                        .create();
        // Own click listener so an invalid selection keeps the dialog open.
        dialog.setOnShowListener(
                d ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(
                                        v -> {
                                            if (selected.isEmpty()
                                                    || selected.size() > RoomGroups.MAX_MEMBERS) {
                                                Ui.toast(activity, R.string.toast_select_1_32);
                                                return;
                                            }
                                            dialog.dismiss();
                                            host.startAnnouncement(
                                                    selected,
                                                    activity.getString(
                                                            R.string.announce_title_selected));
                                        }));
        dialog.show();
    }

    void trust(Peer p) {
        Engine e = host.engine();
        if (e == null || p == null) return;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.trust_title)
                .setMessage(
                        activity.getString(
                                R.string.trust_message,
                                p.name,
                                p.host,
                                p.candidatePort,
                                p.candidatePin))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.trust_confirm, (d, w) -> e.trust(p))
                .show();
    }

    void explain(Peer p) {
        int hintRes;
        switch (p.status) {
            case KEY_MISMATCH:
                hintRes = R.string.explain_key;
                break;
            case TLS_MISMATCH:
                hintRes = R.string.explain_tls;
                break;
            default:
                hintRes = R.string.explain_default;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(config.display(p))
                .setMessage(activity.getString(p.status.label) + "\n\n" + activity.getString(hintRes))
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}
