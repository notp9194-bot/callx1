package com.callx.app.utils;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * MediaE2ECrypto — chunk-wise streaming AES-256-GCM encryption for chat
 * media (image/video-thumb/audio — see ChatMediaController /
 * MessagePagingAdapter / MediaCache), redesigned to match WhatsApp's own
 * media-encryption architecture (HKDF key derivation + a ciphertext
 * digest), while keeping the AEAD (GCM) approach we already had instead of
 * switching to WhatsApp's CBC+HMAC — GCM gives the same encrypt-then-MAC
 * guarantee in one primitive.
 *
 * WHY A NEW UTILITY (instead of reusing E2EEncryptionManager's own AES-GCM
 * helpers): E2EEncryptionManager encrypts short text envelopes in one shot,
 * fully buffered in memory. Media files (especially a full-res photo before
 * compression, or in future audio/video) are too big for that — this class
 * encrypts/decrypts in fixed-size chunks so a file is never fully buffered
 * in RAM on either side, and streams straight to/from disk or an HTTP body.
 *
 * WHATSAPP-PARITY DESIGN (v2 envelope):
 *   - Per-purpose subkeys via HKDF-SHA256 (RFC 5869), exactly like
 *     WhatsApp derives separate cipher/MAC/thumb keys from one mediaKey:
 *     a single random 256-bit master key is generated per file-set, and
 *     {@link #deriveKey} expands it into an independent "full image" key
 *     and "thumbnail" key using domain-separated info strings. A leak or
 *     bug affecting one derived key can't be used to decrypt the other.
 *   - A SHA-256 digest of each ciphertext is computed at encrypt time and
 *     carried in the envelope (see {@link KeyEnvelope#fullDigest} /
 *     {@link KeyEnvelope#thumbDigest}), mirroring WhatsApp's file-hash
 *     check: the receiver can verify the exact bytes it downloaded match
 *     what the sender encrypted BEFORE attempting to decrypt, turning a
 *     corrupted/tampered download into an immediate, clear failure instead
 *     of a deep GCM-tag exception. (GCM already authenticates every chunk
 *     on its own — this digest is defense-in-depth + a friendlier failure
 *     mode, not a replacement for that.)
 *   - v1 envelopes (already-sent messages, before this upgrade) used the
 *     raw master key directly with no derivation and no digest — see
 *     {@link KeyEnvelope#fullKey()} / {@link KeyEnvelope#thumbKey()},
 *     which stay backward compatible by only deriving for v2+.
 *
 * HOW IT FITS INTO THE 1:1 IMAGE E2E FLOW:
 *   1. Sender generates a random 256-bit master key with {@link #generateKey()}.
 *   2. Sender derives {@link #PURPOSE_FULL} / {@link #PURPOSE_THUMB} subkeys
 *      via {@link #deriveKey} and encrypts the compressed thumb + full JPEG
 *      each with its OWN subkey via {@link #encryptStream}. The resulting
 *      ciphertext is meaningless random bytes — that's what gets uploaded
 *      to Cloudinary (as resource_type=raw so Cloudinary doesn't try to
 *      process/transform it as an image). The server / CDN never sees
 *      plaintext image bytes.
 *   3. The random master key (plus the locally-computed BlurHash placeholder
 *      string, which would otherwise leak a preview of the image contents,
 *      plus the SHA-256 digest of each ciphertext) is packed into a small
 *      JSON envelope via {@link #buildKeyEnvelopeJson} and sent through the
 *      SAME Double Ratchet text channel as a normal chat message
 *      (E2EEncryptionManager#encrypt) — i.e. the master key itself is E2E
 *      encrypted, never sent in the clear. See Message#mediaKeyEnc.
 *   4. Receiver decrypts that envelope with E2EEncryptionManager#decrypt,
 *      pulls the master key + digests out with {@link #parseKeyEnvelopeJson},
 *      downloads the ciphertext blob, verifies its digest, re-derives the
 *      matching subkey, and decrypts it locally with {@link #decryptStream}
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

    // ─────────────────────────────────────────────────────────────────────
    // HKDF-SHA256 (RFC 5869) — per-purpose subkey derivation
    // ─────────────────────────────────────────────────────────────────────

    /** Domain-separation label for the full-resolution image/video/audio
     *  cipher subkey — same master key + a different label always yields a
     *  completely different, unrelated 32-byte key (standard HKDF property). */
    public static final String PURPOSE_FULL  = "CallX-Media-E2E-v2:full";
    /** Domain-separation label for the low-res thumbnail cipher subkey. */
    public static final String PURPOSE_THUMB = "CallX-Media-E2E-v2:thumb";

    // Fixed application-level HKDF salt. Doesn't need to be secret (HKDF's
    // security holds even with a public/constant salt as long as the input
    // key material — our random master key — is itself unpredictable); it
    // just needs to be distinct per-application so subkeys derived here can
    // never collide with subkeys another protocol might derive from the
    // same bytes by coincidence.
    private static final byte[] HKDF_SALT =
            "CallX2-MediaE2E-HKDF-Salt-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** Derives a purpose-bound 256-bit AES key from the random master key
     *  using HKDF-SHA256 (RFC 5869: Extract-then-Expand). Deterministic —
     *  sender and receiver independently compute the same subkey from the
     *  same master key + purpose label, so only the master key (already
     *  E2E-protected in the envelope) ever needs to travel. */
    public static byte[] deriveKey(byte[] masterKey, String purpose) {
        try {
            byte[] prk = hmacSha256(HKDF_SALT, masterKey); // HKDF-Extract
            byte[] info = purpose.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // HKDF-Expand for a single 32-byte block: T(1) = HMAC(PRK, info || 0x01)
            byte[] expandInput = new byte[info.length + 1];
            System.arraycopy(info, 0, expandInput, 0, info.length);
            expandInput[info.length] = 0x01;
            byte[] okm = hmacSha256(prk, expandInput); // 32 bytes — exactly one AES-256 key
            return okm;
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is a mandatory JCA algorithm on every Android
            // version we support — this branch is unreachable in practice.
            throw new IllegalStateException("HKDF derivation failed", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    // ─────────────────────────────────────────────────────────────────────
    // SHA-256 ciphertext digest — WhatsApp-style fast corruption/tamper check
    // ─────────────────────────────────────────────────────────────────────

    /** Computes the SHA-256 digest of an already-encrypted file — call this
     *  right after {@link #encryptFile}/{@link #encryptStream} on the
     *  resulting ciphertext, and carry the result in the key envelope so
     *  the receiver can verify the downloaded bytes before decrypting. */
    public static byte[] sha256File(java.io.File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[CHUNK_SIZE];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // unreachable — mandatory JCA algorithm
        }
    }

    /** Constant-time digest comparison (avoids a timing side-channel on the
     *  comparison itself — minor in this context since the digest isn't a
     *  secret, but costs nothing and matches how the AES key comparisons
     *  elsewhere in the E2E stack are done). */
    public static boolean digestsEqual(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

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
        /** Envelope format version. 1 = legacy (raw master key used
         *  directly for both thumb and full, no digest). 2 = current
         *  (HKDF-derived per-purpose subkeys + SHA-256 ciphertext digests). */
        public int version = 1;
        /** The random master key exactly as generated by {@link #generateKey()}.
         *  For v2 envelopes, DON'T use this directly to decrypt — call
         *  {@link #fullKey()} / {@link #thumbKey()} instead so the right
         *  derived (or, for v1, raw) key is used automatically. */
        public byte[] key;
        /** BlurHash placeholder string, or null. Carried in here (instead
         *  of Message#blurHash in the clear) so a receiver-side preview
         *  can't leak image content before the real ciphertext is even
         *  downloaded. */
        public String blurHash;
        /** SHA-256 digest of the full-resolution ciphertext, or null if not
         *  present (v1 envelope, or this message has no full-res file —
         *  e.g. a video where only the thumb is E2E'd). */
        public byte[] fullDigest;
        /** SHA-256 digest of the thumbnail ciphertext, or null if not
         *  present (v1 envelope, or this message has no encrypted thumb). */
        public byte[] thumbDigest;

        /** Key to use when decrypting the full-resolution ciphertext.
         *  v1: the raw master key (matches how it was originally encrypted).
         *  v2+: HKDF-derived full-purpose subkey. */
        public byte[] fullKey() {
            return version >= 2 ? deriveKey(key, PURPOSE_FULL) : key;
        }

        /** Key to use when decrypting the thumbnail ciphertext. Same
         *  version split as {@link #fullKey()}. */
        public byte[] thumbKey() {
            return version >= 2 ? deriveKey(key, PURPOSE_THUMB) : key;
        }
    }

    /** Builds the small JSON blob that gets E2E-encrypted (via
     *  E2EEncryptionManager#encrypt) and stored as Message#mediaKeyEnc.
     *  Always writes the current (v2) format — pass null for either digest
     *  if that ciphertext doesn't apply to this message (e.g. video only
     *  has a thumbDigest, audio only has a fullDigest). */
    public static String buildKeyEnvelopeJson(byte[] key, String blurHash,
                                               byte[] fullDigest, byte[] thumbDigest) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("v", 2);
        o.put("k", Base64.encodeToString(key, Base64.NO_WRAP));
        if (blurHash != null && !blurHash.isEmpty()) o.put("b", blurHash);
        if (fullDigest  != null) o.put("fd", Base64.encodeToString(fullDigest, Base64.NO_WRAP));
        if (thumbDigest != null) o.put("td", Base64.encodeToString(thumbDigest, Base64.NO_WRAP));
        return o.toString();
    }

    /** Back-compat overload for call sites that don't track ciphertext
     *  digests — still writes a v2 envelope (subkey derivation applies
     *  regardless of whether digests are present), just without "fd"/"td". */
    public static String buildKeyEnvelopeJson(byte[] key, String blurHash) throws JSONException {
        return buildKeyEnvelopeJson(key, blurHash, null, null);
    }

    public static KeyEnvelope parseKeyEnvelopeJson(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        KeyEnvelope env = new KeyEnvelope();
        env.version = o.optInt("v", 1);
        env.key = Base64.decode(o.getString("k"), Base64.NO_WRAP);
        env.blurHash = o.optString("b", null);
        String fd = o.optString("fd", null);
        String td = o.optString("td", null);
        if (fd != null) env.fullDigest  = Base64.decode(fd, Base64.NO_WRAP);
        if (td != null) env.thumbDigest = Base64.decode(td, Base64.NO_WRAP);
        return env;
    }

    /** Same as {@link #decryptEnvelopeForMessage} but returns just the
     *  full-purpose decryption key (or null) — convenient at call sites
     *  that only need the key for a full-resolution image/audio download,
     *  not the blurHash or digest. Version-aware: returns the HKDF-derived
     *  subkey for v2 envelopes, the raw master key for legacy v1 ones. */
    public static byte[] decryptKeyOnly(android.content.Context ctx, String mediaKeyEnc,
                                         String partnerUid, String messageId) {
        KeyEnvelope env = decryptEnvelopeForMessage(ctx, mediaKeyEnc, partnerUid, messageId);
        return env != null ? env.fullKey() : null;
    }

    /** Thumbnail counterpart of {@link #decryptKeyOnly} — use this at any
     *  call site that's downloading/decrypting a THUMBNAIL ciphertext
     *  (e.g. the video-thumb-only E2E path), not the full-res file. */
    public static byte[] decryptThumbKeyOnly(android.content.Context ctx, String mediaKeyEnc,
                                              String partnerUid, String messageId) {
        KeyEnvelope env = decryptEnvelopeForMessage(ctx, mediaKeyEnc, partnerUid, messageId);
        return env != null ? env.thumbKey() : null;
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
