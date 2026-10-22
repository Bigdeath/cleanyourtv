package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Maps remote buttons without accessibility filtering, using the local ADB shell-owned watcher. */
final class RemoteButtonSettings {
    private static final String TAG = "RemoteButtons";
    private static final String PREFS = "remote_button_mapping";
    private static final String[] KEYS = {"youtube", "star", "netflix"};
    private static final String[] DEFAULTS = {
            "org.smarttube.stable/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity",
            "package:com.plexapp.android", "default"};

    static String value(Context ctx, int index) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEYS[index], DEFAULTS[index]);
    }

    static String summary(Context ctx, int index) {
        String v = value(ctx, index);
        if ("default".equals(v)) return index == 2 ? "Netflix (Originalfunktion)" : "Standard";
        if ("none".equals(v)) return "Keine zusätzliche Aktion";
        String pkg = v.startsWith("package:") ? v.substring(8) : v.split("/", 2)[0];
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) { return pkg; }
    }

    static void pick(Activity activity, int index, Runnable refresh) {
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        labels.add("Standard"); values.add(DEFAULTS[index]);
        labels.add("Keine zusätzliche Aktion"); values.add("none");
        if (index == 2) {
            labels.add("Netflix (Originalfunktion)"); values.add("default");
        }
        PackageManager pm = activity.getPackageManager();
        Set<String> seen = new HashSet<>();
        List<ResolveInfo> apps = new ArrayList<>();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        apps.addAll(pm.queryIntentActivities(intent, 0));
        intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        apps.addAll(pm.queryIntentActivities(intent, 0));
        apps.sort(Comparator.comparing(a -> a.loadLabel(pm).toString(), String.CASE_INSENSITIVE_ORDER));
        for (ResolveInfo app : apps) {
            if (app.activityInfo == null || app.activityInfo.packageName == null) continue;
            String pkg = app.activityInfo.packageName;
            if (!seen.add(pkg)) continue;
            labels.add(app.loadLabel(pm).toString());
            // Store the actual exported launcher activity, not only its package.
            // "am start -p package" does not reliably resolve every TV app (Spotify etc.).
            String component = pkg + "/" + app.activityInfo.name;
            values.add(component);
        }
        new AlertDialog.Builder(activity)
                .setTitle(new String[]{"YouTube-Taste", "Sterntaste", "Netflix-Taste"}[index])
                .setItems(labels.toArray(new String[0]), (dialog, pos) -> {
                    String choice = values.get(pos);
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(KEYS[index], choice).apply();
                    refresh.run();
                    Toast.makeText(activity, index == 2 && !"default".equals(choice)
                            ? "Netflix kann zusätzlich systemseitig starten"
                            : "Belegung wird übernommen", Toast.LENGTH_LONG).show();
                    sync(activity.getApplicationContext());
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private static String config(Context ctx) {
        StringBuilder b = new StringBuilder();
        for (int i=0; i<KEYS.length; i++) b.append(KEYS[i]).append('=').append(value(ctx,i)).append('\n');
        return b.toString();
    }

    static void sync(Context ctx) {
        String cfg = config(ctx);
        new Thread(() -> {
            try {
                String encoded = Base64.encodeToString(cfg.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                // Only base64 characters enter the shell command, preventing shell injection.
                new LocalAdb(ctx).runShell("printf %s " + encoded
                        + " | base64 -d > /data/local/tmp/tv-buttons.conf.tmp"
                        + " && mv /data/local/tmp/tv-buttons.conf.tmp /data/local/tmp/tv-buttons.conf");
                Log.i(TAG, "Button mappings synchronized");
            } catch (Exception e) { Log.w(TAG, "Unable to synchronize mappings", e); }
        }, "RemoteButtonSync").start();
    }
}
