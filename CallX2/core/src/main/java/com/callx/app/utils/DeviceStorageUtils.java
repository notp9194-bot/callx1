package com.callx.app.utils;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

/**
 * GAP FIX (#3 — "history prune hoti hai, offline old messages nahi milte"):
 *
 * Message-history pruning (see MessageDao#pruneOldMessages) used to run
 * unconditionally — every single chat open (ChatActivity/GroupChatActivity,
 * 10s after onCreate) hard-DELETEd everything beyond the last 500 rows for
 * that chat, and the periodic heavy SyncWorker pass did the same at 200 for
 * every chat in the account. That's a genuine offline-first regression: a
 * chat with real history gets silently capped every time it's opened,
 * whether or not the device actually needs the space — so scrolling back
 * past that point later needs a network fetch (MessageRemoteMediator),
 * which doesn't work at all if the device happens to be offline right then.
 *
 * WhatsApp's own behavior is the reference point here: local chat history
 * is NOT capped by message count at all — it only shrinks when the user
 * explicitly manages storage, or (as a last resort) when the device is
 * genuinely low on space. This class provides that "is it actually
 * necessary" gate. Pruning call sites should check this FIRST and skip
 * entirely when storage isn't under real pressure — see
 * ChatRepository#pruneOldMessagesIfLowStorage.
 */
public final class DeviceStorageUtils {

    private static final String TAG = "DeviceStorageUtils";

    /**
     * Threshold below which we consider the device "low on storage" and
     * therefore willing to prune old chat history to free space. Chosen to
     * roughly track Android's own storage-low broadcast threshold
     * (typically ~250MB on modern devices) with some headroom, rather than
     * inventing an unrelated number — the goal is "about to actually run
     * into trouble," not "tidy up proactively."
     */
    private static final long LOW_STORAGE_THRESHOLD_BYTES = 300L * 1024 * 1024; // 300MB

    private DeviceStorageUtils() {}

    /**
     * True only when free space on the filesystem holding app data is
     * genuinely low. Fails safe: any error reading storage stats returns
     * false (never prune history over a stat failure we can't interpret).
     */
    public static boolean isDeviceStorageLow(Context ctx) {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long freeBytes = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
            return freeBytes < LOW_STORAGE_THRESHOLD_BYTES;
        } catch (Exception e) {
            Log.w(TAG, "Could not read device storage stats — treating as not-low", e);
            return false;
        }
    }
}
