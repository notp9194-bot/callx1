package com.callx.app.utils;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Forwarding an already-E2E-encrypted image to a DIFFERENT chat partner
 * previously just copied the original message's {@code mediaUrl} (Cloudinary
 * ciphertext link) into the new message, without touching
 * {@code mediaKeyEnc} at all. That's broken two different ways:
 *
 *   1. The new recipient's E2E session with US is a completely different
 *      Double Ratchet than our session with the ORIGINAL partner — even if
 *      we tried to copy {@code mediaKeyEnc} across too, it's ciphertext
 *      wrapped for the wrong person and they could never decrypt it.
 *   2. Even setting that aside: reusing the exact same ciphertext blob and
 *      key for a second, unrelated recipient is exactly the key-reuse
 *      WhatsApp explicitly avoids — every forward gets its own fresh
 *      random key and its own fresh ciphertext upload, same as if you'd
 *      picked the photo from your gallery again from scratch.
 *
 * This class does that properly: decrypt (or read local) plaintext →
 * generate a NEW master key → re-derive fresh per-purpose subkeys → encrypt
 * → re-upload ciphertext to Cloudinary → wrap the new envelope through the
 * NEW partner's own E2E session. The result is indistinguishable from a
 * brand-new send — no trace of the original key or ciphertext is reused.
 *
 * SCOPE (current): single-image forwards. Video/audio/multi_media-group
 * forwards of E2E media still use the old direct-link copy today — see
 * ChatActivity's forward-consumption block. Extending this class's approach
 * to those types is straightforward (same shape) but not yet wired in.
 */
public final class MediaForwardReEncryptor {

    private MediaForwardReEncryptor() {}

    public interface Callback {
        /** @param newThumbnailUrl null when the thumb was small enough to
         *  travel inline in {@code newMediaKeyEnc} instead (see
         *  MediaE2ECrypto#shouldInlineThumb) — same as a fresh send. */
        void onSuccess(String newMediaUrl, String newThumbnailUrl, String newMediaKeyEnc);
        void onError(String reason);
    }

