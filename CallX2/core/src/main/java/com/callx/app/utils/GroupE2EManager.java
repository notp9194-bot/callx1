package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * GroupE2EManager — End-to-End Encryption for CallX2 GROUP chat text
 * messages, using the Sender Keys protocol (the same approach WhatsApp /
 * Signal group chat uses on top of their 1:1 Double Ratchet):
 *
 *   1. Every member generates their OWN Sender Key — a random 32-byte
 *      symmetric chain key + a short keyId. This is completely separate
 *      from (but bootstrapped through) their 1:1 identity.
 *   2. That Sender Key is handed to every OTHER member individually, sealed
 *      with the EXISTING 1:1 X3DH/Double-Ratchet session for that pair (see
 *      {@link E2EEncryptionManager#encrypt}) — so distribution reuses 1:1's
 *      forward-secrecy/authentication instead of inventing a new channel.
 *      The sealed distribution blob is dropped at
 *      groupSenderKeys/{groupId}/{recipientUid}/{fromUid} (see
 *      FirebaseUtils#getGroupSenderKeysRef) for the recipient to fetch.
 *   3. To send a group message, a member encrypts ONCE with their own
 *      Sender Key's forward-only HMAC chain (advance-then-derive, same
 *      KDF_CK construction as the 1:1 ratchet) — every member decrypts the
 *      SAME ciphertext with that sender's Sender Key, so this is O(1)
 *      encryptions per message instead of O(members) like re-using 1:1
 *      pairwise sessions would require.
 *   4. On membership change:
 *        - New member added → every existing member's device notices (next
 *          time {@link #ensureGroupCrypto} runs, e.g. on opening the group)
 *          and distributes its CURRENT Sender Key to the new member — no
 *          rotation needed, the new member just starts verifying from
 *          whatever counter value is current.
 *        - Member removed / leaves → every remaining member's device
 *          notices the membership shrink and calls {@link #rotateSenderKey}:
 *          throws away the old chain key, generates a brand new one, and
 *          redistributes it to the (smaller) remaining member list. Without
 *          this, the removed member could keep decrypting every future
 *          message with the Sender Key they already have — rotation is what
 *          actually revokes their access.
 *
 * Wire format (stored as Message.text in Firebase, groupMessages/{groupId}):
 * "ge2r1:" + compact JSON {kid, n, iv, ct}. Distribution blobs riding over
 * the 1:1 channel are plain JSON {kid, ck, n} before being sealed by
 * {@link E2EEncryptionManager#encrypt}, so they look like an ordinary 1:1
 * ciphertext to anything except the two ends of that pairwise session.
 */
public class GroupE2EManager {

    private static final String TAG = "GroupE2ESenderKeys";
    private static final String ENC_PREFIX = "ge2r1:";

    public static final String DECRYPT_FAILED_MARKER = "🔒 Unable to decrypt message";
    public static final String WAITING_FOR_KEY_MARKER = "🔒 Waiting for encryption key…";

    private static final int GCM_IV_LEN  = 12;
    private static final int GCM_TAG_LEN = 128;
    private static final int PAD_BUCKET  = 32;
    private static final int MAX_SKIPPED_KEYS_PER_SENDER = 200;

    private static final String OWN_KEY_PREFS   = "gsk_own_v1";
    private static final String RECV_KEY_PREFS  = "gsk_recv_v1";
    private static final String MEMBERS_PREFS   = "gsk_members_v1";
    private static final String SENT_CACHE_PREFS = "gsk_sent_plaintext_v1";
    private static final String RECV_CACHE_PREFS = "gsk_recv_plaintext_v1";
    private static final int MAX_CACHED_PLAINTEXT = 500;

    private static volatile GroupE2EManager instance;

    private final Context context;
    private final SharedPreferences ownKeyPrefs;
    private final SharedPreferences recvKeyPrefs;
    private final SharedPreferences membersPrefs;
    private final SharedPreferences sentCachePrefs;
    private final SharedPreferences recvCachePrefs;
    private final ExecutorService executor;

    /** Same per-group serialization rationale as E2EEncryptionManager#partnerLocks — see that class's javadoc. */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> groupLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Object lockFor(String groupId) {
        return groupLocks.computeIfAbsent(groupId, k -> new Object());
    }

    /**
     * WHATSAPP-LEVEL SELF-HEAL (group side): fired when
     * {@link #importIncomingSenderKeys} just saved a GENUINELY NEW or
     * REPLACED Sender Key for {@code (groupId, fromUid)} — i.e. the exact
     * moment a conversation that was showing {@link #WAITING_FOR_KEY_MARKER}
     * for that sender becomes decryptable. Without this, old messages
     * already saved to Room with that marker stay stuck on it forever, even
     * though every new incoming message from the same sender decrypts fine
     * from here on. Listeners (GroupChatActivity) use this to re-fetch and
     * retry-decrypt exactly those stuck messages.
     */
    public interface SenderKeyHealedListener {
        void onSenderKeyHealed(String groupId, String fromUid);
    }

    private final java.util.Set<SenderKeyHealedListener> senderKeyHealedListeners =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public void addSenderKeyHealedListener(SenderKeyHealedListener l) {
        if (l != null) senderKeyHealedListeners.add(l);
    }

    public void removeSenderKeyHealedListener(SenderKeyHealedListener l) {
        if (l != null) senderKeyHealedListeners.remove(l);
    }

    private void notifySenderKeyHealed(String groupId, String fromUid) {
        for (SenderKeyHealedListener l : senderKeyHealedListeners) {
            try {
                l.onSenderKeyHealed(groupId, fromUid);
            } catch (Exception e) {
                Log.w(TAG, "SenderKeyHealedListener threw", e);
            }
        }
    }

    private GroupE2EManager(Context ctx) {
        this.context  = ctx.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2);
        this.ownKeyPrefs    = openEncryptedPrefs(OWN_KEY_PREFS);
        this.recvKeyPrefs   = openEncryptedPrefs(RECV_KEY_PREFS);
        this.membersPrefs   = openEncryptedPrefs(MEMBERS_PREFS);
        this.sentCachePrefs = openEncryptedPrefs(SENT_CACHE_PREFS);
        this.recvCachePrefs = openEncryptedPrefs(RECV_CACHE_PREFS);
    }

    public static GroupE2EManager getInstance(Context ctx) {
        if (instance == null) {
            synchronized (GroupE2EManager.class) {
                if (instance == null) instance = new GroupE2EManager(ctx);
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
    // ENTRY POINT — call once when a group chat is opened (GroupChatActivity)
    // ═════════════════════════════════════════════════════════════════════

    public interface SyncCallback {
        void onComplete(boolean rotated);
    }

    /**
     * Safe to call every time the group chat screen opens. Detects
     * membership changes since last time, rotates our Sender Key if anyone
     * left/was removed, generates our Sender Key if we don't have one yet,
     * distributes it to any member we haven't sent our CURRENT key to, and
     * imports any Sender Keys other members have sent us.
     */
    public void ensureGroupCrypto(String groupId, String currentUid, @Nullable SyncCallback callback) {
        executor.execute(() -> {
            try {
                FirebaseUtils.getGroupMembersRef(groupId).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        executor.execute(() -> {
                            try {
                                List<String> current = new ArrayList<>();
                                for (DataSnapshot child : snap.getChildren()) {
                                    if (Boolean.TRUE.equals(child.getValue(Boolean.class))) {
                                        current.add(child.getKey());
                                    }
                                }
                                boolean rotated = onMembersKnown(groupId, currentUid, current);
                                importIncomingSenderKeys(groupId, currentUid);
                                if (callback != null) callback.onComplete(rotated);
                            } catch (Exception e) {
                                Log.e(TAG, "ensureGroupCrypto post-members failed", e);
                                if (callback != null) callback.onComplete(false);
                            }
                        });
                    }
                    @Override public void onCancelled(DatabaseError error) {
                        if (callback != null) callback.onComplete(false);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "ensureGroupCrypto failed", e);
                if (callback != null) callback.onComplete(false);
            }
        });
    }

    private boolean onMembersKnown(String groupId, String currentUid, List<String> currentMembers) throws Exception {
        synchronized (lockFor(groupId)) {
            List<String> lastKnown = loadKnownMembers(groupId);
            boolean someoneLeft = false;
            for (String uid : lastKnown) {
                if (!currentMembers.contains(uid)) { someoneLeft = true; break; }
            }

            saveKnownMembers(groupId, currentMembers);

            if (someoneLeft) {
                // A member left/was removed since we last checked — rotate so
                // they can no longer decrypt anything sent from here on.
                rotateSenderKey(groupId, currentUid, currentMembers);
                return true;
            } else {
                // No departures — just make sure we have a key, and make
                // sure every current member (including anyone newly added)
                // has our current key.
                getOrCreateOwnSenderKey(groupId);
                distributeOwnSenderKey(groupId, currentUid, currentMembers);
                return false;
            }
        }
    }

    /** Explicit rotation entry point — also safe to call directly (e.g. right after a remove/leave action) for faster revocation than waiting for the next chat open. */
    public void rotateSenderKey(String groupId, String currentUid, List<String> remainingMembers) {
        try {
            synchronized (lockFor(groupId)) {
                SenderKeyState fresh = new SenderKeyState();
                fresh.keyId = UUID.randomUUID().toString().substring(0, 8);
                fresh.chainKey = randomBytes(32);
                fresh.n = 0;
                saveOwnState(groupId, fresh);
                clearDistributedTo(groupId);
                distributeOwnSenderKey(groupId, currentUid, remainingMembers);
            }
        } catch (Exception e) {
            Log.e(TAG, "rotateSenderKey failed for " + groupId, e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // DISTRIBUTION — sealing our Sender Key over each member's 1:1 session
    // ═════════════════════════════════════════════════════════════════════

    private void distributeOwnSenderKey(String groupId, String currentUid, List<String> members) throws Exception {
        SenderKeyState own = getOrCreateOwnSenderKey(groupId);
        List<String> alreadySent = loadDistributedTo(groupId, own.keyId);

        E2EEncryptionManager e2e = E2EEncryptionManager.getInstance(context);

        for (String memberUid : members) {
            if (memberUid == null || memberUid.equals(currentUid)) continue;
            if (alreadySent.contains(memberUid)) continue;

            e2e.ensureSession(currentUid, memberUid, ok -> {
                if (!ok) return; // will retry next time ensureGroupCrypto runs (e.g. next chat open)
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("kid", own.keyId);
                    payload.put("ck", b64(own.chainKey));
                    payload.put("n", own.n);

                    String sealed = e2e.encrypt(payload.toString(), memberUid);

                    FirebaseUtils.getGroupSenderKeysRef(groupId)
                            .child(memberUid).child(currentUid)
                            .setValue(sealed);

                    markDistributedTo(groupId, own.keyId, memberUid);
                } catch (Exception e) {
                    Log.w(TAG, "distributeOwnSenderKey: seal/send failed for " + memberUid + ": " + e.getMessage());
                }
            });
        }
    }

    /** Fetches everyone else's Sender Key that's been dropped into OUR mailbox and imports any we haven't seen yet. */
    private void importIncomingSenderKeys(String groupId, String currentUid) {
        FirebaseUtils.getGroupSenderKeysRef(groupId).child(currentUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        E2EEncryptionManager e2e = E2EEncryptionManager.getInstance(context);
                        for (DataSnapshot child : snap.getChildren()) {
                            String fromUid = child.getKey();
                            String sealed  = child.getValue(String.class);
                            if (fromUid == null || sealed == null) continue;
                            if (fromUid.equals(currentUid)) continue;

                            String syntheticId = "gsk_" + sha256Short(sealed);
                            e2e.ensureSession(currentUid, fromUid, ok -> {
                                try {
                                    String json = e2e.decrypt(sealed, fromUid, syntheticId);
                                    if (json == null || E2EEncryptionManager.DECRYPT_FAILED_MARKER.equals(json)) return;
                                    if (!json.trim().startsWith("{")) return; // not one of our envelopes (ignore)

                                    JSONObject payload = new JSONObject(json);
                                    String kid = payload.getString("kid");
                                    byte[] chain = Base64.decode(payload.getString("ck"), Base64.NO_WRAP);
                                    int n = payload.optInt("n", 0);

                                    SenderKeyState existing = loadRecvState(groupId, fromUid);
                                    if (existing != null && existing.keyId.equals(kid)) {
                                        return; // already have this exact key
                                    }
                                    SenderKeyState newState = new SenderKeyState();
                                    newState.keyId = kid;
                                    newState.chainKey = chain;
                                    newState.n = n;
                                    saveRecvState(groupId, fromUid, newState);
                                    // Genuinely new/replaced key just landed — this
                                    // sender's WAITING_FOR_KEY_MARKER messages can
                                    // now be retried.
                                    notifySenderKeyHealed(groupId, fromUid);
                                } catch (Exception e) {
                                    Log.w(TAG, "importIncomingSenderKeys: import failed for " + fromUid + ": " + e.getMessage());
                                }
                            });
                        }
                    }
                    @Override public void onCancelled(DatabaseError error) {}
                });
    }

    // ═════════════════════════════════════════════════════════════════════
    // SELF-HEALING RESEND REQUEST — fixes permanent "Waiting for encryption
    // key…" in group chat (the group-side counterpart to
    // E2EEncryptionManager's e2e_rekey_requests fix for 1:1).
    // ═════════════════════════════════════════════════════════════════════
    //
    // Scenario: member A reinstalls the app (or clears data / logs into a
    // new device). Their LOCAL copy of every other member's Sender Key
    // (recvKeyPrefs) is gone — but nobody else's device knows that. Each
    // OTHER member's device tracks "who have I already sent my current
    // Sender Key to" via loadDistributedTo()/markDistributedTo(), keyed by
    // their OWN keyId, and that record is untouched by A losing their data
    // — from every other member's point of view A already has their key,
    // so distributeOwnSenderKey() keeps skipping A forever (the
    // `alreadySent.contains(memberUid)` check), exactly mirroring the 1:1
    // `handshakeAcked` staying permanently true on the healthy side. A's
    // decryptEnvelope() correctly detects "no Sender Key for this sender"
    // and shows WAITING_FOR_KEY_MARKER — but the only recovery it triggers
    // is importIncomingSenderKeys(), which just re-checks A's OWN mailbox.
    // If nothing new has been dropped there (because every other member
    // thinks A already has the key), that re-check finds nothing and the
    // marker persists on every message from that sender, forever, even
    // after the group is reopened repeatedly.
    //
    // Fix: A asks the specific sender (over Firebase RTDB — no server
    // change needed, same pattern as e2e_rekey_requests) to forget that
    // they already sent A their key and redistribute it fresh.
    private static final String GROUP_KEY_REQUEST_COOLDOWN_PREFIX = "keyreq_sent_";
    private static final long GROUP_KEY_REQUEST_COOLDOWN_MS = 5 * 60 * 1000L; // avoid spamming per-message

    /**
     * Fire-and-forget: tells {@code fromUid}'s device(s) that we don't have
     * their current Sender Key for {@code groupId} and they should
     * redistribute it to us. Rate-limited per (group, sender) so a burst of
     * WAITING_FOR_KEY messages from the same sender only sends one request
     * per cooldown window.
     */
    private void requestSenderKeyResend(String groupId, String fromUid) {
        try {
            String myUid = FirebaseUtils.getCurrentUid();
            if (myUid == null || myUid.isEmpty() || fromUid == null || fromUid.isEmpty()) return;

            String cooldownKey = GROUP_KEY_REQUEST_COOLDOWN_PREFIX + groupId + "_" + fromUid;
            long last = membersPrefs.getLong(cooldownKey, 0);
            if (System.currentTimeMillis() - last < GROUP_KEY_REQUEST_COOLDOWN_MS) return;
            membersPrefs.edit().putLong(cooldownKey, System.currentTimeMillis()).apply();

            FirebaseUtils.getGroupSenderKeyRequestsRef(fromUid)
                    .child(groupId).child(myUid)
                    .setValue(ServerValue.TIMESTAMP);
        } catch (Exception e) {
            Log.w(TAG, "requestSenderKeyResend failed: " + e.getMessage());
        }
    }

    /**
     * Starts listening for incoming Sender-Key resend requests addressed to
     * {@code myUid} — call once per process lifetime (see CallxApp#onCreate,
     * same lifetime/pattern as E2EEncryptionManager#listenForReKeyRequests).
     * For each (groupId, requesterUid): forget that we already sent our
     * current key to that member and redistribute it — self-healing a
     * stuck-forever "Waiting for encryption key…" from whichever side still
     * has a working Sender Key, without waiting for that group to be
     * reopened.
     */
    public void listenForGroupKeyRequests(String myUid) {
        if (myUid == null || myUid.isEmpty()) return;
        try {
            DatabaseReference ref = FirebaseUtils.getGroupSenderKeyRequestsRef(myUid);
            ref.addChildEventListener(new ChildEventListener() {
                @Override public void onChildAdded(DataSnapshot groupSnap, String prev) { handleGroup(groupSnap); }
                @Override public void onChildChanged(DataSnapshot groupSnap, String prev) { handleGroup(groupSnap); }
                @Override public void onChildRemoved(DataSnapshot snap) {}
                @Override public void onChildMoved(DataSnapshot snap, String prev) {}
                @Override public void onCancelled(DatabaseError err) {}

                private void handleGroup(DataSnapshot groupSnap) {
                    String groupId = groupSnap.getKey();
                    if (groupId == null || groupId.isEmpty()) return;
                    for (DataSnapshot reqSnap : groupSnap.getChildren()) {
                        String requesterUid = reqSnap.getKey();
                        if (requesterUid == null || requesterUid.isEmpty()) continue;
                        executor.execute(() -> {
                            try {
                                forceRedistributeSenderKeyTo(groupId, myUid, requesterUid);
                            } catch (Exception e) {
                                Log.w(TAG, "Handling group key request from " + requesterUid
                                        + " in " + groupId + " failed: " + e.getMessage());
                            } finally {
                                // Consumed — remove so it doesn't reprocess on next listener attach.
                                ref.child(groupId).child(requesterUid).removeValue();
                            }
                        });
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "listenForGroupKeyRequests failed to attach: " + e.getMessage());
        }
    }

    /**
     * Forgets that we already sent {@code memberUid} our CURRENT Sender Key
     * for {@code groupId} and redistributes it — the actual self-heal
     * action once a resend request comes in. Only resends to someone still
     * on our known-members list for this group, so a member who genuinely
     * left/was removed (but still remembers the groupId) can't use this to
     * re-acquire a key they were deliberately cut off from by rotation.
     */
    private void forceRedistributeSenderKeyTo(String groupId, String currentUid, String memberUid) throws Exception {
        synchronized (lockFor(groupId)) {
            if (!loadKnownMembers(groupId).contains(memberUid)) {
                Log.w(TAG, "Ignoring group key resend request from non-member " + memberUid + " in " + groupId);
                return;
            }
            SenderKeyState own = getOrCreateOwnSenderKey(groupId);
            removeDistributedTo(groupId, own.keyId, memberUid);
        }
        distributeOwnSenderKey(groupId, currentUid, java.util.Collections.singletonList(memberUid));
    }

    /** Removes a single member from a keyId's "already sent" record — the opposite of {@link #markDistributedTo}. */
    private void removeDistributedTo(String groupId, String keyId, String memberUid) {
        List<String> list = loadDistributedTo(groupId, keyId);
        if (list.remove(memberUid)) {
            membersPrefs.edit().putString("dist_" + groupId + "_" + keyId, String.join(",", list)).apply();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // PUBLIC API — encrypt / decrypt (group text only)
    // ═════════════════════════════════════════════════════════════════════

    /** Encrypts {@code plaintext} with our own Sender Key for {@code groupId}. Call {@link #ensureGroupCrypto} at least once first (GroupChatActivity does this on open). */
    public String encryptGroupMessage(String plaintext, String groupId) throws Exception {
        synchronized (lockFor(groupId)) {
            SenderKeyState s = getOrCreateOwnSenderKey(groupId);

            byte[][] ckAndMk = kdfChainKey(s.chainKey);
            s.chainKey = ckAndMk[0];
            byte[] messageKey = ckAndMk[1];
            int n = s.n++;
            saveOwnState(groupId, s);

            byte[] iv = randomBytes(GCM_IV_LEN);
            byte[] padded = padPlaintext(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = aesGcmEncrypt(messageKey, iv, padded);

            JSONObject env = new JSONObject();
            env.put("kid", s.keyId);
            env.put("n", n);
            env.put("iv", b64(iv));
            env.put("ct", b64(ciphertext));
            return ENC_PREFIX + env.toString();
        }
    }

    /**
     * Decrypts an incoming group envelope from {@code senderUid}, keyed by
     * {@code messageId} for idempotency across multiple call sites (same
     * rationale as {@link E2EEncryptionManager#decrypt(String, String, String)}).
     * Returns {@link #WAITING_FOR_KEY_MARKER} if we haven't imported that
     * sender's current Sender Key yet (self-heals once
     * {@link #ensureGroupCrypto} next runs and picks it up).
     */
    public String decryptGroupMessage(String maybeEnvelope, String groupId, String senderUid, @Nullable String messageId) {
        if (!isEncrypted(maybeEnvelope)) return maybeEnvelope;

        synchronized (lockFor(groupId)) {
            if (messageId != null) {
                String cached = recvCachePrefs.getString("pt_" + messageId, null);
                if (cached != null) return cached;
            }

            String plaintext = decryptEnvelope(maybeEnvelope, groupId, senderUid);

            if (messageId != null
                    && !DECRYPT_FAILED_MARKER.equals(plaintext)
                    && !WAITING_FOR_KEY_MARKER.equals(plaintext)) {
                cacheRecvPlaintext(messageId, plaintext);
            }
            return plaintext;
        }
    }

    private String decryptEnvelope(String maybeEnvelope, String groupId, String senderUid) {
        try {
            JSONObject env = new JSONObject(maybeEnvelope.substring(ENC_PREFIX.length()));
            String kid = env.getString("kid");
            int n = env.getInt("n");

            SenderKeyState s = loadRecvState(groupId, senderUid);
            if (s == null || !s.keyId.equals(kid)) {
                // Either we haven't received this sender's key yet, or they
                // rotated and we haven't picked up the new one — trigger an
                // opportunistic re-fetch so the NEXT message (or a re-render)
                // has a chance to succeed without waiting for the chat to
                // be reopened.
                importIncomingSenderKeys(groupId, FirebaseUtils.getCurrentUid());
                // WHATSAPP-LEVEL SELF-HEAL: the re-fetch above only helps if
                // the sender's key is ALREADY sitting in our mailbox. If we
                // lost our local Sender Key state (reinstall/clear data/new
                // device) the sender's device has no idea — it thinks it
                // already delivered this key to us and will never resend on
                // its own (see requestSenderKeyResend's class doc above).
                // Ask them directly; rate-limited so repeated stuck messages
                // from the same sender don't spam requests.
                requestSenderKeyResend(groupId, senderUid);
                return WAITING_FOR_KEY_MARKER;
            }

            byte[] messageKey;
            String skipKey = kid + "|" + n;
            if (s.skippedKeys.containsKey(skipKey)) {
                messageKey = s.skippedKeys.remove(skipKey);
            } else if (n < s.n) {
                // Already consumed and not in the skip cache — can't rewind a
                // one-way hash chain, and it wasn't kept around either.
                return DECRYPT_FAILED_MARKER;
            } else {
                while (s.n < n) {
                    byte[][] ckAndMk = kdfChainKey(s.chainKey);
                    s.chainKey = ckAndMk[0];
                    if (s.skippedKeys.size() >= MAX_SKIPPED_KEYS_PER_SENDER) {
                        java.util.Iterator<String> it = s.skippedKeys.keySet().iterator();
                        if (it.hasNext()) { it.next(); it.remove(); }
                    }
                    s.skippedKeys.put(kid + "|" + s.n, ckAndMk[1]);
                    s.n++;
                }
                byte[][] ckAndMk = kdfChainKey(s.chainKey);
                s.chainKey = ckAndMk[0];
                messageKey = ckAndMk[1];
                s.n = n + 1;
            }

            saveRecvState(groupId, senderUid, s);

            byte[] iv = Base64.decode(env.getString("iv"), Base64.NO_WRAP);
            byte[] ct = Base64.decode(env.getString("ct"), Base64.NO_WRAP);
            byte[] padded = aesGcmDecrypt(messageKey, iv, ct);
            return new String(unpadPlaintext(padded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "decryptGroupMessage failed for group " + groupId + " sender " + senderUid, e);
            return DECRYPT_FAILED_MARKER;
        }
    }

    public static boolean isEncrypted(String text) {
        return text != null && text.startsWith(ENC_PREFIX);
    }

    /**
     * Same "our own outgoing message echoes back through the Firebase
     * listener as ciphertext" situation as 1:1 — cache the plaintext we
     * already know at send time and restore it instead of trying to run our
     * own send-only chain backwards. See GroupChatActivity#sendText().
     */
    public void cacheOwnPlaintext(String messageId, String plaintext) {
        if (messageId == null || plaintext == null) return;
        SharedPreferences.Editor editor = sentCachePrefs.edit();
        editor.putString("pt_" + messageId, plaintext);
        List<String> order = loadCacheOrder(sentCachePrefs);
        order.remove(messageId);
        order.add(messageId);
        while (order.size() > MAX_CACHED_PLAINTEXT) {
            editor.remove("pt_" + order.remove(0));
        }
        editor.putString("order", String.join(",", order));
        editor.apply();
    }

    @Nullable
    public String takeOwnPlaintext(String messageId) {
        if (messageId == null) return null;
        return sentCachePrefs.getString("pt_" + messageId, null);
    }

    private void cacheRecvPlaintext(String messageId, String plaintext) {
        SharedPreferences.Editor editor = recvCachePrefs.edit();
        editor.putString("pt_" + messageId, plaintext);
        List<String> order = loadCacheOrder(recvCachePrefs);
        order.remove(messageId);
        order.add(messageId);
        while (order.size() > MAX_CACHED_PLAINTEXT) {
            editor.remove("pt_" + order.remove(0));
        }
        editor.putString("order", String.join(",", order));
        editor.apply();
    }

    private List<String> loadCacheOrder(SharedPreferences prefs) {
        String raw = prefs.getString("order", "");
        List<String> list = new ArrayList<>();
        if (!raw.isEmpty()) for (String id : raw.split(",")) if (!id.isEmpty()) list.add(id);
        return list;
    }

    /** Wipe everything on logout. */
    public void clearAllKeys() {
        ownKeyPrefs.edit().clear().apply();
        recvKeyPrefs.edit().clear().apply();
        membersPrefs.edit().clear().apply();
        sentCachePrefs.edit().clear().apply();
        recvCachePrefs.edit().clear().apply();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Persisted state
    // ═════════════════════════════════════════════════════════════════════

    private static class SenderKeyState {
        String keyId;
        byte[] chainKey;
        int n;
        Map<String, byte[]> skippedKeys = new LinkedHashMap<>();
    }

    private SenderKeyState getOrCreateOwnSenderKey(String groupId) throws Exception {
        SenderKeyState existing = loadOwnState(groupId);
        if (existing != null) return existing;
        SenderKeyState fresh = new SenderKeyState();
        fresh.keyId = UUID.randomUUID().toString().substring(0, 8);
        fresh.chainKey = randomBytes(32);
        fresh.n = 0;
        saveOwnState(groupId, fresh);
        return fresh;
    }

    @Nullable
    private SenderKeyState loadOwnState(String groupId) {
        String raw = ownKeyPrefs.getString("own_" + groupId, null);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            SenderKeyState s = new SenderKeyState();
            s.keyId = o.getString("kid");
            s.chainKey = Base64.decode(o.getString("ck"), Base64.NO_WRAP);
            s.n = o.getInt("n");
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private void saveOwnState(String groupId, SenderKeyState s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("kid", s.keyId);
        o.put("ck", b64(s.chainKey));
        o.put("n", s.n);
        ownKeyPrefs.edit().putString("own_" + groupId, o.toString()).apply();
    }

    @Nullable
    private SenderKeyState loadRecvState(String groupId, String senderUid) {
        String raw = recvKeyPrefs.getString("recv_" + groupId + "_" + senderUid, null);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            SenderKeyState s = new SenderKeyState();
            s.keyId = o.getString("kid");
            s.chainKey = Base64.decode(o.getString("ck"), Base64.NO_WRAP);
            s.n = o.getInt("n");
            JSONObject skip = o.optJSONObject("skip");
            if (skip != null) {
                java.util.Iterator<String> keys = skip.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    s.skippedKeys.put(k, Base64.decode(skip.getString(k), Base64.NO_WRAP));
                }
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private void saveRecvState(String groupId, String senderUid, SenderKeyState s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("kid", s.keyId);
        o.put("ck", b64(s.chainKey));
        o.put("n", s.n);
        JSONObject skip = new JSONObject();
        for (Map.Entry<String, byte[]> e : s.skippedKeys.entrySet()) skip.put(e.getKey(), b64(e.getValue()));
        o.put("skip", skip);
        recvKeyPrefs.edit().putString("recv_" + groupId + "_" + senderUid, o.toString()).apply();
    }

    private List<String> loadKnownMembers(String groupId) {
        String raw = membersPrefs.getString("members_" + groupId, "");
        List<String> list = new ArrayList<>();
        if (!raw.isEmpty()) for (String uid : raw.split(",")) if (!uid.isEmpty()) list.add(uid);
        return list;
    }

    private void saveKnownMembers(String groupId, List<String> members) {
        membersPrefs.edit().putString("members_" + groupId, String.join(",", members)).apply();
    }

    private List<String> loadDistributedTo(String groupId, String keyId) {
        String raw = membersPrefs.getString("dist_" + groupId + "_" + keyId, "");
        List<String> list = new ArrayList<>();
        if (!raw.isEmpty()) for (String uid : raw.split(",")) if (!uid.isEmpty()) list.add(uid);
        return list;
    }

    private void markDistributedTo(String groupId, String keyId, String memberUid) {
        List<String> list = loadDistributedTo(groupId, keyId);
        if (!list.contains(memberUid)) list.add(memberUid);
        membersPrefs.edit().putString("dist_" + groupId + "_" + keyId, String.join(",", list)).apply();
    }

    /** Removes every "who have I sent my key to" record for {@code groupId}'s PREVIOUS keyId(s) — a rotation always starts a fresh keyId, so the new one needs a clean slate rather than inheriting the old key's distribution list. */
    private void clearDistributedTo(String groupId) {
        try {
            Map<String, ?> all = membersPrefs.getAll();
            String prefix = "dist_" + groupId + "_";
            SharedPreferences.Editor editor = membersPrefs.edit();
            for (String key : all.keySet()) {
                if (key.startsWith(prefix)) editor.remove(key);
            }
            editor.apply();
        } catch (Exception e) {
            Log.w(TAG, "clearDistributedTo failed for " + groupId + ": " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Crypto primitives (same constructions as E2EEncryptionManager)
    // ═════════════════════════════════════════════════════════════════════

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

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private String sha256Short(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(10, hash.length); i++) sb.append(String.format("%02x", hash[i] & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
