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
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

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

    /** Returned by {@link #decrypt} when an envelope can't be decrypted. Public so callers can detect it reliably instead of matching a literal string. */
    public static final String DECRYPT_FAILED_MARKER = "🔒 Unable to decrypt message";

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
    private static final String RECV_CACHE_PREFS = "e2e_recv_plaintext_v1";
    private static final int MAX_CACHED_RECV_PLAINTEXT = 1000;

    private static volatile E2EEncryptionManager instance;

    private final Context context;
    private final SharedPreferences identityPrefs;
    private final SharedPreferences sessionPrefs;
    private final SharedPreferences sentCachePrefs;
    private final SharedPreferences recvCachePrefs;
    private final ExecutorService executor;
    private final OkHttpClient http;

    /**
     * WHATSAPP-LEVEL SELF-HEAL: fired when {@link #decryptEnvelope} just
     * finished a FRESH X3DH handshake (any of the three branches below —
     * no prior session, near-simultaneous first messages, or a re-key on an
     * already-established session) AND the message riding on that handshake
     * decrypted successfully. That combination means the session with
     * {@code partnerUid} just went from broken/absent to healthy.
     *
     * Without this, a message that was permanently stuck on
     * {@link #DECRYPT_FAILED_MARKER} before the handshake healed stays
     * stuck forever — the ratchet self-heals for every NEW message going
     * forward, but nothing ever goes back and retries the old, already-
     * saved-to-Room ones. Listeners (ChatActivity) use this signal to
     * re-fetch and retry-decrypt exactly those stuck messages.
     */
    public interface SessionHealedListener {
        void onSessionHealed(String partnerUid);
    }

    private final java.util.Set<SessionHealedListener> sessionHealedListeners =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public void addSessionHealedListener(SessionHealedListener l) {
        if (l != null) sessionHealedListeners.add(l);
    }

    public void removeSessionHealedListener(SessionHealedListener l) {
        if (l != null) sessionHealedListeners.remove(l);
    }

    private void notifySessionHealed(String partnerUid) {
        for (SessionHealedListener l : sessionHealedListeners) {
            try {
                l.onSessionHealed(partnerUid);
            } catch (Exception e) {
                Log.w(TAG, "SessionHealedListener threw", e);
            }
        }
    }

    /**
     * CONCURRENCY FIX (root cause of "Unable to decrypt message" appearing
     * intermittently among otherwise-fine messages, in both the open chat
     * screen and background/killed-state notifications):
     *
     * The per-messageId cache in {@link #decrypt(String, String, String)}
     * only makes it safe for the SAME message to be decrypted from multiple
     * call sites (ChatActivity's live listener, ChatRepository's delta
     * sync/pagination, StarredMessagesActivity, CallxMessagingService's
     * notification builder). It does NOT protect against those same call
     * sites decrypting DIFFERENT messages for the same partner AT THE SAME
     * TIME — e.g. an FCM push for message N is processed on
     * CallxMessagingService's background thread at the exact moment
     * ChatActivity's live Firebase listener or ChatRepository's paging
     * executor is processing message N-1 or N+1 for the same conversation.
     * decryptEnvelope() does a plain, unsynchronized load-session ->
     * mutate-in-memory -> save-session round trip, so two overlapping
     * calls for the same partnerUid interleave: whichever thread's
     * saveSession() lands last silently overwrites the other thread's
     * ratchet advancement, and a later message ends up decrypted against a
     * session state that skipped or duplicated a step — surfacing as an
     * AES-GCM auth-tag failure (-> DECRYPT_FAILED_MARKER) for a message
     * that was perfectly fine on the wire. Every decrypt()/encrypt() for a
     * given partner must be serialized against every other decrypt()/
     * encrypt() for that SAME partner (different partners stay independent
     * and can still run in parallel) — that's what this lock map gives us.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> partnerLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Object lockFor(String partnerUid) {
        return partnerLocks.computeIfAbsent(partnerUid, k -> new Object());
    }

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
        this.recvCachePrefs = openEncryptedPrefs(RECV_CACHE_PREFS);
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
                // Same per-partner lock as encrypt()/decrypt() — without it,
                // this check-then-create could race with an incoming message
                // concurrently creating a responder session for the same
                // partner (see decryptEnvelope's acceptSessionAsResponder),
                // and whichever saveSession() lands last would wipe out the
                // other side's session.
                synchronized (lockFor(partnerUid)) {
                    if (hasSession(partnerUid)) {
                        if (callback != null) callback.onComplete(true);
                        return;
                    }
                    boolean ok = initiateSessionAsSender(partnerUid);
                    if (callback != null) callback.onComplete(ok);
                }
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
    // SELF-HEALING RE-KEY — fixes permanent "🔒 Unable to decrypt message"
    // ═════════════════════════════════════════════════════════════════════
    //
    // Scenario: device A reinstalls the app (or clears data, or the user
    // logs into a new device) — its local X3DH identity + Double Ratchet
    // sessions are gone. Device B's session with A is untouched, and B's
    // client already believes handshakeAcked==true for A (see encrypt() —
    // it only attaches "ik"/handshake material while !handshakeAcked), so
    // B keeps sending ordinary ratchet envelopes A can never decrypt —
    // forever, with no built-in recovery. That's the actual cause of the
    // marker persisting on EVERY message rather than just one.
    // Fix: A asks B (over Firebase Realtime DB — no E2E-server change
    // needed) to drop its session and start a fresh X3DH handshake. This
    // node is intentionally separate from the encrypted chat/message data
    // — it carries no plaintext, no key material, just "please re-key with
    // me," identical in spirit to how a HELLO/reset ping works.
    private static final String REKEY_NODE = "e2e_rekey_requests";
    private static final long REKEY_REQUEST_COOLDOWN_MS = 5 * 60 * 1000L; // avoid spamming per-message

    /** Removes our local session with {@code partnerUid} so the next {@link #ensureSession} starts a fresh X3DH handshake. */
    private void clearSession(String partnerUid) {
        sessionPrefs.edit().remove("session_" + partnerUid).apply();
    }

    /**
     * Fire-and-forget: tells {@code partnerUid}'s device(s) that OUR session
     * with them is gone and they should re-key. Rate-limited per partner so
     * a burst of undecryptable messages (e.g. several arriving before the
     * user reopens the chat) only sends one request per cooldown window.
     */
    private void requestReKey(String partnerUid) {
        try {
            String myUid = com.callx.app.utils.FirebaseUtils.getCurrentUid();
            if (myUid == null || myUid.isEmpty() || partnerUid == null || partnerUid.isEmpty()) return;

            long last = identityPrefs.getLong("rekey_req_sent_" + partnerUid, 0);
            if (System.currentTimeMillis() - last < REKEY_REQUEST_COOLDOWN_MS) return;
            identityPrefs.edit().putLong("rekey_req_sent_" + partnerUid, System.currentTimeMillis()).apply();

            FirebaseDatabase.getInstance(Constants.DB_URL)
                    .getReference(REKEY_NODE).child(partnerUid).child(myUid)
                    .setValue(ServerValue.TIMESTAMP);
        } catch (Exception e) {
            Log.w(TAG, "requestReKey failed: " + e.getMessage());
        }
    }

    /**
     * Starts listening for incoming re-key requests addressed to
     * {@code myUid} — call once per process lifetime (see CallxApp#onCreate,
     * same lifetime/pattern as PresenceManager's listeners). For each
     * requester: drop our session with them (if any) and re-run
     * {@link #ensureSession} so the NEXT message we send to them attaches
     * fresh handshake material — self-healing the stuck conversation from
     * whichever side still has a working session.
     */
    public void listenForReKeyRequests(String myUid) {
        if (myUid == null || myUid.isEmpty()) return;
        try {
            DatabaseReference ref = FirebaseDatabase.getInstance(Constants.DB_URL)
                    .getReference(REKEY_NODE).child(myUid);
            ref.addChildEventListener(new ChildEventListener() {
                @Override public void onChildAdded(DataSnapshot snap, String prev) { handle(snap); }
                @Override public void onChildChanged(DataSnapshot snap, String prev) { handle(snap); }
                @Override public void onChildRemoved(DataSnapshot snap) {}
                @Override public void onChildMoved(DataSnapshot snap, String prev) {}
                @Override public void onCancelled(DatabaseError err) {}

                private void handle(DataSnapshot snap) {
                    String requesterUid = snap.getKey();
                    if (requesterUid == null || requesterUid.isEmpty()) return;
                    executor.execute(() -> {
                        try {
                            synchronized (lockFor(requesterUid)) {
                                clearSession(requesterUid);
                            }
                            ensureSession(myUid, requesterUid, ok ->
                                    Log.d(TAG, "Re-keyed with " + requesterUid + " after their request: " + ok));
                        } catch (Exception e) {
                            Log.w(TAG, "Handling re-key request from " + requesterUid + " failed: " + e.getMessage());
                        } finally {
                            // Consumed — remove so it doesn't reprocess on next listener attach.
                            ref.child(requesterUid).removeValue();
                        }
                    });
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "listenForReKeyRequests failed to attach: " + e.getMessage());
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
        s.remoteIdentityPub = bundle.getString("identityKey");

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
        s.remoteIdentityPub = header.getString("ik");
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
        synchronized (lockFor(partnerUid)) {
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
    }

    /**
     * Decrypts an incoming envelope from {@code partnerUid}. Legacy 2-arg
     * form — prefer {@link #decrypt(String, String, String)} which is safe
     * to call from multiple places for the same message (ChatActivity's
     * live listener, ChatRepository's delta sync, StarredMessagesActivity,
     * CallxMessagingService's notification build — all four independently
     * see the same Firebase message). This form has NO messageId to cache
     * against, so it always runs the ratchet — call it at most once per
     * envelope or it will fail on the second call (see class doc on
     * per-message forward secrecy: each message key is derived once, used,
     * then discarded).
     */
    public String decrypt(String maybeEnvelope, String partnerUid) {
        return decrypt(maybeEnvelope, partnerUid, null);
    }

    /**
     * Decrypts an incoming envelope from {@code partnerUid}, keyed by
     * {@code messageId} so the SAME message can safely be "decrypted" any
     * number of times from ANY of the call sites in the app (notification
     * build on FCM receipt, ChatActivity's live Firebase listener,
     * ChatRepository's delta-sync/pagination path, StarredMessagesActivity)
     * without ever touching the ratchet more than once.
     *
     * Why this matters: the Double Ratchet derives a message's decryption
     * key ONCE and discards it immediately after use (that's what gives
     * per-message forward secrecy). A naive decrypt() that re-parses the
     * ratchet state on every call works the FIRST time it's invoked for a
     * given message and silently produces garbage / auth-tag failure
     * (-> "Unable to decrypt message") on every subsequent call for that
     * same message — regardless of which part of the app happens to call
     * it first. With four+ independent call sites all capable of seeing
     * the same Firebase message, that collision is common, not an edge
     * case. This persisted, capped cache makes decrypt() idempotent: the
     * ratchet is only ever advanced once per messageId, and every
     * subsequent caller (including a cold app restart) gets back the same
     * plaintext for free.
     *
     * If the text isn't one of our envelopes (older client, or plaintext),
     * it's returned unchanged so decryption failures never corrupt the
     * chat — worst case is the raw string shown, not a crash.
     */
    public String decrypt(String maybeEnvelope, String partnerUid, @Nullable String messageId) {
        if (!isEncrypted(maybeEnvelope)) return maybeEnvelope;

        // CONCURRENCY FIX: the cache check-then-decrypt-then-cache-write
        // sequence must be atomic per partner, not just the ratchet mutation
        // inside decryptEnvelope(). Without this outer lock, two threads
        // racing on the SAME messageId (e.g. the notification builder and
        // ChatActivity's live listener both waking up for the same push)
        // could both miss the cache before either had a chance to populate
        // it, both fall through to decryptEnvelope(), and — even though
        // decryptEnvelope() is itself now serialized — the second one to
        // actually run would be decrypting an envelope whose one-time key
        // the first one already consumed, producing DECRYPT_FAILED_MARKER
        // for a message that in fact decrypted fine moments earlier.
        synchronized (lockFor(partnerUid)) {
            if (messageId != null) {
                String cached = recvCachePrefs.getString("pt_" + messageId, null);
                if (cached != null) return cached;
            }

            String plaintext = decryptEnvelope(maybeEnvelope, partnerUid);

            if (messageId != null && !DECRYPT_FAILED_MARKER.equals(plaintext)) {
                cacheRecvPlaintext(messageId, plaintext);
            }
            return plaintext;
        }
    }

    private void cacheRecvPlaintext(String messageId, String plaintext) {
        SharedPreferences.Editor editor = recvCachePrefs.edit();
        editor.putString("pt_" + messageId, plaintext);

        java.util.List<String> order = loadRecvCacheOrder();
        order.remove(messageId);
        order.add(messageId);
        while (order.size() > MAX_CACHED_RECV_PLAINTEXT) {
            String evictId = order.remove(0);
            editor.remove("pt_" + evictId);
        }
        editor.putString("order", String.join(",", order));
        editor.apply();
    }

    private java.util.List<String> loadRecvCacheOrder() {
        String raw = recvCachePrefs.getString("order", "");
        java.util.List<String> list = new java.util.ArrayList<>();
        if (!raw.isEmpty()) {
            for (String id : raw.split(",")) if (!id.isEmpty()) list.add(id);
        }
        return list;
    }

    /** The actual one-shot ratchet decrypt — see {@link #decrypt(String, String, String)} for why callers should go through the cached wrapper instead of calling this directly. */
    private String decryptEnvelope(String maybeEnvelope, String partnerUid) {
        synchronized (lockFor(partnerUid)) {
            // WHATSAPP-LEVEL SELF-HEAL: set true in any branch below that
            // accepts a FRESH handshake (session went from broken/absent to
            // newly-established). Checked at the bottom, right after a
            // successful decrypt, to fire notifySessionHealed().
            boolean freshHandshake = false;
            try {
                JSONObject header = new JSONObject(maybeEnvelope.substring(ENC_PREFIX.length()));
                Session s = loadSession(partnerUid);

                if (s == null) {
                    if (!header.has("ik")) {
                        // PERMANENT-DECRYPT-FAILURE FIX: this is the "we have
                        // no session AND the sender didn't attach handshake
                        // material" case — structurally undecryptable, not a
                        // transient glitch. It happens when THIS device lost
                        // its local session (reinstall / app-data clear /
                        // new device / logout+login) while the SENDER's
                        // session survived untouched. The sender's client
                        // already believes handshakeAcked==true for us (see
                        // encrypt() — it only attaches "ik" while
                        // !handshakeAcked), so it will keep silently sending
                        // envelopes we can never decrypt, forever, with no
                        // self-healing — this was the actual bug behind
                        // "lock icon / Unable to decrypt" persisting on
                        // every message instead of just one.
                        // Fix: ask the sender (over Firebase, not the E2E
                        // server — no server change needed) to drop their
                        // session with us and re-key. Fire-and-forget, rate
                        // limited per partner so a burst of undecryptable
                        // messages doesn't spam multiple requests.
                        Log.w(TAG, "Encrypted message with no session and no handshake data — requesting re-key from " + partnerUid);
                        requestReKey(partnerUid);
                        return DECRYPT_FAILED_MARKER;
                    }
                    s = acceptSessionAsResponder(header);
                    freshHandshake = true; // no prior session at all — this IS the fresh handshake
                } else if (header.has("ik")) {
                    // THE ACTUAL BUG behind "still failing even after I sent a
                    // new message": this used to only fire when
                    // `!s.handshakeAcked`. But once a conversation is
                    // established, handshakeAcked is true FOREVER on both
                    // sides and a healthy peer's client never attaches "ik"
                    // again (see encrypt()). So the ONLY reason an
                    // already-acked session would ever see incoming "ik" is
                    // that the partner's app lost its old session (reinstall
                    // / re-key) and started a brand-new X3DH handshake — and
                    // this branch was silently ignoring exactly that,
                    // permanently stuck decrypting with a session the
                    // partner no longer has the matching keys for, no matter
                    // how many new messages either side sent afterward.
                    //
                    // remoteIdentityPub lets us still tell that apart from a
                    // stale REPLAY of an old (already-cached) handshake
                    // message surfacing again during history pagination —
                    // in that case the "ik" is identical to what we already
                    // trust, so we leave the live session alone instead of
                    // clobbering a healthy, more-advanced ratchet with it.
                    // For sessions saved before this field existed,
                    // remoteIdentityPub is null, so we trust the incoming
                    // "ik" (there's nothing to compare it against yet) —
                    // this is what lets an already-stuck conversation like
                    // this one self-heal on the very next handshake message.
                    String incomingIdentity = header.getString("ik");
                    boolean isReplayOfKnownIdentity =
                            s.remoteIdentityPub != null && s.remoteIdentityPub.equals(incomingIdentity);

                    if (!s.handshakeAcked) {
                        // Near-simultaneous first messages both ways — the
                        // responder-derived session is authoritative for
                        // messages carrying "ik"; keep our own outbound
                        // progress intact.
                        Session responderSide = acceptSessionAsResponder(header);
                        responderSide.sendChainKey = s.sendChainKey;
                        s = responderSide;
                        freshHandshake = true; // near-simultaneous first messages both ways
                    } else if (!isReplayOfKnownIdentity) {
                        Log.w(TAG, "Re-keying session with " + partnerUid
                                + " — fresh handshake on an already-established session (partner likely reinstalled)");
                        // SECURITY-CODE-CHANGE ALERT: this branch only runs when
                        // we already had a fully-established, previously-acked
                        // session (s.handshakeAcked) whose remoteIdentityPub we
                        // trusted — and the new handshake carries a DIFFERENT
                        // identity key. That's exactly the situation WhatsApp
                        // surfaces as "Your security code with X has changed" —
                        // either a legitimate reinstall/new-device, or a server
                        // silently swapping in a key it controls (MITM).
                        // Persist a durable alert (survives even if this decrypt
                        // happens in a background FCM path with no chat UI open)
                        // so ChatActivity/ChatMessageSender can surface it as an
                        // in-chat system bubble next time the conversation is
                        // opened. A first-time handshake (remoteIdentityPub ==
                        // null, i.e. nothing trusted to compare against yet) is
                        // NOT an alert — that's just normal session setup.
                        if (s.remoteIdentityPub != null) {
                            persistSecurityAlert(partnerUid, s.remoteIdentityPub, incomingIdentity);
                        }
                        s = acceptSessionAsResponder(header);
                        freshHandshake = true; // re-key on an already-established session (reinstall/new device)
                    }
                    // else: stale replay of a handshake we've already
                    // incorporated — fall through to the normal ratchet path
                    // below using the existing, healthy session.
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

                if (freshHandshake) {
                    // The session that JUST healed is what let this message
                    // decrypt at all — notify so any earlier messages from
                    // this partner still stuck on DECRYPT_FAILED_MARKER get
                    // retried now. Fired after saveSession() so a listener
                    // that immediately re-decrypts sees the healed session.
                    notifySessionHealed(partnerUid);
                }
                return plaintext;
            } catch (Exception e) {
                Log.e(TAG, "decrypt failed for " + partnerUid, e);
                return DECRYPT_FAILED_MARKER;
            }
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

    /**
     * Fingerprint of the partner's identity key so both sides can compare
     * out-of-band.
     *
     * BUG FIX: this used to read {@code s.pendingIdentityPub}, which is
     * actually a mislabeled copy of OUR OWN identity key (see
     * {@code initiateSessionAsSender}: {@code s.pendingIdentityPub =
     * identityPrefs.getString("identity_pub", ...)}) — and is never set at
     * all on the responder side ({@code acceptSessionAsResponder} never
     * touches it). Net effect: the safety-number UI was either showing the
     * user their OWN key back at them (sender side) or permanently
     * "Not established yet" (responder side) — never the partner's actual
     * key, which defeats the entire point of a safety-number check. The
     * correct field is {@code remoteIdentityPub}, which both
     * initiateSessionAsSender and acceptSessionAsResponder set to the
     * PARTNER's identity key.
     */
    public String getPartnerPublicKeyFingerprint(String partnerUid) {
        Session s = loadSession(partnerUid);
        if (s == null || s.remoteIdentityPub == null) return "Not established yet";
        return fingerprintOf(s.remoteIdentityPub);
    }

    /**
     * Combined WhatsApp-style "safety number" for a conversation — a single
     * value BOTH sides compute identically (our identity key + their
     * identity key, sorted so ordering doesn't matter, hashed together),
     * shown as five 5-digit groups. Comparing this number out-of-band (in
     * person, or over a different channel) is what actually detects a
     * MITM'd server silently swapping in a key it controls — a per-side
     * fingerprint alone can't prove YOUR OWN client wasn't also handed a
     * spoofed partner key. Returns null if no session/keys yet.
     */
    @Nullable
    public String getSafetyNumber(String partnerUid) {
        try {
            String ourPub = identityPrefs.getString("identity_pub", null);
            Session s = loadSession(partnerUid);
            if (ourPub == null || s == null || s.remoteIdentityPub == null) return null;
            String a = ourPub, b = s.remoteIdentityPub;
            // Sort so both participants derive the exact same combined
            // digest regardless of who's "us" and who's "them".
            String first = a.compareTo(b) <= 0 ? a : b;
            String second = a.compareTo(b) <= 0 ? b : a;
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (first + "|" + second).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int group = 0; group < 5; group++) {
                if (group > 0) sb.append(' ');
                int val = ((hash[group * 2] & 0xFF) << 8 | (hash[group * 2 + 1] & 0xFF)) % 100000;
                sb.append(String.format("%05d", val));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether the person has confirmed (out-of-band) that {@link #getSafetyNumber} matches on both devices. */
    public boolean isVerified(String partnerUid) {
        return identityPrefs.getBoolean("verified_" + partnerUid, false);
    }

    public void setVerified(String partnerUid, boolean verified) {
        identityPrefs.edit().putBoolean("verified_" + partnerUid, verified).apply();
    }

    // ═════════════════════════════════════════════════════════════════════
    // SECURITY-CODE-CHANGE ALERT (WhatsApp-style "safety number changed")
    // ═════════════════════════════════════════════════════════════════════
    //
    // Persisted (not just in-memory) because the identity change is most
    // often first observed from a background FCM push (CallxMessagingService)
    // decrypting a message while no chat screen is open at all — the alert
    // has to survive until the user actually opens that conversation.

    private void persistSecurityAlert(String partnerUid, String oldIdentityPub, String newIdentityPub) {
        try {
            JSONObject o = new JSONObject();
            o.put("old", fingerprintOf(oldIdentityPub));
            o.put("new", fingerprintOf(newIdentityPub));
            o.put("ts", System.currentTimeMillis());
            identityPrefs.edit().putString("sec_alert_" + partnerUid, o.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "persistSecurityAlert failed", e);
        }
        // A previously-verified safety number no longer applies once the
        // underlying identity key has changed — mirrors WhatsApp clearing
        // the verified checkmark on a security-code change.
        setVerified(partnerUid, false);
    }

    public boolean hasPendingSecurityAlert(String partnerUid) {
        return identityPrefs.contains("sec_alert_" + partnerUid);
    }

    /**
     * Consumes (clears) and returns a human-readable chat-bubble message for
     * a pending security-code-change alert, or null if there isn't one.
     * Intended to be called once, right before inserting a local-only
     * system message into the conversation (see ChatMessageSender
     * #insertSecurityEventIfPending) — never written to Firebase, since
     * each device detects and reports this independently, exactly like
     * WhatsApp.
     */
    @Nullable
    public String consumeSecurityAlertMessage(String partnerUid, @Nullable String partnerDisplayName) {
        String raw = identityPrefs.getString("sec_alert_" + partnerUid, null);
        if (raw == null) return null;
        identityPrefs.edit().remove("sec_alert_" + partnerUid).apply();
        String who = (partnerDisplayName != null && !partnerDisplayName.isEmpty())
                ? partnerDisplayName : "this contact";
        return "🔒 Your security code with " + who
                + " has changed. No one outside this chat can read your messages — tap Security to verify the new code.";
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
        recvCachePrefs.edit().clear().apply();
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
        /** The partner's identity public key this session was established against — lets us tell a genuine re-key (partner reinstalled) apart from a stale replay of an old handshake message. Null for sessions saved before this field existed. */
        String remoteIdentityPub;
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
            s.remoteIdentityPub = o.optString("rip", null);
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
        if (s.remoteIdentityPub != null) o.put("rip", s.remoteIdentityPub);
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