    /**
     * @param ctx                 app/activity context
     * @param originalMediaUrl    the ORIGINAL message's mediaUrl (ciphertext
     *                            URL, or null/unused if a local plaintext
     *                            copy is being used instead)
     * @param originalThumbUrl    the ORIGINAL message's thumbnailUrl, or
     *                            null if it travelled inline or there's none
     * @param originalMediaKeyEnc the ORIGINAL message's encrypted key
     *                            envelope
     * @param originalWasSentByMe true if the CURRENT device originally sent
     *                            this message (needed because our own
     *                            outgoing ratchet ciphertext can't be
     *                            decrypted in reverse — see
     *                            MediaE2ECrypto#decryptEnvelopeForMessage)
     * @param originalLocalPath   m.mediaLocalPath from the original message
     *                            — used ONLY when originalWasSentByMe, as
     *                            the plaintext source (still on this device
     *                            from when it was originally sent)
     * @param originalChatPartnerUid the OTHER person in the chat the
     *                            original message lived in (needed to
     *                            resolve the E2E session for decrypting
     *                            originalMediaKeyEnc when we're the
     *                            receiver, i.e. !originalWasSentByMe)
     * @param originalMessageId   the original message's id/messageId (ratchet
     *                            per-message decrypt-cache key)
     * @param newPartnerUid       who we're forwarding TO — a fresh envelope
     *                            is wrapped through THIS person's E2E session
     */
    public static void forwardImage(Context ctx, String originalMediaUrl, String originalThumbUrl,
                                     String originalMediaKeyEnc, boolean originalWasSentByMe,
                                     String originalLocalPath, String originalChatPartnerUid,
                                     String originalMessageId, String newPartnerUid, Callback cb) {
        new Thread(() -> {
            try {
                // ── Step 1: obtain PLAINTEXT thumb + full bytes ────────────
                File plainFull;
                File plainThumb = null; // may stay null — we can re-derive a thumb from the full image if needed

                if (originalWasSentByMe && originalLocalPath != null && !originalLocalPath.isEmpty()
                        && LocalMediaAvailability.isAvailable(ctx, originalLocalPath)) {
                    // We're the original sender and still have the file on
                    // this device — no decrypt needed at all.
                    plainFull = copyUriToTemp(ctx, Uri.parse(originalLocalPath), "fwd_full_src");
                } else if (originalMediaKeyEnc != null && !originalMediaKeyEnc.isEmpty()) {
                    // We're the receiver (or the sender who no longer has the
                    // local copy) — decrypt the envelope using the session
                    // with whoever the ORIGINAL chat partner was, then
                    // download+decrypt the actual ciphertext.
                    MediaE2ECrypto.KeyEnvelope env = MediaE2ECrypto.decryptEnvelopeForMessage(
                            ctx, originalMediaKeyEnc, originalChatPartnerUid, originalMessageId);
                    if (env == null) {
                        post(cb, null, null, null, "Could not decrypt original media key");
                        return;
                    }
                    plainFull = decryptToTemp(ctx, originalMediaUrl, env.fullKey(), env.fullDigest, "fwd_full_dl");
                    if (plainFull == null) {
                        post(cb, null, null, null, "Could not download/decrypt original media");
                        return;
                    }
                    if (env.inlineThumbCipher != null) {
                        byte[] inline = env.decryptInlineThumb();
                        if (inline != null) plainThumb = bytesToTemp(ctx, inline, "fwd_thumb_inline");
                    } else if (originalThumbUrl != null && !originalThumbUrl.isEmpty()) {
                        plainThumb = decryptToTemp(ctx, originalThumbUrl, env.thumbKey(), env.thumbDigest, "fwd_thumb_dl");
                    }
                } else if (originalMediaUrl != null && !originalMediaUrl.isEmpty()) {
                    // Not E2E at all originally — nothing to rotate, just
                    // reuse the plaintext CDN link exactly like before.
                    post(cb, originalMediaUrl, originalThumbUrl, null, null);
                    return;
                } else {
                    post(cb, null, null, null, "Original media not available");
                    return;
                }

                if (plainThumb == null) {
                    // No original thumb recovered (rare) — derive a small
                    // one from the full image so the forwarded message still
                    // gets a preview, same as any fresh send would generate.
                    plainThumb = deriveThumbFrom(plainFull);
                }

                // ── Step 2: re-encrypt with a FRESH master key for newPartnerUid ──
                byte[] masterKey = MediaE2ECrypto.generateKey();
                byte[] fullSubKey  = MediaE2ECrypto.deriveKey(masterKey, MediaE2ECrypto.PURPOSE_FULL);
                byte[] thumbSubKey = MediaE2ECrypto.deriveKey(masterKey, MediaE2ECrypto.PURPOSE_THUMB);

                File fullEnc = new File(plainFull.getParentFile(), plainFull.getName() + ".enc");
                MediaE2ECrypto.encryptFile(plainFull, fullEnc, fullSubKey);
                byte[] fullDigest = MediaE2ECrypto.sha256File(fullEnc);

                File thumbEnc = null;
                byte[] inlineThumbCipher = null;
                boolean thumbIsInline = false;
                if (plainThumb != null && plainThumb.exists()) {
                    thumbEnc = new File(plainThumb.getParentFile(), plainThumb.getName() + ".enc");
                    MediaE2ECrypto.encryptFile(plainThumb, thumbEnc, thumbSubKey);
                    thumbIsInline = MediaE2ECrypto.shouldInlineThumb(thumbEnc);
                    if (thumbIsInline) {
                        inlineThumbCipher = readAll(thumbEnc);
                    }
                }

                String envelopeJson = MediaE2ECrypto.buildKeyEnvelopeJson(
                        masterKey, null, fullDigest, inlineThumbCipher, thumbIsInline);
                String newMediaKeyEnc = E2EEncryptionManager.getInstance(ctx).encrypt(envelopeJson, newPartnerUid);
                if (newMediaKeyEnc == null) {
                    cleanup(plainFull, plainThumb, fullEnc, thumbEnc);
                    post(cb, null, null, null, "No secure session with recipient yet");
                    return;
                }

                // ── Step 3: re-upload the NEW ciphertext ───────────────────
                final File finalPlainFull = plainFull, finalPlainThumb = plainThumb;
                final File finalFullEnc = fullEnc, finalThumbEnc = thumbEnc;
                final boolean finalThumbInline = thumbIsInline;
                final String finalNewMediaKeyEnc = newMediaKeyEnc;

                uploadOne(ctx, fullEnc, "callx/e2e_image", "raw", (fullUrl, fullErr) -> {
                    if (fullUrl == null) {
                        cleanup(finalPlainFull, finalPlainThumb, finalFullEnc, finalThumbEnc);
                        post(cb, null, null, null, fullErr != null ? fullErr : "Re-upload failed");
                        return;
                    }
                    if (finalThumbInline || finalThumbEnc == null) {
                        cleanup(finalPlainFull, finalPlainThumb, finalFullEnc, finalThumbEnc);
                        post(cb, fullUrl, null, finalNewMediaKeyEnc, null);
                        return;
                    }
                    uploadOne(ctx, finalThumbEnc, "callx/e2e_thumb", "raw", (thumbUrl, thumbErr) -> {
                        cleanup(finalPlainFull, finalPlainThumb, finalFullEnc, finalThumbEnc);
                        post(cb, fullUrl, thumbUrl /* may be null on thumb-upload failure — full still sends */,
                                finalNewMediaKeyEnc, null);
                    });
                });
            } catch (Exception e) {
                post(cb, null, null, null, "Forward failed: " + e.getMessage());
            }
        }).start();
    }

