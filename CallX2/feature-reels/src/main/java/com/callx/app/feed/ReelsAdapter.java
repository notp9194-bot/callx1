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

    @Override
    public long getItemId(int position) {
        if (isGamesCardPosition(position)) return -1000L;
        String id = reels.get(toReelIndex(position)).reelId;
        return id != null ? id.hashCode() : position;
    }

    @Override
    public boolean containsItem(long itemId) {
        if (itemId == -1000L) return true;
        for (ReelModel r : reels) {
            if (r.reelId != null && r.reelId.hashCode() == itemId) return true;
        }
        return false;
    }
}
