package de.local.intercompocket;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Room groups: named sets of kiosks that can be addressed together by an announcement. */
public final class GroupsActivity extends AppCompatActivity {
    private Config config;
    private LinearLayout list;
    private View emptyHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        config = new Config(this);
        setContentView(R.layout.activity_groups);
        Ui.setupSubScreen(this, R.string.groups_title);
        Ui.applyBottomInsets(findViewById(R.id.scroll));
        list = findViewById(R.id.group_list);
        emptyHint = findViewById(R.id.groups_empty);
        ExtendedFloatingActionButton fab = findViewById(R.id.fab_add_group);
        Ui.applyFabInsets(fab);
        fab.setOnClickListener(v -> editGroup(null));
        render();
    }

    private void render() {
        list.removeAllViews();
        List<RoomGroups.Group> groups = new RoomGroups(config.prefs).list();
        emptyHint.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
        for (RoomGroups.Group group : groups) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_group, list, false);
            ((TextView) row.findViewById(R.id.group_name)).setText(group.name);
            ArrayList<String> names = new ArrayList<>();
            for (String id : group.members) names.add(config.peerName(id));
            ((TextView) row.findViewById(R.id.group_members)).setText(String.join(", ", names));
            row.findViewById(R.id.group_edit).setOnClickListener(v -> editGroup(group));
            row.findViewById(R.id.group_delete).setOnClickListener(v -> confirmDelete(group));
            list.addView(row);
        }
    }

    private void confirmDelete(RoomGroups.Group group) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.group_delete_title)
                .setMessage(getString(R.string.group_delete_message, group.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.delete,
                        (d, w) -> {
                            new RoomGroups(config.prefs).remove(group.id);
                            render();
                        })
                .show();
    }

    private void editGroup(RoomGroups.Group group) {
        LinkedHashMap<String, String> known = new LinkedHashMap<>();
        JSONArray saved = config.known();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject p = saved.optJSONObject(i);
            if (p != null && !p.optString("id").isEmpty())
                known.put(p.optString("id"), config.peerName(p.optString("id")));
        }
        if (group != null)
            for (String id : group.members) known.putIfAbsent(id, config.peerName(id));
        if (known.isEmpty()) {
            Ui.toast(this, R.string.toast_connect_kiosk_first);
            return;
        }
        ArrayList<String> ids = new ArrayList<>(known.keySet());
        ids.sort(Comparator.comparing(known::get));

        View content = LayoutInflater.from(this).inflate(R.layout.dialog_edit_group, null);
        EditText name = content.findViewById(R.id.input_field);
        if (group != null) name.setText(group.name);
        ChipGroup chips = content.findViewById(R.id.member_group);
        Set<String> preselected = group == null ? new LinkedHashSet<>() : group.members;
        for (String id : ids) {
            Chip chip =
                    (Chip) LayoutInflater.from(this).inflate(R.layout.chip_choice, chips, false);
            chip.setText(known.get(id));
            chip.setTag(id);
            chip.setChecked(preselected.contains(id));
            chips.addView(chip);
        }

        AlertDialog dialog =
                new MaterialAlertDialogBuilder(this)
                        .setTitle(
                                group == null
                                        ? R.string.group_create_title
                                        : R.string.group_edit_title)
                        .setView(content)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.save, null)
                        .create();
        // Own click listener so validation errors keep the dialog open.
        dialog.setOnShowListener(
                d ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(
                                        v -> {
                                            Set<String> selected = new LinkedHashSet<>();
                                            for (int i = 0; i < chips.getChildCount(); i++) {
                                                Chip chip = (Chip) chips.getChildAt(i);
                                                if (chip.isChecked())
                                                    selected.add((String) chip.getTag());
                                            }
                                            try {
                                                new RoomGroups(config.prefs)
                                                        .save(
                                                                group == null ? null : group.id,
                                                                name.getText().toString(),
                                                                selected);
                                                dialog.dismiss();
                                                render();
                                            } catch (RoomGroups.Invalid ex) {
                                                Ui.toast(this, ex.messageRes);
                                            }
                                        }));
        dialog.show();
    }
}