    // ── internal helpers ────────────────────────────────────────────────

    private interface UploadDone { void done(String url, String error); }

    private static void uploadOne(Context ctx, File f, String folder, String resourceType, UploadDone done) {
        CloudinaryUploader.upload(ctx, Uri.fromFile(f), folder, resourceType, new CloudinaryUploader.UploadCallback() {
            @Override public void onSuccess(CloudinaryUploader.Result r) { done.done(r.secureUrl, null); }
            @Override public void onError(String err) { done.done(null, err); }
        });
    }

    private static File decryptToTemp(Context ctx, String url, byte[] key, byte[] digest, String prefix) {
        if (url == null || url.isEmpty() || key == null) return null;
        final File[] out = new File[1];
        final Object lock = new Object();
        final boolean[] done = {false};
        MediaCache.get(ctx, url, key, digest, new MediaCache.Callback() {
            @Override public void onReady(File file) {
                synchronized (lock) {
                    try {
                        File dst = File.createTempFile(prefix, ".tmp", ctx.getCacheDir());
                        copyFile(file, dst);
                        out[0] = dst;
                    } catch (IOException ignored) {}
                    done[0] = true;
                    lock.notifyAll();
                }
            }
            @Override public void onError(String reason) {
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
        });
        synchronized (lock) {
            while (!done[0]) {
                try { lock.wait(30_000); break; } catch (InterruptedException ignored) { break; }
            }
        }
        return out[0];
    }

    private static File copyUriToTemp(Context ctx, Uri uri, String prefix) throws IOException {
        File dst = File.createTempFile(prefix, ".tmp", ctx.getCacheDir());
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new IOException("Cannot open local media");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return dst;
    }

    private static File bytesToTemp(Context ctx, byte[] bytes, String prefix) throws IOException {
        File dst = File.createTempFile(prefix, ".tmp", ctx.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(dst)) { out.write(bytes); }
        return dst;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private static File deriveThumbFrom(File fullPlain) {
        try {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = 4;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(fullPlain.getAbsolutePath(), opts);
            if (bmp == null) return null;
            File out = new File(fullPlain.getParentFile(), fullPlain.getName() + "_thumb.jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, fos);
            }
            bmp.recycle();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static void cleanup(File... files) {
        for (File f : files) {
            if (f != null && f.exists()) f.delete();
        }
    }

    private static void post(Callback cb, String fullUrl, String thumbUrl, String mediaKeyEnc, String error) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> {
            if (cb == null) return;
            if (error != null) cb.onError(error);
            else cb.onSuccess(fullUrl, thumbUrl, mediaKeyEnc);
        });
    }
}
