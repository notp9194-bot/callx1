package com.callx.app.utils;

import android.util.LruCache;

import androidx.annotation.Nullable;

/**
 * v878 — decrypted E2E video envelope (ThumbHash "b" + inline JPEG poster "jt") keyed by messageId.
 *
 * Why: on a received E2E video the poster lives INSIDE the encrypted envelope, so the bind used to
 * wait for an async ratchet-decrypt before it had anything to draw -> empty box first, poster later
 * (2 canvas records). The mapper/warm path now decrypts off-main BEFORE the row binds and parks the
 * result here; the bind then reads it synchronously and sets the poster inside the first bind pass.
 *
 * Memory only (never persisted). The same plaintext is already held by E2EEncryptionManager's
 * per-message decrypt cache, so this adds no new exposure. Small LRU; best-effort.
 */
public final class VideoEnvelopeCache {
    private VideoEnvelopeCache() {}

    private static final LruCache<String, MediaE2ECrypto.KeyEnvelope> L1 = new LruCache<>(48);

    @Nullable
    public static MediaE2ECrypto.KeyEnvelope peek(@Nullable String messageId) {
        if (messageId == null || messageId.isEmpty()) return null;
        return L1.get(messageId);
    }

    public static void put(@Nullable String messageId, @Nullable MediaE2ECrypto.KeyEnvelope env) {
        if (messageId == null || messageId.isEmpty() || env == null) return;
        L1.put(messageId, env);
    }
}
