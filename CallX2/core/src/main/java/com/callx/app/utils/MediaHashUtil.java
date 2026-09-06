package com.callx.app.utils;

import android.content.Context;
import android.net.Uri;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Streaming SHA-256 for content-hash media dedup (see MediaDedupManager).
 *
 * The byte[]-overload is the one actually used by CloudinaryUploader —
 * it hashes bytes already sitting in memory (the exact bytes about to be
 * uploaded, post-compression) so no extra file read is needed. The Uri
 * overload is provided for callers that want a hash WITHOUT first
 * loading the whole file into memory (streams straight off disk).
 */
public final class MediaHashUtil {
    private MediaHashUtil() {}

    /** Returns lowercase hex SHA-256 of the given bytes, or null on failure. */
    public static String sha256(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns lowercase hex SHA-256 of the given URI's bytes (streamed, not fully loaded), or null on failure. */
    public static String sha256(Context ctx, Uri uri) {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
            return toHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
