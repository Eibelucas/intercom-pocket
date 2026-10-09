package de.local.intercompocket;

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.StringRes;

/**
 * One permission request at a time; runs the pending action once the user answered. Create it as an
 * activity field.
 */
final class PermissionHelper {
    private static final class Pending {
        final Runnable action;
        final int deniedMessage;
        final boolean runWhenDenied;

        Pending(Runnable action, int deniedMessage, boolean runWhenDenied) {
            this.action = action;
            this.deniedMessage = deniedMessage;
            this.runWhenDenied = runWhenDenied;
        }
    }

    private final ComponentActivity activity;
    private final ActivityResultLauncher<String> launcher;
    private Pending pending;

    PermissionHelper(ComponentActivity activity) {
        this.activity = activity;
        this.launcher =
                activity.registerForActivityResult(
                        new ActivityResultContracts.RequestPermission(), this::onResult);
    }

    boolean has(String permission) {
        return activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    void require(
            String permission,
            @StringRes int deniedMessage,
            boolean runWhenDenied,
            Runnable action) {
        if (has(permission)) {
            action.run();
            return;
        }
        pending = new Pending(action, deniedMessage, runWhenDenied);
        launcher.launch(permission);
    }

    /**
     * Runs the action with microphone access; failures while starting audio are reported instead of
     * crashing.
     */
    void requireMicrophone(Runnable action) {
        require(
                Manifest.permission.RECORD_AUDIO,
                R.string.toast_mic_permission_denied,
                false,
                () -> {
                    try {
                        action.run();
                    } catch (RuntimeException e) {
                        Ui.toast(activity, R.string.toast_mic_start_failed);
                    }
                });
    }

    private void onResult(boolean granted) {
        Pending request = pending;
        pending = null;
        if (request == null) return;
        if (!granted) Ui.toast(activity, request.deniedMessage);
        if (granted || request.runWhenDenied) request.action.run();
    }
}
