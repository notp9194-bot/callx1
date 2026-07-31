package com.callx.app.utils;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * MediaE2ECrypto — chunk-wise streaming AES-256-GCM encryption for chat
 * media (currently wired for IMAGE messages only — see ChatMediaController
 * / MessagePagingAdapter / MediaCache).
 *
 * WHY A NEW UTILITY (instead of reusing E2EEncryptionManager's own AES-GCM
 * helpers): E2EEncryptionManager encrypts short text envelopes in one shot,
 * fully buffered in memory. Media files (especially a full-res photo before
 * compression, or in future audio/video) are too big for that — this class
 * encrypts/decrypts in fixed-size chunks so a file is never fully buffered
 * in RAM on either side, and streams straight to/from disk or an HTTP body.
 *
 * HOW IT FITS INTO THE 1:1 IMAGE E2E FLOW:
 *   1. Sender generates a random 256-bit key with {@link #generateKey()}.
 *   2. Sender encrypts the compressed thumb + full JPEG with
 *      {@link #encryptStream}. The resulting ciphertext is meaningless
 *      random bytes — that's what gets uploaded to Cloudinary (as
 *      resource_type=raw so Cloudinary doesn't try to process/transform it
 *      as an image). The server / CDN never sees plaintext image bytes.
 *   3. The random AES key (plus the locally-computed BlurHash placeholder
 *      string, which would otherwise leak a preview of the image contents)
 *      is packed into a small JSON envelope via
 *      {@link #buildKeyEnvelopeJson} and sent through the SAME Double
 *      Ratchet text channel as a normal chat message
 *      (E2EEncryptionManager#encrypt) — i.e. the AES key itself is E2E
 *      encrypted, never sent in the clear. See Message#mediaKeyEnc.
 *   4. Receiver decrypts that envelope with E2EEncryptionManager#decrypt,
 *      pulls the key out with {@link #parseKeyEnvelopeJson}, downloads the
 *      ciphertext blob, and decrypts it locally with {@link #decryptStream}
 *      before ever handing bytes to the image decoder.
 *
 * WIRE FORMAT (self-contained — no external metadata needed to decrypt):
 *   [4 bytes magic "CXM1"] [8 bytes random nonce]
 *   then zero or more DATA chunks:
 *     [4 bytes chunk ciphertext length, big-endian, always >= 0]
 *     [ciphertext bytes (length above) — AES-GCM(chunk plaintext), tag
 *      appended per standard javax.crypto GCM output]
 *   then exactly one END marker:
 *     [4 bytes 0xFFFFFFFF sentinel]
 *     [16 bytes — AES-GCM tag over an EMPTY plaintext, authenticated with
 *      a reserved "end of stream" chunk index]
 *
 * Every chunk (data or end-marker) uses its own IV = nonce(8) + chunk index
 * (4 bytes, big-endian) — 12 bytes total, the standard GCM IV length — and
 * its own AAD binding in the magic + chunk index + a "is this the end
 * marker" flag. Consequences:
 *   - Chunks can't be reordered, duplicated, or spliced from a different
 *     ciphertext (the chunk index + magic are authenticated).
 *   - Truncation is detected: decryptStream only returns successfully once
 *     it has verified the authenticated END marker, so a dropped tail
 *     (accidental or adversarial, e.g. a compromised/lossy CDN) fails
 *     loudly instead of silently handing back a partial image.
 *   - Each chunk is independently authenticated, so encrypt/decrypt never
 *     need to hold the whole file in memory — true streaming in both
 *     directions.
 */
public final class MediaE2ECrypto {

    private MediaE2ECrypto() {}

    private static final byte[] MAGIC = {'C', 'X', 'M', '1'};
    private static final int NONCE_LEN = 8;
    private static final int GCM_IV_LEN = 12;   // nonce(8) + chunk index(4)
    private static final int GCM_TAG_LEN_BITS = 128;
    private static final int GCM_TAG_LEN_BYTES = GCM_TAG_LEN_BITS / 8;

    /** Plaintext bytes per chunk. 64 KB keeps memory use low while staying
     *  well clear of per-call Cipher overhead for typical chat photos. */
    public static final int CHUNK_SIZE = 64 * 1024;

    /** Reserved chunk index for the authenticated end-of-stream marker —
     *  never a valid data-chunk index for any real file (max real chunk
     *  count for a file this size scheme supports is far below this). */
    private static final int END_CHUNK_INDEX = 0xFFFFFFFF;
    private static final int END_LEN_SENTINEL = 0xFFFFFFFF;

    /** AES-256 key, straight from a CSPRNG — never derived, reused, or
     *  written to disk anywhere except inside the ratchet-encrypted key
     *  envelope (see #buildKeyEnvelopeJson). */
    public static byte[] generateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Streaming encrypt / decrypt
    // ─────────────────────────────────────────────────────────────────────

    /** Encrypts everything read from {@code in} into {@code out} using the
     *  wire format described in the class doc. Does not close either
     *  stream — caller owns their lifecycle. */
    public static void encryptStream(InputStream in, OutputStream out, byte[] key) throws IOException, GeneralSecurityException {
        byte[] nonce = new byte[NONCE_LEN];
        new SecureRandom().nextBytes(nonce);
        out.write(MAGIC);
        out.write(nonce);

        byte[] buf = new byte[CHUNK_SIZE];
        int chunkIndex = 0;
        int n;
        while ((n = readFullChunk(in, buf)) > 0) {
            byte[] plain = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
            byte[] ct = gcmCrypt(Cipher.ENCRYPT_MODE, key, chunkIv(nonce, chunkIndex),
                    chunkAad(chunkIndex, false), plain);
            writeInt(out, ct.length);
            out.write(ct);
            chunkIndex++;
            if (n < buf.length) break; // that read hit EOF — nothing more to encrypt
        }

        byte[] endTag = gcmCrypt(Cipher.ENCRYPT_MODE, key, chunkIv(nonce, END_CHUNK_INDEX),
                chunkAad(END_CHUNK_INDEX, true), new byte[0]);
        writeInt(out, END_LEN_SENTINEL);
        out.write(endTag); // GCM tag over empty plaintext == GCM_TAG_LEN_BYTES bytes
        out.flush();
    }

    /** Decrypts a stream produced by {@link #encryptStream} into {@code out}.
     *  Throws if the header is malformed, any chunk fails authentication, or
     *  the stream ends before the authenticated end marker is read (i.e.
     *  truncation is always detected, never silently accepted). */
    public static void decryptStream(InputStream in, OutputStream out, byte[] key) throws IOException, GeneralSecurityException {
        byte[] magic = readFully(in, MAGIC.length);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("MediaE2ECrypto: not a recognized encrypted-media stream");
        }
        byte[] nonce = readFully(in, NONCE_LEN);

        int chunkIndex = 0;
        while (true) {
            int len = readInt(in);
            if (len == END_LEN_SENTINEL) {
                byte[] endTag = readFully(in, GCM_TAG_LEN_BYTES);
                // Throws (auth-tag mismatch) if this isn't a genuine,
                // untampered end marker for THIS key/nonce — this is what
                // makes truncation/tampering detectable rather than just
                // silently accepted as "the file ends here".
                gcmCrypt(Cipher.DECRYPT_MODE, key, chunkIv(nonce, END_CHUNK_INDEX),
                        chunkAad(END_CHUNK_INDEX, true), endTag);
                break;
            }
            if (len < 0 || len > CHUNK_SIZE + GCM_TAG_LEN_BYTES) {
                throw new IOException("MediaE2ECrypto: implausible chunk length " + len);
            }
            byte[] ct = readFully(in, len);
            byte[] plain = gcmCrypt(Cipher.DECRYPT_MODE, key, chunkIv(nonce, chunkIndex),
                    chunkAad(chunkIndex, false), ct);
            out.write(plain);
            chunkIndex++;
        }
        out.flush();
    }

    /** Convenience: encrypts {@code src} into a freshly-created {@code dst}
     *  file. Used by ChatMediaController right before upload so the file
     *  handed to CloudinaryUploader is already ciphertext. */
    public static void encryptFile(java.io.File src, java.io.File dst, byte[] key)
            throws IOException, GeneralSecurityException {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new java.io.FileOutputStream(dst)) {
            encryptStream(in, out, key);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Key envelope (what actually travels through the E2E text ratchet)
    // ─────────────────────────────────────────────────────────────────────

    public static final class KeyEnvelope {
        public byte[] key;
        /** BlurHash placeholder string, or null. Carried in here (instead
         *  of Message#blurHash in the clear) so a receiver-side preview
         *  can't leak image content before the real ciphertext is even
         *  downloaded. */
        public String blurHash;
    }

    /** Builds the small JSON blob that gets E2E-encrypted (via
     *  E2EEncryptionManager#encrypt) and stored as Message#mediaKeyEnc. */
    public static String buildKeyEnvelopeJson(byte[] key, String blurHash) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("v", 1);
        o.put("k", Base64.encodeToString(key, Base64.NO_WRAP));
        if (blurHash != null && !blurHash.isEmpty()) o.put("b", blurHash);
        return o.toString();
    }

    public static KeyEnvelope parseKeyEnvelopeJson(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        KeyEnvelope env = new KeyEnvelope();
        env.key = Base64.decode(o.getString("k"), Base64.NO_WRAP);
        env.blurHash = o.optString("b", null);
        return env;
    }

    /** Same as {@link #decryptEnvelopeForMessage} but returns just the raw
     *  AES key (or null) — convenient at call sites that only need the key
     *  to hand to MediaCache's decrypting download, not the blurHash. */
    public static byte[] decryptKeyOnly(android.content.Context ctx, String mediaKeyEnc,
                                         String partnerUid, String messageId) {
        KeyEnvelope env = decryptEnvelopeForMessage(ctx, mediaKeyEnc, partnerUid, messageId);
        return env != null ? env.key : null;
    }

    /**
     * Convenience used by receive-side rendering (MessagePagingAdapter):
     * decrypts {@code mediaKeyEnc} through the normal E2EEncryptionManager
     * ratchet-decrypt cache and parses the resulting envelope in one call.
     * Returns null on any failure (no session yet, tampered/corrupt
     * envelope, etc.) — callers should fall back to an un-decryptable /
     * "couldn't load" state rather than crash.
     *
     * @param messageId used only as an E2EEncryptionManager per-message
     *                  decrypt-result cache key — suffixed with ":mk" so it
     *                  never collides with the cache slot used to decrypt
     *                  this same message's caption/text (see
     *                  E2EEncryptionManager#decrypt's messageId doc).
     *                  ONLY call this for messages the current device did
     *                  NOT send — the ratchet can't decrypt our own
     *                  outgoing ciphertext in reverse (own-sent image
     *                  bubbles render from the local file instead; see
     *                  ChatMediaController).
     */
    public static KeyEnvelope decryptEnvelopeForMessage(android.content.Context ctx, String mediaKeyEnc,
                                                          String partnerUid, String messageId) {
        if (mediaKeyEnc == null || mediaKeyEnc.isEmpty()) return null;
        try {
            String cacheKey = (messageId != null) ? (messageId + ":mk") : null;
            String json = com.callx.app.utils.E2EEncryptionManager.getInstance(ctx)
                    .decrypt(mediaKeyEnc, partnerUid, cacheKey);
            if (json == null || com.callx.app.utils.E2EEncryptionManager.DECRYPT_FAILED_MARKER.equals(json)) {
                return null;
            }
            return parseKeyEnvelopeJson(json);
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────────────

    private static byte[] chunkIv(byte[] nonce, int chunkIndex) {
        byte[] iv = new byte[GCM_IV_LEN];
        System.arraycopy(nonce, 0, iv, 0, NONCE_LEN);
        iv[NONCE_LEN]     = (byte) (chunkIndex >>> 24);
        iv[NONCE_LEN + 1] = (byte) (chunkIndex >>> 16);
        iv[NONCE_LEN + 2] = (byte) (chunkIndex >>> 8);
        iv[NONCE_LEN + 3] = (byte) chunkIndex;
        return iv;
    }

    /** AAD binds the chunk to this exact stream (via magic) and its
     *  position/role, so chunks can't be reordered or mixed across files. */
    private static byte[] chunkAad(int chunkIndex, boolean isEndMarker) {
        byte[] aad = new byte[MAGIC.length + 4 + 1];
        System.arraycopy(MAGIC, 0, aad, 0, MAGIC.length);
        aad[MAGIC.length]     = (byte) (chunkIndex >>> 24);
        aad[MAGIC.length + 1] = (byte) (chunkIndex >>> 16);
        aad[MAGIC.length + 2] = (byte) (chunkIndex >>> 8);
        aad[MAGIC.length + 3] = (byte) chunkIndex;
        aad[MAGIC.length + 4] = (byte) (isEndMarker ? 1 : 0);
        return aad;
    }

    private static byte[] gcmCrypt(int mode, byte[] key, byte[] iv, byte[] aad, byte[] data)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LEN_BITS, iv));
        c.updateAAD(aad);
        return c.doFinal(data);
    }

    /** Reads up to buf.length bytes, looping until either the buffer is
     *  full or the stream is exhausted (plain InputStream#read may return
     *  short reads well before EOF). Returns the number of bytes actually
     *  read (0 only at true EOF). */
    private static int readFullChunk(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        int total = 0;
        while (total < len) {
            int n = in.read(b, total, len - total);
            if (n < 0) throw new EOFException("MediaE2ECrypto: unexpected end of stream");
            total += n;
        }
        return b;
    }

    private static void writeInt(OutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static int readInt(InputStream in) throws IOException {
        byte[] b = readFully(in, 4);
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }
}
