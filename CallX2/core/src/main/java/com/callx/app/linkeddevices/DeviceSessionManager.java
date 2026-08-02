package com.callx.app.linkeddevices;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.callx.app.utils.Constants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * Single source of truth for "whose Firebase data should this install show".
 *
 * Normal installs: effective uid == the signed-in FirebaseAuth uid (unchanged
 * behaviour — every existing screen keeps working exactly as before).
 *
 * Companion installs (after scanning a "Link a Device" QR code from a primary
 * phone): this install signs in to Firebase with its OWN anonymous uid, but
 * all chat/reel/x/status/call data is read & written under the PRIMARY
 * account's uid instead — that is what makes messages sent on the primary
 * phone show up here in real time and vice versa, exactly like WhatsApp
 * Linked Devices. The primary account's Firebase rules grant this anonymous
 * uid access by listing it under users/{primaryUid}/linkedDevices/{myUid}.
 *
 * Every call site in the app that used to do:
 *     FirebaseAuth.getInstance().getCurrentUser().getUid()
 * now calls:
 *     DeviceSessionManager.getInstance().getEffectiveUid()
 * which transparently returns the right uid for both cases.
 */
public final class DeviceSessionManager {

    private static final String TAG = "DeviceSessionManager";
    private static final String PREFS = "device_session_prefs";
    private static final String KEY_MODE = "mode"; // "primary" (default) or "companion"
    private static final String KEY_LINKED_PRIMARY_UID = "linked_primary_uid";
    private static final String KEY_LINKED_PRIMARY_NAME = "linked_primary_name";

    private static DeviceSessionManager sInstance;

    private Context appContext;
    private SharedPreferences prefs;
    private DatabaseReference revocationWatchRef;
    private ValueEventListener revocationListener;

    public interface RevocationListener {
        /** Called when the primary device removes this companion. Data access must stop. */
        void onDeviceUnlinked();
    }

    private DeviceSessionManager() {}

    public static synchronized DeviceSessionManager getInstance() {
        if (sInstance == null) sInstance = new DeviceSessionManager();
        return sInstance;
    }

    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ── Mode ────────────────────────────────────────────────────────────────

    public boolean isCompanionMode() {
        ensureInit();
        return prefs.getString(KEY_MODE, "primary").equals("companion");
    }

    /** uid of the ORIGINAL account this companion device is linked to (null if not companion). */
    public String getLinkedPrimaryUid() {
        ensureInit();
        return prefs.getString(KEY_LINKED_PRIMARY_UID, null);
    }

    public String getLinkedPrimaryName() {
        ensureInit();
        return prefs.getString(KEY_LINKED_PRIMARY_NAME, "");
    }

    /**
     * The uid every chat/reel/x/status/call repository should use for reading
     * and writing data. This is the ONLY method the rest of the app needs.
     */
    public String getEffectiveUid() {
        ensureInit();
        if (isCompanionMode()) {
            String linked = getLinkedPrimaryUid();
            if (linked != null && !linked.isEmpty()) return linked;
        }
        return getOwnAuthUid();
    }

    /** The uid this install is actually signed into Firebase Auth with (never the linked one). */
    public String getOwnAuthUid() {
        return FirebaseAuth.getInstance().getCurrentUser() == null
            ? null : FirebaseAuth.getInstance().getCurrentUser().getUid();
    }

    /** Called by ScanQrToLinkActivity once the primary device approves the pairing. */
    public void enterCompanionMode(String primaryUid, String primaryName) {
        ensureInit();
        prefs.edit()
            .putString(KEY_MODE, "companion")
            .putString(KEY_LINKED_PRIMARY_UID, primaryUid)
            .putString(KEY_LINKED_PRIMARY_NAME, primaryName == null ? "" : primaryName)
            .apply();
        watchForRevocation(null);
    }

    /** Called on the companion device when it wants to unlink itself, or after a remote revoke. */
    public void exitCompanionMode() {
        ensureInit();
        prefs.edit().clear().apply();
        stopRevocationWatch();
    }

    // ── Revocation watch (companion side) ───────────────────────────────────

    /**
     * Starts listening at users/{primaryUid}/linkedDevices/{myUid}. If the primary
     * device removes this entry (revoke from Linked Devices list), we fire the
     * listener so the caller can force a logout / show a "device unlinked" screen.
     */
    public void watchForRevocation(RevocationListener listener) {
        if (!isCompanionMode()) return;
        String primaryUid = getLinkedPrimaryUid();
        String myUid = getOwnAuthUid();
        if (primaryUid == null || myUid == null) return;

        stopRevocationWatch();
        revocationWatchRef = FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("users").child(primaryUid).child("linkedDevices").child(myUid);
        revocationListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists() && isCompanionMode()) {
                    Log.w(TAG, "Companion link revoked by primary device");
                    exitCompanionMode();
                    if (listener != null) listener.onDeviceUnlinked();
                }
            }
            @Override public void onCancelled(DatabaseError error) { }
        };
        revocationWatchRef.addValueEventListener(revocationListener);
    }

    public void stopRevocationWatch() {
        if (revocationWatchRef != null && revocationListener != null) {
            revocationWatchRef.removeEventListener(revocationListener);
        }
        revocationWatchRef = null;
        revocationListener = null;
    }

    private void ensureInit() {
        if (prefs == null) {
            throw new IllegalStateException(
                "DeviceSessionManager.init(context) must be called once from CallxApp.onCreate() before use");
        }
    }
}
