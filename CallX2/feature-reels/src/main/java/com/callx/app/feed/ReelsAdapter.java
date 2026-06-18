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
 * Every GAMES_CARD_INTERVAL (3) real reels, one extra "Mini Games" card is
 * shown (YouTube-Playables-shelf style — see GamesCardFragment), the same
 * way YouTube interleaves a Playables shelf into the home feed.
 *
 * This is done purely via *position math* — the underlying `reels` list
 * is never mutated with fake entries. That keeps ReelModel, Firebase
 * pagination (currentPage/loadMoreReels in ReelsFragment), blocking logic,
 * and getItemId() stability completely untouched; only this adapter's
 * position<->reel-index translation changes.
 *
 * Position layout (1-indexed slots, R = real reel, G = games card):
 *   R R R G R R R G R R R G ...
 * i.e. a games card appears after every 3rd real reel.
 */
public class ReelsAdapter extends FragmentStateAdapter {

    private static final int GAMES_CARD_INTERVAL = 3; // show games card after every 3 reels

    private final List<ReelModel> reels = new ArrayList<>();
    private boolean gamesCardsEnabled = false;

    public ReelsAdapter(FragmentActivity fa) {
        super(fa);
    }

    public ReelsAdapter(Fragment fragment) {
        super(fragment);
    }

    /**
     * Enables/disables the interleaved "Mini Games" card every
     * GAMES_CARD_INTERVAL reels. Off by default so existing screens that
     * reuse this adapter (HashtagReelsActivity, SingleReelPlayerActivity)
     * are unaffected — only the main ReelsFragment feed opts in.
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
        int start = reels.size();
        reels.addAll(more);
        notifyItemRangeInserted(start, more.size());
    }

    public void prependReel(ReelModel reel) {
        reels.add(0, reel);
        notifyItemInserted(0);
    }

    public ReelModel get(int position) {
        int reelIndex = toReelIndex(position);
        return reels.get(reelIndex);
    }

    /** True if the given adapter position is a Mini Games card (not a real reel). */
    public boolean isGamesCardPosition(int position) {
        if (!gamesCardsEnabled) return false;
        int slot = position + 1; // 1-indexed
        return slot % (GAMES_CARD_INTERVAL + 1) == 0;
    }

    /**
     * Converts an adapter position to an index into the `reels` list,
     * accounting for the games cards interleaved every GAMES_CARD_INTERVAL slots.
     * Public so callers (e.g. ReelsFragment's preloaders/pagination) can
     * translate a raw ViewPager2 position into the real reel-list index.
     * If `position` itself is a games-card slot, returns the index of the
     * next upcoming real reel (safe clamp for preloading purposes).
     */
    public int toReelIndex(int position) {
        if (!gamesCardsEnabled) return position;
        int gamesCardsBefore = position / (GAMES_CARD_INTERVAL + 1);
        return position - gamesCardsBefore;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (isGamesCardPosition(position)) {
            return GamesCardFragment.newInstance();
        }
        int reelIndex = toReelIndex(position);
        return ReelPlayerFragment.newInstance(reels.get(reelIndex));
    }

    @Override
    public int getItemCount() {
        if (reels.isEmpty()) return 0;
        if (!gamesCardsEnabled) return reels.size();
        int gamesCards = reels.size() / GAMES_CARD_INTERVAL;
        return reels.size() + gamesCards;
    }

    @Override
    public long getItemId(int position) {
        if (isGamesCardPosition(position)) {
            // Stable negative id per games-card slot — never collides with a
            // reelId.hashCode() (which ReelModel.reelId always produces via
            // String#hashCode, effectively never landing on these reserved values)
            // and stays consistent across notifyDataSetChanged() since it's
            // derived purely from position, not list contents.
            return -1000L - position;
        }
        int reelIndex = toReelIndex(position);
        String id = reels.get(reelIndex).reelId;
        return id != null ? id.hashCode() : position;
    }

    @Override
    public boolean containsItem(long itemId) {
        if (itemId <= -1000L) return true; // games-card slots are always considered present
        for (ReelModel r : reels) {
            if (r.reelId != null && r.reelId.hashCode() == itemId) return true;
        }
        return false;
    }
}
