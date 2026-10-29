package de.local.intercompocket;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Home-screen shortcut that calls one kiosk. Colors come from the dynamic system palette via the
 * layout.
 */
public final class KioskWidget extends AppWidgetProvider {
    static void update(Context context, int widgetId) {
        Config config = new Config(context);
        String target = config.widgetTarget(widgetId);
        String name = context.getString(R.string.widget_select);
        JSONArray known = config.known();
        for (int i = 0; i < known.length(); i++) {
            JSONObject p = known.optJSONObject(i);
            if (p != null && target.equals(p.optString("id"))) {
                name = config.peerName(target);
                break;
            }
        }
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.kiosk_widget);
        view.setTextViewText(R.id.widget_name, name);
        Intent open =
                new Intent(context, MainActivity.class)
                        .putExtra("quickPeer", target)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending =
                PendingIntent.getActivity(
                        context,
                        widgetId,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        view.setOnClickPendingIntent(R.id.widget_root, pending);
        AppWidgetManager.getInstance(context).updateAppWidget(widgetId, view);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, id);
    }

    static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : manager.getAppWidgetIds(new ComponentName(context, KioskWidget.class)))
            update(context, id);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        android.content.SharedPreferences.Editor edit = new Config(context).prefs.edit();
        for (int id : ids) edit.remove("widget:" + id);
        edit.apply();
    }
}
