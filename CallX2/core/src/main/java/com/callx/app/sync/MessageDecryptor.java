package com.callx.app.sync;

import android.content.Context;

import com.callx.app.models.Message;
import com.callx.app.utils.E2EEncryptionManager;

/**
 * GAP FIX (#6 — repository sync vs. realtime listener not unified):
 *
 * Before this class, the exact same 1:1 E2EE "decrypt a Message the moment
 * it comes off Firebase" logic existed as two hand-written copies:
 *
 *   - ChatRepository#decryptIfNeeded  (delta sync / preload — background)
 *   - ChatActivity#decryptIncomingIfNeeded (live ChildEventListener — foreground)
 *
 * Both copies had to independently get the same subtle rule right: an
 * incoming message we sent ourselves is ciphertext we can't decrypt in
 * reverse, so it must be swapped for the plaintext cached at send time
 * instead of run through the ratchet. Two copies of that rule is exactly
 * the kind of thing that quietly drifts apart over time (one gets a fix,
 * the other doesn't) — which is the real risk the "not fully unified"
 * gap was pointing at, more than any single active bug today.
 *
 * This class is now the ONLY place that rule is implemented. Both callers
 * delegate to it. E2EEncryptionManager#decrypt()/takeOwnPlaintext() are
 * already idempotent per messageId (see their own docs), so it's safe for
 * this to be called more than once for the same message across different
 * sync paths (e.g. CallxMessagingService on FCM receipt, ChatRepository's
 * cold delta sync, and ChatActivity's live listener can all legitimately
 * see the same message) — whichever call lands first "wins" the one-time
 * ratchet step and everyone else gets the cached plaintext back.
 *
 * Scope: 1:1 chat text only, same as the two originals — group messages
 * (m.isGroup) use a completely different sender-key scheme
 * (GroupE2EManager) and are intentionally left untouched here.
 */
public final class MessageDecryptor {

    private MessageDecryptor() {}

    /**
     * Decrypts {@code m.text} in place if it's an E2EE ratchet envelope.
     * No-op for non-text messages, plaintext text, and group messages.
     *
     * @param ctx        any Context (application context is fine — used only
     *                   to reach E2EEncryptionManager's per-user keystore)
     * @param m          the message just received from Firebase; mutated in place
     * @param currentUid the signed-in user's uid, used to detect our own
     *                   outgoing message echoing back down a listener
     */
    public static void decryptIfNeeded(Context ctx, Message m, String currentUid) {
        if (m == null) return;
        decryptText(ctx, m, currentUid);
        // After the text on purpose: the sender encrypts the text first, the quote thumbnail second.
        decryptReplyThumb(ctx, m, currentUid);
        // v923: predecode the quote thumb into the UI bitmap pool before the row ever binds
        final ReplyThumbHook hook = replyThumbHook;
        final String pt = m.replyToThumbBase64;
        if (hook != null && pt != null && !pt.isEmpty() && !E2EEncryptionManager.isEncrypted(pt)) {
            try { hook.onReplyThumb(pt); } catch (Throwable ignored) { }
        }
    }

    /** Set by feature-chat (core can't see the adapter). Called off-main with plain base64. */
    public interface ReplyThumbHook { void onReplyThumb(String plainB64); }
    public static volatile ReplyThumbHook replyThumbHook;

    /**
     * The reply-quote thumbnail (Message#replyToThumbBase64) travels as its own ratchet envelope
     * for 1:1 chats (see ChatMessageSender#firebasePushMessage). Swap it back to plain base64 here,
     * before the message reaches Room / the adapter. Own echo -> plaintext cached at send time.
     * Undecryptable -> null (no thumb tile), never a garbage decode.
     */
    private static void decryptReplyThumb(Context ctx, Message m, String currentUid) {
        final String t = m.replyToThumbBase64;
        if (t == null || m.isGroup || m.id == null || !E2EEncryptionManager.isEncrypted(t)) return;
        final E2EEncryptionManager mgr = E2EEncryptionManager.getInstance(ctx);
        final String key = m.id + ":rt";
        if (m.senderId != null && m.senderId.equals(currentUid)) {
            m.replyToThumbBase64 = mgr.takeOwnPlaintext(key);   // null if evicted -> no thumb
            return;
        }
        String pt = mgr.decrypt(t, m.senderId, key);
        m.replyToThumbBase64 = (pt == null || E2EEncryptionManager.DECRYPT_FAILED_MARKER.equals(pt)) ? null : pt;
    }

    private static void decryptText(Context ctx, Message m, String currentUid) {
        if (m.text == null || m.isGroup) return;
        if (!E2EEncryptionManager.isEncrypted(m.text)) return;

        if (m.senderId != null && m.senderId.equals(currentUid)) {
            // Our own message echoing back (chat resync on reopen, reconnect,
            // multi-device, etc.) — what's stored server-side for it is ALWAYS
            // ciphertext, sealed with our own send chain, which this device
            // cannot run backwards. Restore the plaintext cached at send time.
            String cached = E2EEncryptionManager.getInstance(ctx).takeOwnPlaintext(m.id);
            m.text = (cached != null) ? cached : "🔒 Sent message";
            return;
        }

        m.text = E2EEncryptionManager.getInstance(ctx).decrypt(m.text, m.senderId, m.id);
    }
}
