package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private ImageView fadeImage;
    private final int fadeDurationMs;
    private boolean transitioning;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final String liveUrl;
    private long lastLiveFetch;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        files = new ArrayList<>(config.wallpapers);
        int liveSeconds = context.getSharedPreferences(
                Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getInt("live_wallpaper_interval_seconds", -1);
        interval = liveSeconds >= 10 && liveSeconds <= 1800
                ? liveSeconds * 1000L
                : config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        liveUrl = config.liveWallpaperUrl;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        SharedPreferences settings = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        int fadeSeconds = settings.getInt("wallpaper_fade_seconds", 2);
        fadeDurationMs = fadeSeconds == 0
                ? 500
                : Math.max(1, Math.min(10, fadeSeconds)) * 1000;
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && (!files.isEmpty() || !liveUrl.isEmpty())) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fadeImage = new ImageView(context);
            fadeImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            fadeImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            fadeImage.setAlpha(0f);
            fadeImage.setVisibility(View.GONE);
            root.addView(fadeImage, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (liveUrl.isEmpty()) showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        File first = WallpaperRotation.find(files, previous);
        if (first == null) first = WallpaperRotation.pick(files, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = WallpaperRotation.pick(files, previous, order, random);
        }
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    void close() {
        active = false;
        closed = true;
        if (fadeImage != null) {
            fadeImage.animate().cancel();
            fadeImage.setImageDrawable(null);
        }
        transitioning = false;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || transitioning) return;
        if (!liveUrl.isEmpty()) {
            updateLive();
            return;
        }
        if (files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File selected = WallpaperRotation.find(files, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(files, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                showBitmap(bitmap);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Fetch directly from Immich only while Home is visible. No disk cache or polling service. */
    private void updateLive() {
        long now = SystemClock.elapsedRealtime();
        if (lastLiveFetch != 0 && now - lastLiveFetch < Math.max(10_000L, interval)) return;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                URL url = new URL(liveUrl);
                if (!("http".equals(url.getProtocol()) || "https".equals(url.getProtocol())))
                    throw new IllegalArgumentException("Unsupported wallpaper scheme");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(8000);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp");
                if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP " + connection.getResponseCode());
                int length = connection.getContentLength();
                if (length > 8 * 1024 * 1024) throw new java.io.IOException("Image too large");
                try (InputStream stream = connection.getInputStream();
                     java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = stream.read(buf)) != -1) {
                        if (bytes.size() + n > 8 * 1024 * 1024) throw new java.io.IOException("Image too large");
                        bytes.write(buf, 0, n);
                    }
                    byte[] data = bytes.toByteArray();
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, options);
                    if (options.outWidth <= options.outHeight || options.outHeight <= 0)
                        throw new java.io.IOException("Not a landscape photo");
                    int sample = WallpaperSize.sampleSize(options.outWidth, options.outHeight, width, height);
                    if (!WallpaperSize.accepted(options.outWidth / sample, options.outHeight / sample))
                        throw new java.io.IOException("Image dimensions unsupported");
                    options.inJustDecodeBounds = false;
                    options.inSampleSize = sample;
                    options.inPreferredConfig = bitmapConfig;
                    options.inMutable = dimColor != 0;
                    bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                    if (bitmap != null && dimColor != 0) new Canvas(bitmap).drawColor(dimColor);
                }
            } catch (Exception | OutOfMemoryError ex) {
                Log.w("TvHome.Wallpaper", "Live wallpaper fetch failed: " + ex.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
            Bitmap result = bitmap;
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (result != null) result.recycle();
                    return;
                }
                if (result != null) {
                    showBitmap(result);
                } else {
                    lastLiveFetch = SystemClock.elapsedRealtime();
                }
            });
        });
    }

    /** Fade in the decoded replacement above the current image, keeping shade above both. */
    private void showBitmap(Bitmap next) {
        if (fadeImage == null || image.getDrawable() == null) {
            show(next);
            lastLiveFetch = SystemClock.elapsedRealtime();
            return;
        }
        transitioning = true;
        fadeImage.animate().cancel();
        fadeImage.setImageBitmap(next);
        fadeImage.setAlpha(0f);
        fadeImage.setVisibility(View.VISIBLE);
        fadeImage.animate().alpha(1f).setDuration(fadeDurationMs)
                .withEndAction(() -> {
                    if (closed) return;
                    show(next);
                    fadeImage.setVisibility(View.GONE);
                    fadeImage.setImageDrawable(null);
                    fadeImage.setAlpha(0f);
                    transitioning = false;
                    lastLiveFetch = SystemClock.elapsedRealtime();
                }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Puts a picture on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Bitmap bitmap) {
        Bitmap old = current;
        current = bitmap;
        image.setImageBitmap(bitmap);
        if (old != null && old != bitmap) image.postDelayed(old::recycle, 500);
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
