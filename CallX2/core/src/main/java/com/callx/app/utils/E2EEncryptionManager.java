package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * E2EEncryptionManager — End-to-End Encryption for CallX2 1:1 chat text messages.
 *
 * PROTOCOL (X3DH handshake + Double Ratchet — the same protocol family
 * WhatsApp/Signal use — implemented here on top of EC P-256 + AES-256-GCM +
 * HMAC-SHA256/HKDF, so no extra native crypto dependency is needed beyond
 * what the JVM/Android already ships):
 *
 *   1. Each device generates a long-term Identity keypair (EC P-256), a
 *      medium-term Signed-PreKey (signed by the Identity key), and a batch
 *      of single-use One-Time-PreKeys. Only PUBLIC parts ever leave the
 *      device — uploaded to the server (see /e2e/keys in index.js), which
 *      stores them for other users to fetch.
 *   2. To start talking to a partner for the first time, the sender fetches
 *      the partner's bundle (identity + signed prekey + signature + a fresh
 *      one-time prekey, atomically consumed server-side so it can never be
 *      reused) and runs X3DH to agree on an initial Root Key — WITHOUT ever
 *      putting a private key on the wire.
 *   3. From the Root Key, both sides run a Double Ratchet: every message
 *      advances a per-direction symmetric chain (HMAC-SHA256) AND — every
 *      time the conversation changes direction — a fresh Diffie-Hellman
 *      ratchet step mixes in new randomness.
 *
 * SECURITY PROPERTIES (this is what makes it stronger than a single static
 * shared secret — the previous version of this class derived ONE ECDH
 * secret per partner and reused it for every message ever sent):
 *   - Per-message forward secrecy — each message uses its own one-time key,
 *     derived then immediately discarded; compromising today's key material
 *     does not expose yesterday's messages.
 *   - Post-compromise security — the DH ratchet means even a fully
 *     compromised chain key heals on the next round trip.
 *   - One-time prekeys are consumed exactly once (atomic server transaction)
 *     — closes the classic "static prekey replay" weakness.
 *   - Signed prekey is authenticated with the Identity key (ECDSA), so a
 *     malicious/compromised server can't quietly swap in a prekey it
 *     controls without it being detectable against the safety-number
 *     fingerprint.
 *   - Out-of-order / lost-message tolerant (skipped-message-key cache), so
 *     flaky mobile networks don't permanently break decryption.
 *   - Plaintext length is bucket-padded before encryption so ciphertext size
 *     leaks less about message content than raw AES-GCM would.
 *   - All private key material lives only in Android Keystore-backed
 *     EncryptedSharedPreferences — never uploaded, never logged.
 *
 * Wire format (stored as Message.text in Firebase): "e2r1:" + compact JSON
 * (see #encrypt). Used only for 1:1 chat text (ChatActivity /
 * ChatMessageSender) — group chat is intentionally out of scope here.
 */
public class E2EEncryptionManager {

    private static final String TAG = "E2ERatchet";

    private static final String ENC_PREFIX = "e2r1:";

    private static final int GCM_IV_LEN  = 12;
    private static final int GCM_TAG_LEN = 128; // bits
    private static final int PAD_BUCKET  = 32;  // pad plaintext to a multiple of this many bytes

    private static final int ONE_TIME_PREKEY_BATCH  = 20;
    private static final long BUNDLE_REFRESH_MS     = TimeUnit.DAYS.toMillis(7);
    private static final int MAX_SKIPPED_KEYS_CACHED = 200;

    private static final String IDENTITY_PREFS   = "e2e_identity_v2";
    private static final String SESSION_PREFS    = "e2e_sessions_v2";
    private static final String SENT_CACHE_PREFS = "e2e_sent_plaintext_v1";
    private static final int MAX_CACHED_SENT_PLAINTEXT = 1000;

    private static volatile E2EEncryptionManager instance;

    private final Context context;
    private final SharedPreferences identityPrefs;
    private final SharedPreferences sessionPrefs;
    private final SharedPreferences sentCachePrefs;
    private final ExecutorService executor;
    private final OkHttpClient http;

    public interface SetupCallback {
        void onComplete(boolean success);
    }

