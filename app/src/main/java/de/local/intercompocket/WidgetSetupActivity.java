package de.local.intercompocket;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Widget configuration: pick the kiosk the widget calls. Shown as a dialog over a transparent
 * window.
 */
public final class WidgetSetupActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        int widgetId =
                getIntent()
                        .getIntExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_ID,
                                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        Config config = new Config(this);
        JSONArray known = config.known();
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < known.length(); i++) {
            JSONObject p = known.optJSONObject(i);
            if (p == null || p.optString("id").isEmpty()) continue;
            ids.add(p.optString("id"));
            names.add(config.peerName(p.optString("id")));
        }
        if (ids.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.widget_title)
                    .setMessage(R.string.widget_no_kiosk)
                    .setPositiveButton(R.string.ok, (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.widget_pick_kiosk)
                .setItems(
                        names.toArray(new String[0]),
                        (d, index) -> {
                            config.setWidgetTarget(widgetId, ids.get(index));
                            KioskWidget.update(this, widgetId);
                            setResult(
                                    RESULT_OK,
                                    new Intent()
                                            .putExtra(
                                                    AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
                            finish();
                        })
                .setNegativeButton(R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }
}
