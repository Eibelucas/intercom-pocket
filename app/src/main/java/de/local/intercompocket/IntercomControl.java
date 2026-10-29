package de.local.intercompocket;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.appcompat.app.AppCompatActivity;

/** Starts, reloads and stops the foreground service. */
final class IntercomControl {
    private IntercomControl() {}

    static void enable(AppCompatActivity activity, PermissionHelper permissions) {
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.require(
                    Manifest.permission.POST_NOTIFICATIONS,
                    R.string.toast_notifications_denied,
                    true,
                    () -> start(activity));
        } else {
            start(activity);
        }
    }

    private static void start(Context context) {
        try {
            context.startForegroundService(
                    new Intent(context, IntercomService.class).setAction(IntercomService.ENABLE));
        } catch (RuntimeException e) {
            Ui.toast(context, context.getString(R.string.toast_start_failed, e.getMessage()));
        }
    }

    static void reload(Context context) {
        context.startService(
                new Intent(context, IntercomService.class).setAction(IntercomService.RELOAD));
    }

    static void stop(Context context) {
        context.startService(
                new Intent(context, IntercomService.class).setAction(IntercomService.STOP));
    }
}
