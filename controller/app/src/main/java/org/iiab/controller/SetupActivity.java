/*
 * ============================================================================
 * Name        : SetupActivity.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Setup permission table helper and Locale configuration
 * ============================================================================
 */
package org.iiab.controller;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SetupActivity extends AppCompatActivity {

    private SwitchCompat switchNotif, switchStorage, switchBattery, switchOverlay;
    private Button btnContinue;
    private Button btnManageAll;
    private Spinner spinnerLanguage;

    private ActivityResultLauncher<String> requestPermissionLauncher;
    private ActivityResultLauncher<Intent> storageLauncher;
    private ActivityResultLauncher<Intent> batteryLauncher;
    private ActivityResultLauncher<Intent> overlayLauncher;
    private ActivityResultLauncher<Intent> notifLauncher;

    // Helper class to hold Locale objects cleanly inside the Spinner
    private static class LocaleItem {
        Locale locale;
        String displayName;

        LocaleItem(Locale locale, String displayName) {
            this.locale = locale;
            // Capitalize the first letter for a cleaner UI
            this.displayName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        TextView welcomeText = findViewById(R.id.setup_welcome_text);
        welcomeText.setText(getString(R.string.setup_welcome, getString(R.string.app_name)));

        // Bind core switches
        switchNotif = findViewById(R.id.switch_perm_notifications);
        switchStorage = findViewById(R.id.switch_perm_storage);
        switchBattery = findViewById(R.id.switch_perm_battery);
        switchOverlay = findViewById(R.id.switch_perm_overlay);

        btnContinue = findViewById(R.id.btn_setup_continue);
        btnManageAll = findViewById(R.id.btn_manage_all);
        spinnerLanguage = findViewById(R.id.spinner_language);

        // Hide notifications switch if Android is below 13 (Tiramisu)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            switchNotif.setVisibility(View.GONE);
        }

        setupLaunchers();
        setupListeners();
        setupLanguageSpinner();
        checkAllPermissions();

        btnContinue.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            // 1. Process and format the selected language
            LocaleItem selectedItem = (LocaleItem) spinnerLanguage.getSelectedItem();
            if (selectedItem != null) {
                Locale loc = selectedItem.locale;

                String minimal = loc.getLanguage(); // e.g., "es"
                String simple = loc.getLanguage() + "-" + loc.getCountry(); // e.g., "es-MX"
                String full = loc.getLanguage() + "_" + loc.getCountry() + ".UTF-8"; // e.g., "es_MX.UTF-8"

                editor.putString("selected_lang_minimal", minimal);
                editor.putString("selected_lang_simple", simple);
                editor.putString("selected_lang_full", full);
            }

            // 2. Save setup completion flag
            editor.putBoolean(getString(R.string.pref_key_setup_complete), true);
            editor.apply();

            // Launch Main Activity and kill Setup
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
    }

    private void setupLanguageSpinner() {
        List<LocaleItem> localeItems = new ArrayList<>();
        Set<String> addedNames = new HashSet<>();
        Locale currentSystemLocale = Locale.getDefault();
        int defaultSelectionIndex = 0;

        // Fetch all system locales
        for (Locale locale : Locale.getAvailableLocales()) {
            // Filter: We only want locales that have both a language and a country to build our 3 formats properly
            if (!locale.getLanguage().isEmpty() && !locale.getCountry().isEmpty()) {
                String name = locale.getDisplayName();

                // Avoid redundant entries
                if (!addedNames.contains(name)) {
                    localeItems.add(new LocaleItem(locale, name));
                    addedNames.add(name);
                }
            }
        }

        // Sort alphabetically by display name
        Collections.sort(localeItems, (a, b) -> a.displayName.compareToIgnoreCase(b.displayName));

        // Find the index of the user's current system language to pre-select it
        for (int i = 0; i < localeItems.size(); i++) {
            if (localeItems.get(i).locale.getLanguage().equals(currentSystemLocale.getLanguage()) &&
                    localeItems.get(i).locale.getCountry().equals(currentSystemLocale.getCountry())) {
                defaultSelectionIndex = i;
                break;
            }
        }

        // Attach to Spinner
        ArrayAdapter<LocaleItem> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, localeItems);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(adapter);
        spinnerLanguage.setSelection(defaultSelectionIndex);
    }

    private void setupLaunchers() {
        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> checkAllPermissions()
        );

        notifLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> checkAllPermissions()
        );

        storageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> checkAllPermissions()
        );

        batteryLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> checkAllPermissions()
        );

        overlayLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> checkAllPermissions()
        );
    }

    private void setupListeners() {
        // 1. Notifications
        switchNotif.setOnClickListener(v -> {
            if (hasNotifPermission()) {
                handleRevokeAttempt(v);
                return;
            }
            if (switchNotif.isChecked()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Send the user directly to the app's notification settings, due targetSdkVersion 28
                    Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    notifLauncher.launch(intent);
                }
            }
            switchNotif.setChecked(false); // Force visual state off until system confirms
        });

        // 2. Storage
        switchStorage.setOnClickListener(v -> {
            if (hasStoragePermission()) {
                handleRevokeAttempt(v);
                return;
            }
            if (switchStorage.isChecked()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.addCategory("android.intent.category.DEFAULT");
                        intent.setData(Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                        storageLauncher.launch(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent();
                        intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        storageLauncher.launch(intent);
                    }
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
            }
            switchStorage.setChecked(false);
        });

        // 3. Battery (Wakelock)
        switchBattery.setOnClickListener(v -> {
            if (hasBatteryPermission()) {
                handleRevokeAttempt(v);
                return;
            }
            if (switchBattery.isChecked()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    batteryLauncher.launch(intent);
                }
            }
            switchBattery.setChecked(false);
        });

        // 4. Overlay
        switchOverlay.setOnClickListener(v -> {
            if (hasOverlayPermission()) {
                handleRevokeAttempt(v);
                return;
            }
            if (switchOverlay.isChecked()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    overlayLauncher.launch(intent);
                }
            }
            switchOverlay.setChecked(false);
        });

        // App Settings shortcut
        btnManageAll.setOnClickListener(v -> openAppSettings());
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAllPermissions(); // Refresh state if user returns from Android Settings
    }

    /**
     * Prevents the user from bypassing the Setup Wizard using the Back button/gesture.
     */
    @Override
    public void onBackPressed() {
        Snackbar.make(
                findViewById(android.R.id.content),
                getString(R.string.setup_back_blocked_msg),
                Snackbar.LENGTH_SHORT
        ).show();
    }

    /**
     * Shows visual feedback (shake) when a user tries to revoke a permission from within the app.
     */
    private void handleRevokeAttempt(View switchView) {
        ((SwitchCompat) switchView).setChecked(true);

        Animation shake = AnimationUtils.loadAnimation(this, R.anim.shake);
        switchView.startAnimation(shake);

        Snackbar.make(findViewById(android.R.id.content), R.string.revoke_permission_warning, Snackbar.LENGTH_LONG)
                .setAction(R.string.settings_label, v -> openAppSettings()).show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    // ==========================================
    // GLOBAL VALIDATIONS
    // ==========================================

    private void checkAllPermissions() {
        boolean notif = hasNotifPermission();
        boolean storage = hasStoragePermission();
        boolean battery = hasBatteryPermission();
        boolean overlay = hasOverlayPermission();

        switchNotif.setChecked(notif);
        switchStorage.setChecked(storage);
        switchBattery.setChecked(battery);
        switchOverlay.setChecked(overlay);

        boolean allGranted = storage && battery && overlay;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            allGranted = allGranted && notif;
        }

        btnContinue.setEnabled(allGranted);
        btnContinue.setBackgroundTintList(ContextCompat.getColorStateList(this,
                allGranted ? R.color.btn_explore_ready : R.color.btn_explore_disabled));
    }

    private boolean hasNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // For legacy target SDKs, NotificationManagerCompat is far more reliable
            // than ContextCompat.checkSelfPermission for checking actual notification status.
            return NotificationManagerCompat.from(this).areNotificationsEnabled();
        }
        return true;
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private boolean hasBatteryPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return true;
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }
}