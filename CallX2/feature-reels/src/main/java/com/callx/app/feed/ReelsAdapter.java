package com.callx.app.feed;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.callx.app.feed.ReelPlayerFragment;
import com.callx.app.models.ReelModel;

import java.util.ArrayList;
import java.util.List;

/**
 * ReelsAdapter — backs the vertical Reels ViewPager2 feed.
 *
 * ── Mini Games card injection ────────────────────────────────────────────
 * ONE "Mini Games" card appears at position 3 (after the first 3 reels).
 * After that, reels continue normally — feed is infinite.
 *
 * Feed layout: R R R G R R R R R R R ... (infinite)
 *   positions:  0 1 2 3 4 5 6 7 ...
 *
 * Position-to-reel-index mapping:
 *   position < 3  → reelIndex = position          (reels 0, 1, 2)
 *   position == 3 → Game Card (no reel)
 *   position > 3  → reelIndex = position - 1      (reels 3, 4, 5 ...)
 */
public class ReelsAdapter extends FragmentStateAdapter {

    /** Game card appears once, after this many reels. */
    private static final int GAMES_CARD_POSITION = 3;

    private final List<ReelModel> reels = new ArrayList<>();
    private boolean gamesCardsEnabled = false;

    /**
     * ★ BUG FIX — this is what made "Watch more reels" (and switchFeed's
     * For You ↔ Following toggle) land on the WRONG reel.
     *
     * getItemId() below is position-based (position + 1), by design — see
     * that method's doc for why reelId-based ids don't work with this
     * feed's infinite-scroll wraparound. The old comment on setReels()
     * claimed a full reset "correctly invalidates every id at once", but
     * notifyDataSetChanged() alone does NOT do that: FragmentStateAdapter
     * only drops a fragment whose id no longer satisfies containsItem() —
     * and since id=1 is still "contained" after setReels()/prependReel()
     * (the new list still has ≥1 item), the OLD fragment that was already
     * showing at id=1 was kept and reused as-is, still bound to whatever
     * reel it was originally created for. So `openReelInFeed()`'s
     * `currentList.add(0, reel); adapter.setReels(currentList);` correctly
     * put the target reel at position 0 in the data — but ViewPager2 kept
     * displaying the stale fragment that used to live at position 0,
     * instead of a fresh one bound to the new reel. Same root cause made
     * switchFeed() risk showing stale For-You fragments after switching to
     * Following whenever the two lists were long enough to keep old ids
     * "contained".
     *
     * Fix: fold a generation counter into every id, bumped only on a
     * structural reset (setReels/prependReel — anything that changes what
     * reel a given position used to mean). A stale id's epoch no longer
     * matches, containsItem() returns false, FragmentStateAdapter tears
     * down the old fragment and createFragment() runs fresh for that
     * position — so it's finally bound to the reel actually at that index
     * now. addReels() (pure tail-append, used by infinite-scroll pagination)
     * deliberately does NOT bump this — every existing position still means
     * the same reel it always did, so those fragments correctly stay put
     * and pagination doesn't pay for a full-feed refresh it doesn't need.
     */
    private long idEpoch = 0L;

    public ReelsAdapter(FragmentActivity fa) {
        super(fa);
    }

    public ReelsAdapter(Fragment fragment) {
        super(fragment);
    }

    /**
     * Enables/disables the single "Mini Games" card at position 3.
     * Off by default so other screens (HashtagReels, SingleReel, etc.) are unaffected.
     */
    public void setGamesCardsEnabled(boolean enabled) {
        if (this.gamesCardsEnabled != enabled) {
            this.gamesCardsEnabled = enabled;
            notifyDataSetChanged();
        }
    }

    public void setReels(List<ReelModel> newReels) {
        reels.clear();
        reels.addAll(newReels);
        idEpoch++; // structural reset — see idEpoch doc above
        notifyDataSetChanged();
    }

    public void addReels(List<ReelModel> more) {
        int insertAt = getItemCount(); // adapter position before new items
        reels.addAll(more);
        // +1 offset because of the game card slot when enabled and already visible
        int adapterInsertAt = (gamesCardsEnabled && reels.size() - more.size() >= GAMES_CARD_POSITION)
                ? insertAt : insertAt;
        notifyItemRangeInserted(adapterInsertAt, more.size());
    }

    public void prependReel(ReelModel reel) {
        reels.add(0, reel);
        idEpoch++; // shifts every existing item's position — see idEpoch doc above
        notifyDataSetChanged();
    }

    /** Get the ReelModel for an adapter position (must not be a game-card position). */
    public ReelModel get(int position) {
        return reels.get(toReelIndex(position));
    }

    /**
     * True only at position GAMES_CARD_POSITION (position 3).
     * Game card appears exactly once.
     */
    public boolean isGamesCardPosition(int position) {
        if (!gamesCardsEnabled) return false;
        return position == GAMES_CARD_POSITION;
    }

    /**
     * Converts adapter position → index in the reels list.
     *   position 0,1,2 → reelIndex 0,1,2
     *   position 3     → game card (do not call for game card positions)
     *   position 4,5,6 → reelIndex 3,4,5
     */
    public int toReelIndex(int position) {
        if (!gamesCardsEnabled) return position;
        if (position < GAMES_CARD_POSITION) return position;
        // position > GAMES_CARD_POSITION (skip the one game card slot)
        return position - 1;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (isGamesCardPosition(position)) {
            return GamesCardFragment.newInstance();
        }
        return ReelPlayerFragment.newInstance(reels.get(toReelIndex(position)));
    }

    @Override
    public int getItemCount() {
        if (reels.isEmpty()) return 0;
        if (!gamesCardsEnabled) return reels.size();
        // If we have at least 3 reels, add 1 extra slot for the game card
        if (reels.size() >= GAMES_CARD_POSITION) {
            return reels.size() + 1;
        }
        return reels.size();
    }

    /**
     * Stable per-ADAPTER-POSITION id (not per-reelId). Unlimited scroll means
     * the same reel can legitimately appear more than once in this list once
     * the feed wraps around (see ReelsFragment.loadMoreReels()) — hashing
     * reelId would then hand two different positions the same id, which
     * FragmentStateAdapter requires to be unique and breaks ViewPager2
     * (duplicate item ids / the wrong fragment reused at the wrong
     * position). Position is always unique and stable here: the list is
     * append-only except for the rare full reset (setReels/prependReel),
     * which now bumps idEpoch — see its doc — so every id from before the
     * reset stops matching containsItem() and gets recreated fresh instead
     * of silently reusing stale content at its new position.
     */
    @Override
    public long getItemId(int position) {
        if (isGamesCardPosition(position)) return -1000L;
        return (idEpoch << 32) | (position + 1L);
    }

    @Override
    public boolean containsItem(long itemId) {
        if (itemId == -1000L) return gamesCardsEnabled && reels.size() >= GAMES_CARD_POSITION;
        long epoch = itemId >>> 32;
        if (epoch != idEpoch) return false; // id from before a structural reset — force recreate
        long pos = (itemId & 0xFFFFFFFFL) - 1L;
        return pos >= 0 && pos < getItemCount() && !isGamesCardPosition((int) pos);
    }
}
