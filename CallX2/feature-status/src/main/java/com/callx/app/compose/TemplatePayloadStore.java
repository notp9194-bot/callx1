package com.callx.app.compose;

import android.content.Intent;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

/**
 * Sticker JSON can be bigger than what is safe in an Intent extra (Binder transaction limit). Small payloads still travel
 * as a normal extra; big ones are parked here (in memory, 4 newest) and only a short token is put in the Intent, so the
 * receiver gets the poster's music / text / stickers instead of silently losing them.
 */
final class TemplatePayloadStore {
    /** Same cut-off the callers always used for a plain extra. */
    static final int INTENT_MAX = 100_000;
    private static final String REF_SUFFIX = "Ref";
    private static final LruCache<String, String> STORE = new LruCache<>(4);

    private TemplatePayloadStore() { }

    /** Puts {@code value} on the intent: as a plain extra when small, else parked + token under {@code key + "Ref"}. */
    static void put(@NonNull Intent i, @NonNull String key, @Nullable String value) {
        if (value == null || value.isEmpty()) return;
        if (value.length() < INTENT_MAX) { i.putExtra(key, value); return; }
        final String token = UUID.randomUUID().toString();
        synchronized (STORE) { STORE.put(token, value); }
        i.putExtra(key + REF_SUFFIX, token);
    }

    /** Reads what {@link #put} stored (plain extra first, then the parked value). Null when absent / process was recreated. */
    @Nullable
    static String get(@NonNull Intent i, @NonNull String key) {
        final String plain = i.getStringExtra(key);
        if (plain != null) return plain;
        final String token = i.getStringExtra(key + REF_SUFFIX);
        if (token == null) return null;
        synchronized (STORE) { return STORE.get(token); }   // not removed: the activity may be recreated (rotation)
    }
}
