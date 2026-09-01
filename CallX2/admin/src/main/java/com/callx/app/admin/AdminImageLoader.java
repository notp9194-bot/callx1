package com.callx.app.admin;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny avatar loader for the standalone admin app. It replaces Glide without
 * changing the request row UI or adding another transitive dependency.
 */
final class AdminImageLoader {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final int TIMEOUT_MS = 10_000;

    private AdminImageLoader() { }

    static void load(ImageView view, String imageUrl) {
        final String url = imageUrl == null ? "" : imageUrl.trim();
        view.setTag(url);
        view.setImageResource(android.R.drawable.ic_menu_myplaces);
        if (url.isEmpty()) return;

        EXECUTOR.execute(() -> {
            Bitmap bitmap = download(url);
            if (bitmap == null) return;
            view.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    private static Bitmap download(String imageUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(imageUrl).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setDoInput(true);
            try (InputStream input = connection.getInputStream()) {
                return BitmapFactory.decodeStream(input);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}