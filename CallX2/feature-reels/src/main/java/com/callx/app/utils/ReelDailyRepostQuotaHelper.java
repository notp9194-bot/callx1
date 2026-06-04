package com.callx.app.utils;

import androidx.annotation.NonNull;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ReelDailyRepostQuotaHelper — Enforces a per-user daily repost limit.
 *
 * Firebase path: userRepostQuota/{uid}/{yyyy-MM-dd} = count (int)
 *
 * Rules:
 *  • Regular users:   50 reposts / day
 *  • Verified users: 200 reposts / day (users/{uid}/isVerified == true)
 *  • Quota resets at midnight UTC (date-key rolls over automatically)
 *  • Atomic Firebase transaction prevents race conditions
 *  • Fail-open: on Firebase error, allows repost rather than blocking
 *
 * Usage:
 *   ReelDailyRepostQuotaHelper.checkAndIncrement(myUid, (allowed, count, limit) -> {
 *       if (!allowed) showQuotaErrorToast(count, limit);
 *       else proceedWithRepost();
 *   });
 */
public class ReelDailyRepostQuotaHelper {

    public static final int QUOTA_DEFAULT  = 50;
    public static final int QUOTA_VERIFIED = 200;

    public interface QuotaCallback {
        /** @param allowed true if repost is within quota (and count was incremented)
         *  @param currentCount the count AFTER this attempt
         *  @param limit the user's daily limit */
        void onResult(boolean allowed, int currentCount, int limit);
    }

    /**
     * Checks quota and — if allowed — atomically increments the daily count.
     * Calls callback on the main thread.
     */
    public static void checkAndIncrement(@NonNull String uid, @NonNull QuotaCallback callback) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        DatabaseReference quotaRef = FirebaseUtils.db()
            .getReference("userRepostQuota").child(uid).child(today);

        // Check verified status first to determine limit
        FirebaseUtils.getUserRef(uid).child("isVerified").get().addOnCompleteListener(task -> {
            boolean isVerified = task.isSuccessful()
                && Boolean.TRUE.equals(task.getResult().getValue(Boolean.class));
            int limit = isVerified ? QUOTA_VERIFIED : QUOTA_DEFAULT;

            quotaRef.runTransaction(new Transaction.Handler() {
                @NonNull @Override
                public Transaction.Result doTransaction(@NonNull MutableData current) {
                    Integer count = current.getValue(Integer.class);
                    if (count == null) count = 0;
                    if (count >= limit) return Transaction.success(current); // over limit — no increment
                    current.setValue(count + 1);
                    return Transaction.success(current);
                }

                @Override
                public void onComplete(DatabaseError error, boolean committed,
                                       DataSnapshot snapshot) {
                    if (error != null) {
                        // Firebase error — fail open so user isn't silently blocked
                        callback.onResult(true, 0, limit);
                        return;
                    }
                    Integer finalCount = snapshot != null ? snapshot.getValue(Integer.class) : null;
                    if (finalCount == null) finalCount = 1;
                    // If count > limit the transaction didn't increment — repost is blocked
                    boolean allowed = finalCount <= limit;
                    callback.onResult(allowed, finalCount, limit);
                }
            });
        });
    }

    /**
     * Read-only quota status — for UI display (e.g., "42/50 reposts today").
     */
    public static void getQuotaStatus(@NonNull String uid, @NonNull QuotaCallback callback) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        FirebaseUtils.db()
            .getReference("userRepostQuota").child(uid).child(today).get()
            .addOnSuccessListener(snap -> {
                Integer count = snap.getValue(Integer.class);
                if (count == null) count = 0;
                callback.onResult(count < QUOTA_DEFAULT, count, QUOTA_DEFAULT);
            })
            .addOnFailureListener(e -> callback.onResult(true, 0, QUOTA_DEFAULT));
    }
}
