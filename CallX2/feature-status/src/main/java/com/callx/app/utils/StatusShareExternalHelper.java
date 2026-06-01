package com.callx.app.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import com.callx.app.models.StatusItem;
import java.util.concurrent.Executors;
import java.io.*;
import java.net.*;

/** StatusShareExternalHelper v26 — Share status to WhatsApp/Instagram/etc. */
public final class StatusShareExternalHelper {
    private StatusShareExternalHelper() {}

    public static void shareText(Context ctx, StatusItem item) {
        if (item == null || ctx == null) return;
        String text = item.text != null ? item.text : item.caption != null ? item.caption : "";
        if (item.linkUrl != null) text += "\n" + item.linkUrl;
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        ctx.startActivity(Intent.createChooser(i, "Share status via…"));
    }

    public static void shareMedia(Context ctx, StatusItem item) {
        if (item == null || ctx == null) return;
        if (item.mediaUrl == null) { shareText(ctx, item); return; }
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                URL url = new URL(item.mediaUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.connect();
                byte[] data = conn.getInputStream().readAllBytes();
                conn.disconnect();
                String ext  = "video".equals(item.type) ? ".mp4" : ".jpg";
                String mime = "video".equals(item.type) ? "video/mp4" : "image/jpeg";
                File f = new File(ctx.getCacheDir(), "share_status" + ext);
                try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); }
                Uri uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
                String caption = item.caption != null ? item.caption : item.text != null ? item.text : "";
                Intent intent = new Intent(Intent.ACTION_SEND)
                        .setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_TEXT, caption)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (ctx instanceof android.app.Activity) {
                    ((android.app.Activity) ctx).runOnUiThread(() ->
                        ctx.startActivity(Intent.createChooser(intent, "Share status via…")));
                }
            } catch (Exception e) {
                if (ctx instanceof android.app.Activity) {
                    ((android.app.Activity) ctx).runOnUiThread(() -> shareText(ctx, item));
                }
            }
        });
    }

    public static void shareToWhatsApp(Context ctx, StatusItem item) {
        if (item == null || ctx == null) return;
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .setPackage("com.whatsapp")
                .putExtra(Intent.EXTRA_TEXT, item.text != null ? item.text : item.caption != null ? item.caption : "");
        try { ctx.startActivity(i); } catch (Exception e) { shareText(ctx, item); }
    }
}
