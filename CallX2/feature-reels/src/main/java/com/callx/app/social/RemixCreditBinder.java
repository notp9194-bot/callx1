package com.callx.app.social;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.models.ReelModel;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

/**
 * v790 — "Remix with @owner ❤" credit strip under a remix reel's owner row. Tapping it opens the original post.
 * If the original creator removed this remix (remixBlocks/{original}/{remix}), it reads "Original removed by the
 * creator" and no longer links to the original.
 */
public final class RemixCreditBinder {
    private RemixCreditBinder() {}

    /** v791 — feeds rebind the same card many times while scrolling: remember the "blocked?" answer for a minute
     *  so the strip does not fire one Firebase read per bind. key = original|remix → {blocked, readAtMs}. */
    private static final long BLOCK_TTL_MS = 60_000L;
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> BLOCK_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void bind(@Nullable final TextView strip, @Nullable final ReelModel reel) {
        if (strip == null) return;
        if (reel == null || !reel.isRemix || reel.remixOf == null || reel.remixOf.isEmpty()) {
            strip.setVisibility(View.GONE);
            strip.setTag(null);
            strip.setOnClickListener(null);
            return;
        }
        final Context ctx = strip.getContext();
        final String key = reel.reelId + "|" + reel.remixOf;
        final String name = reel.remixOfOwnerName == null ? "" : reel.remixOfOwnerName;
        strip.setTag(key);
        strip.setVisibility(View.VISIBLE);
        strip.setText(ctx.getString(R.string.rc_credit_remix, name) + " ❤");
        strip.setOnClickListener(v -> open(ctx, reel.remixOf));

        // blocked by the original creator?
        final String cacheKey = reel.remixOf + "|" + reel.reelId;
        final long[] cached = BLOCK_CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached[1] < BLOCK_TTL_MS) {
            if (cached[0] == 1L) showRemoved(strip);
            return;
        }
        FirebaseUtils.db().getReference("remixBlocks").child(reel.remixOf).child(String.valueOf(reel.reelId))
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        BLOCK_CACHE.put(cacheKey, new long[]{s.exists() ? 1L : 0L, System.currentTimeMillis()});
                        if (!key.equals(strip.getTag()) || !s.exists()) return;
                        showRemoved(strip);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    private static void showRemoved(@NonNull TextView strip) {
        strip.setText(R.string.rc_credit_removed);
        strip.setOnClickListener(null);
    }

    private static void open(Context ctx, String reelId) {
        try {
            ctx.startActivity(new Intent(ctx, SingleReelPlayerActivity.class)
                    .putExtra(SingleReelPlayerActivity.EXTRA_REEL_ID, reelId));
        } catch (Exception ignored) {}
    }
}