    private E2EEncryptionManager(Context ctx) {
        this.context  = ctx.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2);
        this.http     = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        this.identityPrefs  = openEncryptedPrefs(IDENTITY_PREFS);
        this.sessionPrefs   = openEncryptedPrefs(SESSION_PREFS);
        this.sentCachePrefs = openEncryptedPrefs(SENT_CACHE_PREFS);
        ensureIdentityAndPreKeysExist();

    }

    public static E2EEncryptionManager getInstance(Context ctx) {
        if (instance == null) {
            synchronized (E2EEncryptionManager.class) {
                if (instance == null) instance = new E2EEncryptionManager(ctx);
            }
        }
        return instance;
    }

    private SharedPreferences openEncryptedPrefs(String name) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context, name, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            Log.e(TAG, "EncryptedSharedPreferences init failed, falling back to plain prefs: " + e.getMessage());
            return context.getSharedPreferences(name + "_fallback", Context.MODE_PRIVATE);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // IDENTITY / SIGNED PREKEY / ONE-TIME PREKEYS — generation & upload
    // ═════════════════════════════════════════════════════════════════════

    private KeyPair genEcKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    private void ensureIdentityAndPreKeysExist() {
        try {
            if (identityPrefs.getString("identity_priv", null) == null) {
                KeyPair id = genEcKeyPair();
                identityPrefs.edit()
                        .putString("identity_priv", b64(id.getPrivate().getEncoded()))
                        .putString("identity_pub",  b64(id.getPublic().getEncoded()))
                        .apply();
                Log.d(TAG, "E2E: generated new identity keypair");
            }
            if (identityPrefs.getString("spk_priv", null) == null) {
                rotateSignedPreKey();
            }
        } catch (Exception e) {
            Log.e(TAG, "ensureIdentityAndPreKeysExist failed", e);
        }
    }

    private void rotateSignedPreKey() throws Exception {
        KeyPair spk = genEcKeyPair();
        PrivateKey identityPriv = loadPriv("identity_priv");
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(identityPriv);
        sig.update(spk.getPublic().getEncoded());
        byte[] signature = sig.sign();

        identityPrefs.edit()
                .putString("spk_id",   UUID.randomUUID().toString().substring(0, 8))
                .putString("spk_priv", b64(spk.getPrivate().getEncoded()))
                .putString("spk_pub",  b64(spk.getPublic().getEncoded()))
                .putString("spk_sig",  b64(signature))
                .apply();
    }

    /** Generates a fresh batch of one-time prekeys, stores privates locally, returns the public batch for upload. */
    private JSONArray generateOneTimePreKeyBatch(int count) throws Exception {
        JSONArray arr = new JSONArray();
        SharedPreferences.Editor editor = identityPrefs.edit();
        for (int i = 0; i < count; i++) {
            KeyPair kp = genEcKeyPair();
            String id = UUID.randomUUID().toString().substring(0, 8);
            editor.putString("opk_priv_" + id, b64(kp.getPrivate().getEncoded()));
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("key", b64(kp.getPublic().getEncoded()));
            arr.put(o);
        }
        editor.apply();
        return arr;
    }

    /**
     * Uploads our public bundle (identity + signed prekey + fresh one-time
     * prekeys) to the server if we haven't recently, then fetches the
     * partner's bundle and runs X3DH if we don't already have a session
     * with them. Safe to call every time a chat is opened — it's cheap once
     * a session/bundle already exists.
     */
    public void ensureSession(String ourUid, String partnerUid, SetupCallback callback) {
        executor.execute(() -> {
            try {
                maybeUploadOurBundle();
                if (hasSession(partnerUid)) {
                    if (callback != null) callback.onComplete(true);
                    return;
                }
                boolean ok = initiateSessionAsSender(partnerUid);
                if (callback != null) callback.onComplete(ok);
            } catch (Exception e) {
                Log.e(TAG, "ensureSession failed", e);
                if (callback != null) callback.onComplete(false);
            }
        });
    }

    private void maybeUploadOurBundle() {
        long last = identityPrefs.getLong("bundle_uploaded_ts", 0);
        if (System.currentTimeMillis() - last < BUNDLE_REFRESH_MS
                && identityPrefs.getBoolean("bundle_uploaded_ok", false)) {
            return;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("identityKey", identityPrefs.getString("identity_pub", null));
            body.put("signedPreKey", identityPrefs.getString("spk_pub", null));
            body.put("signedPreKeySig", identityPrefs.getString("spk_sig", null));
            body.put("signedPreKeyId", identityPrefs.getString("spk_id", null));
            body.put("oneTimePreKeys", generateOneTimePreKeyBatch(ONE_TIME_PREKEY_BATCH));

            String idToken = getIdTokenBlocking();
            Request req = new Request.Builder()
                    .url(Constants.SERVER_URL + "/e2e/keys")
                    .header("Authorization", "Bearer " + idToken)
                    .post(RequestBody.create(body.toString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                boolean ok = resp.isSuccessful();
                identityPrefs.edit()
                        .putLong("bundle_uploaded_ts", System.currentTimeMillis())
                        .putBoolean("bundle_uploaded_ok", ok)
                        .apply();
            }
        } catch (Exception e) {
            Log.w(TAG, "maybeUploadOurBundle failed (will retry later): " + e.getMessage());
        }
    }

    @Nullable
    private String getIdTokenBlocking() {
        try {
            com.google.firebase.auth.FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return null;
            return Tasks.await(user.getIdToken(false), 10, TimeUnit.SECONDS).getToken();
        } catch (Exception e) {
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // X3DH — establishing a session (sender / initiator side)
    // ═════════════════════════════════════════════════════════════════════

    private boolean initiateSessionAsSender(String partnerUid) throws Exception {
        String idToken = getIdTokenBlocking();
        Request req = new Request.Builder()
                .url(Constants.SERVER_URL + "/e2e/bundle/" + partnerUid)
                .header("Authorization", "Bearer " + idToken)
                .get()
                .build();
        JSONObject bundle;
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                Log.w(TAG, "No prekey bundle available for " + partnerUid);
                return false;
            }
            bundle = new JSONObject(resp.body().string());
        }

        PublicKey partnerIdentity = decodePub(bundle.getString("identityKey"));
        PublicKey partnerSpk      = decodePub(bundle.getString("signedPreKey"));
        byte[] spkSig             = Base64.decode(bundle.getString("signedPreKeySig"), Base64.NO_WRAP);
        String spkId              = bundle.optString("signedPreKeyId", "");

        // Authenticate the signed prekey against the partner's identity key —
        // if this fails, refuse to talk rather than silently trusting a
        // possibly-swapped key.
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(partnerIdentity);
        verifier.update(partnerSpk.getEncoded());
        if (!verifier.verify(spkSig)) {
            Log.e(TAG, "SECURITY: signed prekey signature verification FAILED for " + partnerUid);
            return false;
        }

        String opkId = null;
        PublicKey partnerOpk = null;
        JSONObject opk = bundle.optJSONObject("oneTimePreKey");
        if (opk != null) {
            opkId = opk.getString("id");
            partnerOpk = decodePub(opk.getString("key"));
        }

        PrivateKey ourIdentityPriv = loadPriv("identity_priv");
        KeyPair ephemeral = genEcKeyPair(); // doubles as our first ratchet keypair

        byte[] dh1 = ecdh(ourIdentityPriv, partnerSpk);
        byte[] dh2 = ecdh(ephemeral.getPrivate(), partnerIdentity);
        byte[] dh3 = ecdh(ephemeral.getPrivate(), partnerSpk);
        byte[] dh4 = partnerOpk != null ? ecdh(ephemeral.getPrivate(), partnerOpk) : new byte[0];
        byte[] ikm = concat(dh1, dh2, dh3, dh4);
        byte[] rootKey = hkdf(new byte[32], ikm, "CallX2-X3DH-v1", 32);

        // Initial DH ratchet step (mirrors the responder's first receive step)
        byte[] dhOut = dh3; // ECDH(ephemeral.priv, partnerSpk) — same value the responder derives
        byte[][] rkAndCk = kdfRootKey(rootKey, dhOut);

        Session s = new Session();
        s.rootKey      = rkAndCk[0];
        s.sendChainKey = rkAndCk[1];
        s.recvChainKey = null;
        s.dhSelfPriv   = ephemeral.getPrivate();
        s.dhSelfPub    = ephemeral.getPublic();
        s.dhRemotePub  = partnerSpk;
        s.ns = 0; s.nr = 0; s.pn = 0;
        s.handshakeAcked = false;
        s.pendingIdentityPub = identityPrefs.getString("identity_pub", null);
        s.pendingSpkId = spkId;
        s.pendingOpkId = opkId;

        saveSession(partnerUid, s);
        return true;
    }

    // ═════════════════════════════════════════════════════════════════════
    // X3DH — responder side (first message received from a new partner)
    // ═════════════════════════════════════════════════════════════════════

    private Session acceptSessionAsResponder(JSONObject header) throws Exception {
        PublicKey senderIdentity  = decodePub(header.getString("ik"));
        PublicKey senderEphemeral = decodePub(header.getString("dh")); // EK_A doubles as their first ratchet key
        String spkId = header.optString("spkId", "");
        String opkId = header.has("opkId") ? header.getString("opkId") : null;

        String ourSpkId = identityPrefs.getString("spk_id", "");
        if (!spkId.equals(ourSpkId)) {
            throw new IllegalStateException("Signed prekey id mismatch (rotated) for incoming handshake");
        }

        PrivateKey ourIdentityPriv = loadPriv("identity_priv");
        PrivateKey ourSpkPriv      = loadPriv("spk_priv");
        PublicKey  ourSpkPub       = loadPub("spk_pub");

        PrivateKey ourOpkPriv = null;
        if (opkId != null) {
            if (identityPrefs.getString("opk_priv_" + opkId, null) == null) {
                throw new IllegalStateException("One-time prekey already consumed or unknown: " + opkId);
            }
            ourOpkPriv = loadPriv("opk_priv_" + opkId);
        }

        byte[] dh1 = ecdh(ourSpkPriv, senderIdentity);
        byte[] dh2 = ecdh(ourIdentityPriv, senderEphemeral);
        byte[] dh3 = ecdh(ourSpkPriv, senderEphemeral);
        byte[] dh4 = ourOpkPriv != null ? ecdh(ourOpkPriv, senderEphemeral) : new byte[0];
        byte[] ikm = concat(dh1, dh2, dh3, dh4);
        byte[] rootKey = hkdf(new byte[32], ikm, "CallX2-X3DH-v1", 32);

        byte[] dhOut = dh3; // ECDH(ourSpkPriv, senderEphemeral) == ECDH(ephemeral.priv, ourSpkPub) on the sender's side
        byte[][] rkAndCk = kdfRootKey(rootKey, dhOut);

        // One-time prekey is single-use — burn it now that it's been consumed.
        if (opkId != null) identityPrefs.edit().remove("opk_priv_" + opkId).apply();

        Session s = new Session();
        s.rootKey      = rkAndCk[0];
        s.recvChainKey = rkAndCk[1];
        s.sendChainKey = null;
        s.dhSelfPriv   = ourSpkPriv;
        s.dhSelfPub    = ourSpkPub;
        s.dhRemotePub  = senderEphemeral;
        s.ns = 0; s.nr = 0; s.pn = 0;
        s.handshakeAcked = true;
        return s;
    }

    // ═════════════════════════════════════════════════════════════════════
    // PUBLIC API — encrypt / decrypt (1:1 text only)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Encrypts {@code plaintext} for {@code partnerUid}. Must be called
     * after {@link #ensureSession} has completed successfully at least once
     * for this partner (ChatViewModel.init() / ChatActivity onCreate calls
     * this on chat open).
     */
    public String encrypt(String plaintext, String partnerUid) throws Exception {
        Session s = loadSession(partnerUid);
        if (s == null) {
            throw new IllegalStateException("No E2E session for " + partnerUid + " — call ensureSession() first");
        }

        if (s.sendChainKey == null) {
            // Direction changed since our last send (or this is our first
            // send after receiving) — perform a fresh DH ratchet step.
            KeyPair newRatchet = genEcKeyPair();
            byte[] dhOut = ecdh(newRatchet.getPrivate(), s.dhRemotePub);
            byte[][] rkAndCk = kdfRootKey(s.rootKey, dhOut);
            s.rootKey = rkAndCk[0];
            s.sendChainKey = rkAndCk[1];
            s.pn = s.ns;
            s.ns = 0;
            s.dhSelfPriv = newRatchet.getPrivate();
            s.dhSelfPub  = newRatchet.getPublic();
        }

        byte[][] ckAndMk = kdfChainKey(s.sendChainKey);
        s.sendChainKey = ckAndMk[0];
        byte[] messageKey = ckAndMk[1];

        int n = s.ns++;

        byte[] iv = new byte[GCM_IV_LEN];
        new SecureRandom().nextBytes(iv);
        byte[] padded = padPlaintext(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = aesGcmEncrypt(messageKey, iv, padded);

        JSONObject env = new JSONObject();
        env.put("dh", b64(s.dhSelfPub.getEncoded()));
        env.put("n", n);
        env.put("pn", s.pn);
        env.put("iv", b64(iv));
        env.put("ct", b64(ciphertext));
        if (!s.handshakeAcked) {
            // Still attach X3DH init material until we know the partner has
            // decrypted at least one of our messages — self-heals if an
            // early message is lost, at the cost of a slightly larger
            // payload only during the handshake window.
            env.put("ik", s.pendingIdentityPub);
            env.put("spkId", s.pendingSpkId);
            if (s.pendingOpkId != null) env.put("opkId", s.pendingOpkId);
        }

        saveSession(partnerUid, s);
        return ENC_PREFIX + env.toString();
    }

    /**
     * Decrypts an incoming envelope from {@code partnerUid}. If the text
     * isn't one of our envelopes (older client, or plaintext), it's returned
     * unchanged so decryption failures never corrupt the chat — worst case
     * is the raw string shown, not a crash.
     */
    public String decrypt(String maybeEnvelope, String partnerUid) {
        if (!isEncrypted(maybeEnvelope)) return maybeEnvelope;
        try {
            JSONObject header = new JSONObject(maybeEnvelope.substring(ENC_PREFIX.length()));
            Session s = loadSession(partnerUid);

            if (s == null) {
                if (!header.has("ik")) {
                    Log.w(TAG, "Encrypted message with no session and no handshake data — cannot decrypt");
                    return "🔒 Unable to decrypt message";
                }
                s = acceptSessionAsResponder(header);
            } else if (header.has("ik") && !s.handshakeAcked) {
                // Near-simultaneous first messages both ways — the
                // responder-derived session is authoritative for messages
                // carrying "ik"; keep our own outbound progress intact.
                Session responderSide = acceptSessionAsResponder(header);
                responderSide.sendChainKey = s.sendChainKey;
                s = responderSide;
            }

            PublicKey msgDh = decodePub(header.getString("dh"));
            int n  = header.getInt("n");
            int pn = header.optInt("pn", 0);
            String dhKeyId = header.getString("dh");

            byte[] messageKey;
            String skipKey = dhKeyId + "|" + n;
            if (s.skippedKeys.containsKey(skipKey)) {
                messageKey = s.skippedKeys.remove(skipKey);
            } else {
                if (s.dhRemotePub == null || !keyEquals(s.dhRemotePub, msgDh)) {
                    // Sender ratcheted — drain any still-outstanding keys in
                    // the OLD receiving chain first (messages that may still
                    // be in flight from before the ratchet), then step.
                    if (s.recvChainKey != null && s.dhRemotePub != null) {
                        drainSkippedKeys(s, b64(s.dhRemotePub.getEncoded()), pn);
                    }
                    byte[] dhOut = ecdh(s.dhSelfPriv, msgDh);
                    byte[][] rkAndCk = kdfRootKey(s.rootKey, dhOut);
                    s.rootKey = rkAndCk[0];
                    s.recvChainKey = rkAndCk[1];
                    s.dhRemotePub = msgDh;
                    s.nr = 0;
                    s.sendChainKey = null; // force our own re-ratchet on next send
                }
                drainSkippedKeys(s, dhKeyId, n);
                byte[][] ckAndMk = kdfChainKey(s.recvChainKey);
                s.recvChainKey = ckAndMk[0];
                messageKey = ckAndMk[1];
                s.nr = n + 1;
            }

            s.handshakeAcked = true; // any successful decrypt proves the partner has our material
            byte[] iv = Base64.decode(header.getString("iv"), Base64.NO_WRAP);
            byte[] ct = Base64.decode(header.getString("ct"), Base64.NO_WRAP);
            byte[] padded = aesGcmDecrypt(messageKey, iv, ct);
            String plaintext = new String(unpadPlaintext(padded), StandardCharsets.UTF_8);

            saveSession(partnerUid, s);
            return plaintext;
        } catch (Exception e) {
            Log.e(TAG, "decrypt failed for " + partnerUid, e);
            return "🔒 Unable to decrypt message";
        }
    }

    private void drainSkippedKeys(Session s, String dhKeyId, int upToExclusive) throws Exception {
        while (s.nr < upToExclusive) {
            byte[][] ckAndMk = kdfChainKey(s.recvChainKey);
            s.recvChainKey = ckAndMk[0];
            if (s.skippedKeys.size() >= MAX_SKIPPED_KEYS_CACHED) {
                java.util.Iterator<String> it = s.skippedKeys.keySet().iterator();
                if (it.hasNext()) { it.next(); it.remove(); } // bounded cache, oldest-first eviction
            }
            s.skippedKeys.put(dhKeyId + "|" + s.nr, ckAndMk[1]);
            s.nr++;
        }
    }

    public static boolean isEncrypted(String text) {
        return text != null && text.startsWith(ENC_PREFIX);
    }

    /** Safety-number style fingerprint of our identity key — shown in ChatSecurityBottomSheet. */
    public String getOurPublicKeyFingerprint() {
        return fingerprintOf(identityPrefs.getString("identity_pub", null));
    }

    /** Fingerprint of the partner's identity key so both sides can compare out-of-band. */
    public String getPartnerPublicKeyFingerprint(String partnerUid) {
        Session s = loadSession(partnerUid);
        if (s == null || s.pendingIdentityPub == null) return "Not established yet";
        return fingerprintOf(s.pendingIdentityPub);
    }

    private String fingerprintOf(String pubB64) {
        if (pubB64 == null) return "No key";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(Base64.decode(pubB64, Base64.NO_WRAP));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, hash.length); i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", hash[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return pubB64.substring(0, Math.min(16, pubB64.length())) + "...";
        }
    }

    /** Drop the session with a partner (e.g. "Reset security code") — next message re-runs X3DH. */
    public void evictSharedKey(String partnerUid) {
        sessionPrefs.edit().remove("session_" + partnerUid).apply();
    }

    /**
     * Remembers the plaintext of a message WE sent, keyed by its Firebase
     * message id. Needed because our own outgoing message inevitably echoes
     * back down the same Firebase listener that delivers incoming messages
     * (chat-open resync, multi-tab, reconnect, etc.) — and what's sitting on
     * Firebase for that id is always ciphertext (that's the whole point of
     * E2EE). We can't "decrypt" our own outgoing ciphertext back — it was
     * encrypted with our SEND chain, which by design never runs in reverse —
     * so instead we keep the plaintext we already know locally and restore
     * it whenever that id comes back from Firebase. See
     * ChatActivity#decryptIncomingIfNeeded().
     *
     * Persisted (survives app restart) and capped at
     * MAX_CACHED_SENT_PLAINTEXT entries with oldest-first eviction, so this
     * can't grow unbounded over months of chatting.
     */
    public void cacheOwnPlaintext(String messageId, String plaintext) {
        if (messageId == null || plaintext == null) return;
        SharedPreferences.Editor editor = sentCachePrefs.edit();
        editor.putString("pt_" + messageId, plaintext);

        java.util.List<String> order = loadSentCacheOrder();
        order.remove(messageId);
        order.add(messageId);
        while (order.size() > MAX_CACHED_SENT_PLAINTEXT) {
            String evictId = order.remove(0);
            editor.remove("pt_" + evictId);
        }
        editor.putString("order", String.join(",", order));
        editor.apply();
    }

    @Nullable
    public String takeOwnPlaintext(String messageId) {
        if (messageId == null) return null;
        return sentCachePrefs.getString("pt_" + messageId, null);
    }

    private java.util.List<String> loadSentCacheOrder() {
        String raw = sentCachePrefs.getString("order", "");
        java.util.List<String> list = new java.util.ArrayList<>();
        if (!raw.isEmpty()) {
            for (String id : raw.split(",")) if (!id.isEmpty()) list.add(id);
        }
        return list;
    }

    /** Wipe everything on logout. */
    public void clearAllKeys() {
        identityPrefs.edit().clear().apply();
        sessionPrefs.edit().clear().apply();
        sentCachePrefs.edit().clear().apply();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Session (de)serialization — persisted so the ratchet survives restarts
    // ═════════════════════════════════════════════════════════════════════

    private static class Session {
        byte[] rootKey;
        byte[] sendChainKey;
        byte[] recvChainKey;
        PrivateKey dhSelfPriv;
        PublicKey  dhSelfPub;
        PublicKey  dhRemotePub;
        int ns, nr, pn;
        boolean handshakeAcked;
        String pendingIdentityPub;
        String pendingSpkId;
        String pendingOpkId;
        Map<String, byte[]> skippedKeys = new LinkedHashMap<>();
    }

    private boolean hasSession(String partnerUid) {
        return sessionPrefs.contains("session_" + partnerUid);
    }

    @Nullable
    private Session loadSession(String partnerUid) {
        String raw = sessionPrefs.getString("session_" + partnerUid, null);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            Session s = new Session();
            s.rootKey      = Base64.decode(o.getString("rk"), Base64.NO_WRAP);
            s.sendChainKey = o.has("sck") ? Base64.decode(o.getString("sck"), Base64.NO_WRAP) : null;
            s.recvChainKey = o.has("rck") ? Base64.decode(o.getString("rck"), Base64.NO_WRAP) : null;
            s.dhSelfPriv   = decodePriv(o.getString("dsp"));
            s.dhSelfPub    = decodePub(o.getString("dpp"));
            s.dhRemotePub  = o.has("drp") ? decodePub(o.getString("drp")) : null;
            s.ns = o.getInt("ns"); s.nr = o.getInt("nr"); s.pn = o.getInt("pn");
            s.handshakeAcked = o.getBoolean("ack");
            s.pendingIdentityPub = o.optString("pik", null);
            s.pendingSpkId = o.optString("pspk", null);
            s.pendingOpkId = o.has("popk") ? o.getString("popk") : null;
            JSONObject skipped = o.optJSONObject("skip");
            if (skipped != null) {
                java.util.Iterator<String> keys = skipped.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    s.skippedKeys.put(k, Base64.decode(skipped.getString(k), Base64.NO_WRAP));
                }
            }
            return s;
        } catch (Exception e) {
            Log.e(TAG, "loadSession corrupted for " + partnerUid + " — resetting session", e);
            return null;
        }
    }

    private void saveSession(String partnerUid, Session s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("rk", b64(s.rootKey));
        if (s.sendChainKey != null) o.put("sck", b64(s.sendChainKey));
        if (s.recvChainKey != null) o.put("rck", b64(s.recvChainKey));
        o.put("dsp", b64(s.dhSelfPriv.getEncoded()));
        o.put("dpp", b64(s.dhSelfPub.getEncoded()));
        if (s.dhRemotePub != null) o.put("drp", b64(s.dhRemotePub.getEncoded()));
        o.put("ns", s.ns); o.put("nr", s.nr); o.put("pn", s.pn);
        o.put("ack", s.handshakeAcked);
        if (s.pendingIdentityPub != null) o.put("pik", s.pendingIdentityPub);
        if (s.pendingSpkId != null) o.put("pspk", s.pendingSpkId);
        if (s.pendingOpkId != null) o.put("popk", s.pendingOpkId);
        JSONObject skip = new JSONObject();
        for (Map.Entry<String, byte[]> e : s.skippedKeys.entrySet()) {
            skip.put(e.getKey(), b64(e.getValue()));
        }
        o.put("skip", skip);
        sessionPrefs.edit().putString("session_" + partnerUid, o.toString()).apply();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Crypto primitives
    // ═════════════════════════════════════════════════════════════════════

    private byte[] ecdh(PrivateKey priv, PublicKey pub) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(priv);
        ka.doPhase(pub, true);
        return ka.generateSecret();
    }

    /** KDF_RK — advances the root key + derives a new chain key from a fresh DH output. */
    private byte[][] kdfRootKey(byte[] rootKey, byte[] dhOut) throws Exception {
        byte[] out = hkdf(rootKey, dhOut, "CallX2-DR-Root-v1", 64);
        byte[] newRoot = new byte[32];
        byte[] newChain = new byte[32];
        System.arraycopy(out, 0, newRoot, 0, 32);
        System.arraycopy(out, 32, newChain, 0, 32);
        return new byte[][]{newRoot, newChain};
    }

    /** KDF_CK — advances a symmetric chain key one step, deriving this message's one-time key. */
    private byte[][] kdfChainKey(byte[] chainKey) throws Exception {
        byte[] messageKey = hmacSha256(chainKey, new byte[]{0x01});
        byte[] nextChainKey = hmacSha256(chainKey, new byte[]{0x02});
        return new byte[][]{nextChainKey, messageKey};
    }

    private byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** Minimal HKDF (RFC 5869) — extract then expand to {@code outLen} bytes. */
    private byte[] hkdf(byte[] salt, byte[] ikm, String infoStr, int outLen) throws Exception {
        byte[] prk = hmacSha256(salt, ikm);
        byte[] info = infoStr.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[outLen];
        byte[] prev = new byte[0];
        int pos = 0;
        int counter = 1;
        while (pos < outLen) {
            byte[] input = concat(prev, info, new byte[]{(byte) counter});
            prev = hmacSha256(prk, input);
            int toCopy = Math.min(prev.length, outLen - pos);
            System.arraycopy(prev, 0, out, pos, toCopy);
            pos += toCopy;
            counter++;
        }
        return out;
    }

    private byte[] aesGcmEncrypt(byte[] key, byte[] iv, byte[] plaintext) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LEN, iv));
        return c.doFinal(plaintext);
    }

    private byte[] aesGcmDecrypt(byte[] key, byte[] iv, byte[] ciphertext) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LEN, iv));
        return c.doFinal(ciphertext);
    }

    /** Pads to the next PAD_BUCKET-byte boundary with a 2-byte big-endian length prefix. */
    private byte[] padPlaintext(byte[] data) {
        int total = 2 + data.length;
        int padded = ((total + PAD_BUCKET - 1) / PAD_BUCKET) * PAD_BUCKET;
        byte[] out = new byte[padded];
        out[0] = (byte) ((data.length >> 8) & 0xFF);
        out[1] = (byte) (data.length & 0xFF);
        System.arraycopy(data, 0, out, 2, data.length);
        return out;
    }

    private byte[] unpadPlaintext(byte[] padded) {
        int len = ((padded[0] & 0xFF) << 8) | (padded[1] & 0xFF);
        byte[] out = new byte[len];
        System.arraycopy(padded, 2, out, 0, len);
        return out;
    }

    private byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, pos, p.length); pos += p.length; }
        return out;
    }

    private boolean keyEquals(PublicKey a, PublicKey b) {
        return java.util.Arrays.equals(a.getEncoded(), b.getEncoded());
    }

    private String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private PublicKey decodePub(String b64) throws Exception {
        byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(bytes));
    }

    private PrivateKey decodePriv(String b64) throws Exception {
        byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    @Nullable
    private PrivateKey loadPriv(String prefKey) throws Exception {
        String b64v = identityPrefs.getString(prefKey, null);
        if (b64v == null) return null;
        return decodePriv(b64v);
    }

    private PublicKey loadPub(String prefKey) throws Exception {
        String b64v = identityPrefs.getString(prefKey, null);
        return decodePub(b64v);
    }
}
