package com.callx.app.chatlist;

import android.content.Context;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * ChatListLayoutManager — v88 custom LinearLayoutManager.
 *
 * TWO CRITICAL OVERRIDES vs the stock LinearLayoutManager:
 *
 * ① supportsPredictiveItemAnimations() = false
 * ─────────────────────────────────────────────
 * Stock LLM returns true → RecyclerView runs TWO layout passes (pre-layout +
 * post-layout) on every insert/remove so it can animate items into their new
 * positions. For a live chat list driven by Firebase, where new messages arrive
 * every few seconds, this doubles the layout work on the UI thread on every
 * update.
 *
 * Since we already set rv.setItemAnimator(null), the second pass is wasted work.
 * Returning false here eliminates it entirely.
 *
 * ② getExtraLayoutSpace() = screenHeight
 * ───────────────────────────────────────
 * Stock LLM only lays out items that are currently VISIBLE in the viewport.
 * When the user scrolls quickly, the RV must layout new items ON THE UI THREAD
 * before they appear — this is a leading cause of "scroll stutter" even when
 * onDraw() is fast.
 *
 * By returning screenHeight, we ask the LLM to keep one extra screen of rows
 * laid out BEYOND the viewport in both directions. These rows are measured,
 * positioned, and ready to draw — the UI thread does zero layout work when
 * they scroll into view. Telegram's list views use this same trick (they call
 * it "extra layout space").
 *
 * Memory cost: ~10–15 extra VHs are kept live (handled by setItemViewCacheSize).
 * CPU trade-off: slightly more layout work at idle, but zero jank during scroll.
 */
public class ChatListLayoutManager extends LinearLayoutManager {

    private final int extraSpacePx;

    public ChatListLayoutManager(Context context) {
        super(context);
        extraSpacePx = getScreenHeight(context);
    }

    /**
     * Disable predictive animations → only one layout pass per data change.
     * Required when ItemAnimator is null (avoids wasted pre-layout pass).
     */
    @Override
    public boolean supportsPredictiveItemAnimations() {
        return false;
    }

    /**
     * Pre-layout one extra screen height beyond the viewport in each direction.
     * Rows that are about to scroll into view are already measured + positioned.
     * The UI thread does zero layout work when they appear — butter scroll.
     */
    @Override
    protected int getExtraLayoutSpace(RecyclerView.State state) {
        return extraSpacePx;
    }

    /**
     * Enable measurement caching — avoids re-measuring items whose size hasn't
     * changed (which is every item in our fixed-height chat list).
     */
    @Override
    public boolean isMeasurementCacheEnabled() {
        return true;
    }

    private static int getScreenHeight(Context ctx) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(dm);
            return dm.heightPixels;
        }
        // Safe fallback: 2160px (FHD+ portrait)
        return 2160;
    }
}
