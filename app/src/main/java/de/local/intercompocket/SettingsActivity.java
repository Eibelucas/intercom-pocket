package de.local.intercompocket;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class SettingsActivity extends AppCompatActivity {
    private static final String[] THEME_VALUES = {"system", "light", "dark"};

    private final PermissionHelper permissions = new PermissionHelper(this);
    private Config config;
    private TextInputLayout nameLayout, keyLayout;
    private TextInputEditText fieldName, fieldKey;
    private MaterialSwitch switchTls, switchAnnouncements, switchBoot;
    private View rowPhone, rowBattery, rowQuiet, rowFullScreen, rowTheme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        config = new Config(this);
        setContentView(R.layout.activity_settings);
        Ui.setupSubScreen(this, R.string.settings_title);
        Ui.applyBottomInsets(findViewById(R.id.scroll));

        nameLayout = findViewById(R.id.layout_name);
        keyLayout = findViewById(R.id.layout_key);
        fieldName = findViewById(R.id.field_name);
        fieldKey = findViewById(R.id.field_key);
        switchTls = findViewById(R.id.switch_tls);
        switchAnnouncements = findViewById(R.id.switch_announcements);
        switchBoot = findViewById(R.id.switch_boot);
        rowPhone = findViewById(R.id.row_phone);
        rowBattery = findViewById(R.id.row_battery);
        rowQuiet = findViewById(R.id.row_quiet);
        rowFullScreen = findViewById(R.id.row_fullscreen);
        rowTheme = findViewById(R.id.row_theme);
        MaterialButton save = findViewById(R.id.btn_save);

        if (savedInstanceState == null) {
            fieldName.setText(config.name());
            fieldKey.setText(config.key());
            switchTls.setChecked(config.tls());
            switchAnnouncements.setChecked(config.acceptAnnouncements());
            switchBoot.setChecked(config.autoStart());
        }
        save.setOnClickListener(v -> save());
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindRows();
    }

    private void bindRows() {
        Ui.bindRow(
                rowPhone,
                R.string.row_phone,
                getString(
                        PhoneIntegration.ready(this)
                                ? R.string.state_active
                                : R.string.state_not_set_up),
                () -> PhoneSetupFlow.start(this, permissions));
        boolean unrestricted =
                getSystemService(PowerManager.class)
                        .isIgnoringBatteryOptimizations(getPackageName());
        Ui.bindRow(
                rowBattery,
                R.string.row_battery,
                getString(unrestricted ? R.string.state_unrestricted : R.string.state_optimized),
                this::batterySettings);
        Ui.bindRow(
                rowQuiet,
                R.string.row_quiet,
                config.quietEnabled()
                        ? clock(config.quietStart()) + " – " + clock(config.quietEnd())
                        : getString(R.string.state_off),
                this::quietDialog);
        rowFullScreen.setVisibility(Build.VERSION.SDK_INT >= 34 ? View.VISIBLE : View.GONE);
        Ui.bindRow(
                rowFullScreen,
                R.string.row_fullscreen,
                "",
                () ->
                        startActivity(
                                new Intent(
                                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                        Uri.parse("package:" + getPackageName()))));
        String[] themeLabels = getResources().getStringArray(R.array.theme_options);
        Ui.bindRow(rowTheme, R.string.row_theme, themeLabels[themeIndex()], this::themeDialog);
    }

    // ---------------------------------------------------------------- save

    private void save() {
        String name = String.valueOf(fieldName.getText()).trim();
        String key = String.valueOf(fieldKey.getText()).trim();
        nameLayout.setError(null);
        keyLayout.setError(null);
        if (name.isEmpty() || name.length() > 48) {
            nameLayout.setError(getString(R.string.error_name));
            return;
        }
        if (key.isEmpty() || key.length() > 4096) {
            keyLayout.setError(getString(R.string.error_key));
            return;
        }
        try {
            config.save(name, key, switchTls.isChecked());
            config.setAutoStart(switchBoot.isChecked());
            config.setAcceptAnnouncements(switchAnnouncements.isChecked());
            if (IntercomService.instance != null && IntercomService.instance.engine != null)
                IntercomControl.reload(this);
            else IntercomControl.enable(this, permissions);
            Ui.toast(this, R.string.toast_settings_saved);
            finish();
        } catch (Exception e) {
            Ui.toast(this, R.string.toast_settings_save_failed);
        }
    }

    // ---------------------------------------------------------------- theme

    private int themeIndex() {
        String mode = config.themeMode();
        for (int i = 0; i < THEME_VALUES.length; i++) if (THEME_VALUES[i].equals(mode)) return i;
        return 0;
    }

    private void themeDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.row_theme)
                .setSingleChoiceItems(
                        getResources().getStringArray(R.array.theme_options),
                        themeIndex(),
                        (dialog, choice) -> {
                            config.setThemeMode(THEME_VALUES[choice]);
                            dialog.dismiss();
                            App.applyThemeMode(this); // recreates visible activities
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- battery

    private void batterySettings() {
        if (getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            Ui.toast(this, R.string.toast_battery_exempt);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.battery_title)
                .setMessage(R.string.battery_message)
                .setNegativeButton(R.string.later, null)
                .setPositiveButton(
                        R.string.open_setting,
                        (d, w) ->
                                startActivity(
                                        new Intent(
                                                Settings
                                                        .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                Uri.parse("package:" + getPackageName()))))
                .show();
    }

    // ---------------------------------------------------------------- quiet hours

    private static String clock(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private void quietDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_quiet_hours, null);
        MaterialSwitch enabled = content.findViewById(R.id.switch_quiet);
        MaterialButton from = content.findViewById(R.id.btn_quiet_start);
        MaterialButton until = content.findViewById(R.id.btn_quiet_end);
        ChipGroup dayGroup = content.findViewById(R.id.day_group);
        MaterialButton exceptionsButton = content.findViewById(R.id.btn_quiet_exceptions);

        enabled.setChecked(config.quietEnabled());
        int[] start = {config.quietStart()};
        int[] end = {config.quietEnd()};
        from.setText(getString(R.string.quiet_from, clock(start[0])));
        until.setText(getString(R.string.quiet_until, clock(end[0])));
        from.setOnClickListener(
                v ->
                        pickTime(
                                start,
                                () ->
                                        from.setText(
                                                getString(R.string.quiet_from, clock(start[0])))));
        until.setOnClickListener(
                v ->
                        pickTime(
                                end,
                                () ->
                                        until.setText(
                                                getString(R.string.quiet_until, clock(end[0])))));

        String[] days = getResources().getStringArray(R.array.weekdays_short);
        int mask = config.quietDays();
        for (int i = 0; i < days.length; i++) {
            Chip chip =
                    (Chip) LayoutInflater.from(this).inflate(R.layout.chip_choice, dayGroup, false);
            chip.setText(days[i]);
            chip.setChecked((mask & (1 << i)) != 0);
            dayGroup.addView(chip);
        }

        Set<String> exceptions = new HashSet<>(config.exceptions());
        exceptionsButton.setOnClickListener(v -> chooseExceptions(exceptions));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.row_quiet)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.save,
                        (d, w) -> {
                            int daysMask = 0;
                            for (int i = 0; i < dayGroup.getChildCount(); i++) {
                                if (((Chip) dayGroup.getChildAt(i)).isChecked()) daysMask |= 1 << i;
                            }
                            config.saveQuietHours(
                                    enabled.isChecked(), daysMask, start[0], end[0], exceptions);
                            bindRows();
                        })
                .show();
    }

    private void pickTime(int[] minutes, Runnable changed) {
        MaterialTimePicker picker =
                new MaterialTimePicker.Builder()
                        .setTitleText(R.string.pick_time)
                        .setTimeFormat(TimeFormat.CLOCK_24H)
                        .setHour(minutes[0] / 60)
                        .setMinute(minutes[0] % 60)
                        .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                        .build();
        picker.addOnPositiveButtonClickListener(
                v -> {
                    minutes[0] = picker.getHour() * 60 + picker.getMinute();
                    changed.run();
                });
        picker.show(getSupportFragmentManager(), "time_picker");
    }

    private void chooseExceptions(Set<String> exceptions) {
        JSONArray known = config.known();
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < known.length(); i++) {
            JSONObject p = known.optJSONObject(i);
            if (p == null || p.optString("id").isEmpty()) continue;
            ids.add(p.optString("id"));
            names.add(config.display(Peer.from(p, p.optString("host"))));
        }
        if (ids.isEmpty()) {
            Ui.toast(this, R.string.toast_connect_kiosk_first);
            return;
        }
        boolean[] selected = new boolean[ids.size()];
        for (int i = 0; i < selected.length; i++) selected[i] = exceptions.contains(ids.get(i));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.quiet_exceptions_title)
                .setMultiChoiceItems(
                        names.toArray(new String[0]),
                        selected,
                        (d, i, on) -> {
                            if (on) exceptions.add(ids.get(i));
                            else exceptions.remove(ids.get(i));
                        })
                .setPositiveButton(R.string.done, null)
                .show();
    }
}
