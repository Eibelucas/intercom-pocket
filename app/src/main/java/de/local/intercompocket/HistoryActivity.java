package de.local.intercompocket;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Metadata-only call history (no audio, no shared secret) with a verified call-back. */
public final class HistoryActivity extends AppCompatActivity {
    private Config config;
    private LinearLayout list;
    private View empty;
    private MaterialToolbar toolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        config = new Config(this);
        setContentView(R.layout.activity_history);
        toolbar = Ui.setupSubScreen(this, R.string.history_title);
        toolbar.inflateMenu(R.menu.history_menu);
        toolbar.setOnMenuItemClickListener(
                item -> {
                    if (item.getItemId() != R.id.action_clear_history) return false;
                    confirmClear();
                    return true;
                });
        Ui.applyBottomInsets(findViewById(R.id.scroll));
        list = findViewById(R.id.history_list);
        empty = findViewById(R.id.history_empty);
        render();
    }

    private void render() {
        list.removeAllViews();
        ArrayList<JSONObject> rows = new CallHistory(config.prefs).entries();
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        toolbar.getMenu().findItem(R.id.action_clear_history).setVisible(!rows.isEmpty());
        DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (JSONObject item : rows) list.addView(createRow(item, dates));
    }

    private View createRow(JSONObject item, DateFormat dates) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_history, list, false);
        String id = item.optString("id");
        boolean announcement = item.optBoolean("announcement");
        String alias = config.alias(id);
        String name =
                announcement
                        ? getString(
                                R.string.history_announcement,
                                item.optString("name", getString(R.string.history_kiosks)))
                        : alias.isEmpty()
                                ? item.optString("name", getString(R.string.fallback_kiosk))
                                : alias;

        ImageView icon = row.findViewById(R.id.history_icon);
        icon.setImageResource(
                announcement
                        ? R.drawable.ic_volume_up
                        : item.optBoolean("out")
                                ? R.drawable.ic_north_east
                                : R.drawable.ic_south_west);
        ((TextView) row.findViewById(R.id.history_name)).setText(name);
        ((TextView) row.findViewById(R.id.history_time))
                .setText(dates.format(new Date(item.optLong("at"))));

        long seconds = item.optLong("seconds");
        String result = item.optString("result");
        if (seconds > 0)
            result +=
                    "  ·  " + String.format(Locale.ROOT, "%d:%02d min", seconds / 60, seconds % 60);
        ((TextView) row.findViewById(R.id.history_result)).setText(result);

        View callback = row.findViewById(R.id.history_callback);
        if (id.isEmpty() || announcement) {
            callback.setVisibility(View.GONE);
        } else {
            callback.setOnClickListener(
                    v -> {
                        startActivity(
                                new Intent(this, MainActivity.class)
                                        .putExtra("quickPeer", id)
                                        .addFlags(
                                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                        finish();
                    });
        }
        return row;
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.history_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.delete,
                        (d, w) -> {
                            new CallHistory(config.prefs).clear();
                            render();
                        })
                .show();
    }
}
