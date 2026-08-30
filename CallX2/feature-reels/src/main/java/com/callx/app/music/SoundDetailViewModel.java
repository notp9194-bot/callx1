package com.callx.app.music;

import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * PERF FIX (#4, rotation reload): SoundDetailFragment.onViewCreated() used
 * to unconditionally call loadSoundData()/loadReelsForSound()/
 * loadRelatedSounds()/loadCreatorProfile() on every single onViewCreated()
 * — including a rotation, which recreates the Fragment (and every one of
 * its plain instance fields — reelItems, lastReelKey, hasMoreReels, the
 * adapter, etc.) from scratch. SoundDetailCache's TTL made those reloads
 * cheap in the sense of "no network round-trip on a warm cache", but they
 * were never free: each one is still a Firebase (or cache) read plus,
 * critically, a full grid rebuild starting from an EMPTY reelItems list —
 * which is what threw away scroll position, since by the time the async
 * read/callback repopulated the grid, the framework had already tried (and
 * failed, against an empty adapter) to restore rvReels' saved scroll state.
 *
 * A Fragment's ViewModelStore is retained by the framework across a
 * config-change recreate — same fragment identity, brand-new Fragment
 * object, but the SAME ViewModel instance handed back by
 * `new ViewModelProvider(this).get(SoundDetailViewModel.class)`. So this
 * class exists purely as a plain state cache (no LiveData, nobody observes
 * it — SoundDetailFragment just reads/writes fields directly) holding
 * everything SoundDetailFragment needs to repaint itself without touching
 * Firebase again: see SoundDetailFragment#restoreFromViewModel().
 *
 * NOT a replacement for SoundDetailCache — that cache is process-wide and
 * keyed by soundId/genre/creatorUid, shared across every screen and every
 * Fragment instance, and is still what a genuinely fresh open (first open,
 * or a related-sound hop that replaces this Fragment with a new instance
 * entirely) reads from. This ViewModel is scoped to ONE Fragment instance's
 * identity and only ever helps the same screen survive a rotation.
 */
public class SoundDetailViewModel extends ViewModel {

    /** Which sound this cached state belongs to. SoundDetailFragment compares
     *  this against its own (bundle-args) soundId to tell "this is the same
     *  screen surviving a rotation" apart from "this is a fresh open that
     *  happens to have grabbed a stale/mismatched vm" — should only ever
     *  matter as a defensive guard, since a genuinely new Fragment instance
     *  gets a genuinely new, empty ViewModel. */
    public String soundId;

    // ── Sound node (sounds/{id} or musicLibrary/{id} fallback) ─────────────
    public boolean soundDataLoaded = false;
    public boolean fromMusicLibrary = false;
    public String  soundUrl, previewAudioUrl, coverUrl;
    public int     durationMs;
    // long, not int: mirrors SoundDetailCache.SoundNodeEntry's reelCount/
    // totalSaves fields (Long, read straight off Firebase's reel_count/
    // total_saves nodes) — an int here was the "possible lossy conversion
    // from long to int" build break.
    public long    reelCount, totalSaves;
    public Long    trendingRank;
    public boolean isTrending, isOriginal, isVerified;

    // ── Creator row ──────────────────────────────────────────────────────
    public boolean creatorLoaded = false;
    public String  creatorUid, creatorName, creatorPhoto;

    // ── Reels grid + pagination cursor ──────────────────────────────────
    public boolean reelsLoaded = false;
    public final List<SoundDetailActivity.ReelThumbItem> reelItems = new ArrayList<>();
    public String  lastReelKey;
    public boolean hasMoreReels = true;

    // ── Related sounds row ───────────────────────────────────────────────
    public boolean relatedLoaded = false;
    public final List<SoundDetailActivity.RelatedItem> relatedItems = new ArrayList<>();

    // ── Scroll position ──────────────────────────────────────────────────
    /** NestedScrollView#getScrollY() captured in onDestroyView(), restored
     *  in restoreFromViewModel(). -1 = never captured yet. */
    public int savedScrollY = -1;

    /** True once at least one of the four loads above has landed — used by
     *  onViewCreated() to decide fast-path restore vs. a fresh set of loads. */
    public boolean hasAnyData() {
        return soundDataLoaded || reelsLoaded || relatedLoaded || creatorLoaded;
    }
}
