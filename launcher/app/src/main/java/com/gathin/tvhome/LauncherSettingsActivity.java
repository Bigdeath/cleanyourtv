package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.view.ViewGroup;
import android.view.View;
import android.net.Uri;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private TextView wallpaperChoice;
    private TextView liveUrlChoice;
    private TextView liveIntervalChoice;
    private TextView liveFadeChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
        }
        addLiveWallpaperSettings();
        addRemoteButtonSettings();
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove("live_wallpaper_url").remove("live_wallpaper_interval")
                    .remove("live_wallpaper_interval_seconds")
                    .remove("live_wallpaper_disabled").remove("wallpaper_fade_seconds").commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void addLiveWallpaperSettings() {
        View anchor = findViewById(R.id.wallpaperChoice);
        LinearLayout host = (LinearLayout) anchor.getParent();
        int insert = host.indexOfChild(anchor);

        // Reuse the launcher settings selector, including its DPAD focus highlight.
        liveUrlChoice = settingsRow();
        liveUrlChoice.setOnClickListener(v -> editLiveUrl());
        host.addView(liveUrlChoice, insert++);

        liveIntervalChoice = settingsRow();
        liveIntervalChoice.setOnClickListener(v -> selectLiveInterval());
        host.addView(liveIntervalChoice, insert++);

        liveFadeChoice = settingsRow();
        liveFadeChoice.setOnClickListener(v -> selectFadeDuration());
        host.addView(liveFadeChoice, insert++);

        TextView backgroundMode = settingsRow();
        backgroundMode.setText("Hintergrund anzeigen / ausschalten");
        backgroundMode.setOnClickListener(v -> selectBackgroundMode());
        host.addView(backgroundMode, insert);
    }

    private void addRemoteButtonSettings() {
        View anchor = findViewById(R.id.tvSettingsChoice);
        LinearLayout host = (LinearLayout) anchor.getParent();
        int insert = host.indexOfChild(anchor);
        TextView heading = new TextView(this);
        heading.setText("Fernbedienungstasten");
        heading.setTextColor(0xffffffff);
        heading.setTextSize(20);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(28);
        heading.setLayoutParams(hp);
        host.addView(heading, insert++);
        // Visual order: YouTube, Netflix, Star. Keep persisted key indices unchanged.
        for (int keyIndex : new int[]{0, 2, 1}) {
            final int index = keyIndex;
            TextView row = settingsRow();
            row.setText(remoteButtonLabel(index));
            row.setOnClickListener(v -> RemoteButtonSettings.pick(this, index,
                    () -> row.setText(remoteButtonLabel(index))));
            host.addView(row, insert++);
        }
    }

    private String remoteButtonLabel(int index) {
        return new String[]{"YouTube", "Stern", "Netflix"}[index] + ": "
                + RemoteButtonSettings.summary(this, index);
    }

    private TextView settingsRow() {
        // The original SettingsRow style includes spacing, focus and pressed states.
        TextView row = new TextView(this, null, 0, R.style.SettingsRow);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        params.topMargin = dp(12);
        row.setLayoutParams(params);
        return row;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void selectBackgroundMode() {
        String[] options = {"Live-Hintergrund (URL)", "Statisches Ersatzbild", "Kein Hintergrund"};
        new AlertDialog.Builder(this)
                .setTitle("Hintergrund")
                .setItems(options, (dialog, selected) -> {
                    SharedPreferences.Editor edit = choices.edit();
                    if (selected == 0) {
                        edit.remove(Config.WALLPAPER_CHOICE_KEY);
                        edit.remove("live_wallpaper_disabled");
                    } else if (selected == 1) {
                        edit.remove(Config.WALLPAPER_CHOICE_KEY);
                        edit.putBoolean("live_wallpaper_disabled", true);
                    } else {
                        edit.putString(Config.WALLPAPER_CHOICE_KEY, "none");
                        edit.putBoolean("live_wallpaper_disabled", true);
                    }
                    edit.apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .show();
    }

    private void editLiveUrl() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(Config.load(this).liveWallpaperUrl);
        field.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Hintergrund-URL")
                .setMessage("Direkte HTTP(S)-Bildadresse. Leer lassen zum Deaktivieren.")
                .setView(field)
                .setPositiveButton("Speichern", (dialog, which) -> {
                    String value = field.getText().toString().trim();
                    if (!value.isEmpty()) {
                        Uri uri = Uri.parse(value);
                        String scheme = uri.getScheme();
                        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                                || uri.getHost() == null) {
                            Toast.makeText(this, "Bitte eine gültige HTTP(S)-URL eingeben", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                    choices.edit().putString("live_wallpaper_url", value).apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNeutralButton("Theme-Standard", (dialog, which) -> {
                    choices.edit().remove("live_wallpaper_url").apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void selectLiveInterval() {
        String[] labels = {
                "10 Sekunden", "15 Sekunden", "20 Sekunden",
                "30 Sekunden", "45 Sekunden", "1 Minute",
                "2 Minuten", "5 Minuten", "10 Minuten", "30 Minuten"
        };
        int[] seconds = {10, 15, 20, 30, 45, 60, 120, 300, 600, 1800};
        new AlertDialog.Builder(this).setTitle("Hintergrundwechsel")
                .setItems(labels, (dialog, which) -> {
                    choices.edit()
                            .remove("live_wallpaper_interval")
                            .putInt("live_wallpaper_interval_seconds", seconds[which])
                            .apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNeutralButton("Theme-Standard", (dialog, which) -> {
                    choices.edit().remove("live_wallpaper_interval")
                            .remove("live_wallpaper_interval_seconds").apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .show();
    }

    private void selectFadeDuration() {
        String[] labels = {
                "500 ms",
                "1 Sekunde", "2 Sekunden", "3 Sekunden",
                "4 Sekunden", "5 Sekunden", "6 Sekunden",
                "7 Sekunden", "8 Sekunden", "9 Sekunden",
                "10 Sekunden"
        };
        new AlertDialog.Builder(this)
                .setTitle("Hintergrund-Überblendung")
                .setItems(labels, (dialog, which) -> {
                    choices.edit()
                            .putInt("wallpaper_fade_seconds", which)
                            .apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNeutralButton("Theme-Standard", (dialog, which) -> {
                    choices.edit().remove("wallpaper_fade_seconds").apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        Config live = Config.load(this);
        liveUrlChoice.setText("Hintergrund-URL: " + (live.liveWallpaperUrl.isEmpty() ? "Aus" : live.liveWallpaperUrl));
        int intervalSeconds = choices.getInt("live_wallpaper_interval_seconds",
                live.wallpaperRotationMinutes * 60);
        String intervalLabel = intervalSeconds < 60
                ? intervalSeconds + " Sekunden"
                : (intervalSeconds / 60) + (intervalSeconds == 60 ? " Minute" : " Minuten");
        liveIntervalChoice.setText("Bildwechsel: " + intervalLabel);
        int fadeSeconds = Math.max(1, Math.min(10,
                choices.getInt("wallpaper_fade_seconds", 2)));
        liveFadeChoice.setText("Bildübergang: " + fadeSeconds
                + (fadeSeconds == 1 ? " Sekunde" : " Sekunden"));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains("live_wallpaper_url") || choices.contains("live_wallpaper_interval")
                || choices.contains("live_wallpaper_interval_seconds")
                || choices.contains("live_wallpaper_disabled")
                || choices.contains("wallpaper_fade_seconds");
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
