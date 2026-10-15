package com.callx.app.utils;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

/**
 * ServerClock — Firebase server time (WhatsApp-style): status expiry must not depend on a
 * wrong phone clock. now() = device time + .info/serverTimeOffset. Until the first offset
 * lands (or when offline) the offset is 0, i.e. plain device time — never worse than before.
 */
public final class ServerClock {
    private static volatile long offsetMs = 0L;
    private static volatile boolean started = false;

    private ServerClock() {}

    /** Server-corrected "now" in epoch millis. Starts the offset listener lazily. */
    public static long now() {
        if (!started) start();
        return System.currentTimeMillis() + offsetMs;
    }

    private static synchronized void start() {
        if (started) return;
        started = true;
        try {
            FirebaseUtils.db().getReference(".info/serverTimeOffset")
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        Double v = s.getValue(Double.class);
                        if (v != null) offsetMs = v.longValue();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { /* keep last offset */ }
                });
        } catch (Exception ignored) { /* Firebase not ready — device time */ }
    }
}
