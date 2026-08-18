package com.callx.app.utils;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * WhatsApp-style "local-first" media rendering helper.
 *
 * Once an image/video is sent, we keep the device's original local
 * file/content Uri (`mediaLocalPath`) around in Room instead of dropping it
 * the moment the upload finishes. That lets the SENT bubble — and the
 * full-screen viewer — render straight from the original local file for as
 * long as it's still on the phone (full quality, no re-download, no
 * Cloudinary compression loss), and only fall back to the remote mediaUrl
 * once the user deletes it from their device (gallery cleanup, storage
 * clear, app uninstall/reinstall, etc).
 *
 * `mediaLocalPath` is stored as a Uri string and can be either:
 *   - a `content://...` Uri (picked from gallery/picker), or
 *   - a `file://...` Uri (app-internal cache/compressed copy).
 * Both need different existence checks, so this class centralizes that
 * logic instead of duplicating try/catch blocks at every call site.
 */
public final class LocalMediaAvailability {

    private LocalMediaAvailability() {}

    /**
     * @return true iff pathOrUri is non-empty AND currently resolves to a
     *         readable file/content Uri on this device. Any exception
     *         (revoked permission, unmounted SD card, deleted file, bad
     *         Uri) is treated as "not available" — caller should fall back
     *         to the remote URL rather than crash or show a broken bubble.
     */
    public static boolean isAvailable(Context ctx, String pathOrUri) {
        if (ctx == null || pathOrUri == null || pathOrUri.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(pathOrUri);
            String scheme = uri.getScheme();

            if (scheme == null) {
                // Bare filesystem path (legacy rows, no scheme) — check directly.
                return new File(pathOrUri).exists();
            }

            if ("file".equals(scheme)) {
                String path = uri.getPath();
                return path != null && new File(path).exists();
            }

            if ("content".equals(scheme)) {
                // Cheapest reliable existence probe for a content:// Uri —
                // if the provider can't hand back a descriptor (deleted,
                // revoked permission, provider gone) this throws, which the
                // catch below treats as "not available".
                try (ParcelFileDescriptor pfd =
                             ctx.getContentResolver().openFileDescriptor(uri, "r")) {
                    return pfd != null;
                }
            }

            // Unknown scheme — be conservative and don't trust it.
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
