package com.callx.app.feed;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

/**
 * HomeFeedAutoplayPolicy — resolves the user's "Autoplay" preference for the
 * Reels Home feed.
 *
 * ReelFeedSettingsActivity has always written users/{uid}/feedSettings/autoplay
 * as one of "Always" / "Wi-Fi Only" / "Off", but nothing ever read it back:
 * the Home feed autoplayed unconditionally, so picking "Off" or "Wi-Fi Only"
 * changed nothing. This reads that same value and turns it into a per-attach
 * decision, re-evaluated against the live connection each time a card becomes
 * active (so a walk out of Wi-Fi range stops autoplay on the next card rather
 * than only after an app restart).
 */
public class HomeFeedAutoplayPolicy {

    public enum Mode { ALWAYS, WIFI_ONLY, OFF }

    private volatile Mode mode = Mode.ALWAYS;

    /** Reads the saved preference. {@code onLoaded} runs once, success or not. */
    public void load(@Nullable String myUid, @Nullable Runnable onLoaded) {
        if (myUid == null) { if (onLoaded != null) onLoaded.run(); return; }
        FirebaseUtils.getUserRef(myUid).child("feedSettings").child("autoplay")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    mode = parse(snap.getValue(String.class));
                    if (onLoaded != null) onLoaded.run();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (onLoaded != null) onLoaded.run();
                }
            });
    }

    public Mode mode() { return mode; }

    /** True when a newly active card is allowed to start playing by itself. */
    public boolean shouldAutoplay(@Nullable Context ctx) {
        switch (mode) {
            case OFF:       return false;
            case WIFI_ONLY: return isUnmetered(ctx);
            default:        return true;
        }
    }

    /** Same strings ReelFeedSettingsActivity writes; anything else = Always. */
    private static Mode parse(@Nullable String value) {
        if (value == null) return Mode.ALWAYS;
        if ("Off".equalsIgnoreCase(value))        return Mode.OFF;
        if ("Wi-Fi Only".equalsIgnoreCase(value)
                || "WiFi Only".equalsIgnoreCase(value)) return Mode.WIFI_ONLY;
        return Mode.ALWAYS;
    }

    /** Wi-Fi / Ethernet (or any connection the system reports as unmetered). */
    public static boolean isUnmetered(@Nullable Context ctx) {
        if (ctx == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            if (caps == null) return false;
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (Exception e) {
            return false;
        }
    }
}
