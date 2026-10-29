package de.local.intercompocket;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.telecom.TelecomManager;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Registers the Intercom phone account and sends the user to the system screen that enables it. */
final class PhoneSetupFlow {
    private PhoneSetupFlow() {}

    static void start(AppCompatActivity activity, PermissionHelper permissions) {
        permissions.requireMicrophone(
                () -> {
                    // Before Android 15 the account state can only be read with this permission.
                    if (Build.VERSION.SDK_INT < 35
                            && !permissions.has(Manifest.permission.READ_PHONE_NUMBERS)) {
                        permissions.require(
                                Manifest.permission.READ_PHONE_NUMBERS,
                                R.string.toast_phone_permission_denied,
                                false,
                                () -> start(activity, permissions));
                        return;
                    }
                    if (!PhoneIntegration.register(activity)) {
                        Ui.toast(activity, R.string.toast_phone_register_failed);
                        return;
                    }
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.phone_dialog_title)
                            .setMessage(R.string.phone_dialog_message)
                            .setNegativeButton(R.string.later, null)
                            .setPositiveButton(
                                    R.string.open_settings, (d, w) -> openAccountSettings(activity))
                            .show();
                });
    }

    private static void openAccountSettings(AppCompatActivity activity) {
        try {
            activity.startActivity(new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS));
        } catch (ActivityNotFoundException e) {
            Ui.toast(activity, R.string.toast_phone_open_manually);
        }
    }
}
