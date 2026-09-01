package com.callx.app.music;
import com.callx.app.utils.AlertDialogStyler;
import com.callx.app.reels.databinding.FragmentSoundDetailBinding;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.util.LruCache;

import com.callx.app.cache.SoundDetailCache;
import com.callx.app.cache.MutualFollowersCache;
import com.bumptech.glide.Glide;
import de.hdodenhof.circleimageview.CircleImageView;
import com.bumptech.glide.ListPreloader;
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;
import com.callx.app.player.ReelThermalManager;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.profile.ReelPeekPreviewController;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.MediaSwipeReplyCloseHelper;
import com.callx.app.utils.VerifiedBadgeUtils;
import com.callx.app.utils.SwipeAwareFrameLayout;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.shape.CornerFamily;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Query;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * SoundDetailFragment — Single source of truth for Sound Detail screen.
 *
 * Ek hi Fragment, do jagah use hota hai:
 *   • SoundDetailActivity    (isSheet = false → back arrow, no drag handle)
 *   • SoundDetailSheetFragment (isSheet = true  → X icon, drag handle visible)
 *
 * Koi duplicate logic nahi — sab kuch yahan hai.
 */
public class SoundDetailFragment extends Fragment implements Player.Listener {

    // ── Args ──────────────────────────────────────────────────────────────────
    private static final String ARG_SOUND_ID    = "sound_id";
    private static final String ARG_TITLE       = "title";
    private static final String ARG_ARTIST      = "artist";
    private static final String ARG_COVER_URL   = "cover_url";
    private static final String ARG_SOUND_URL   = "sound_url";
    private static final String ARG_DURATION_MS = "duration_ms";
    private static final String ARG_GENRE       = "genre";
    private static final String ARG_BPM         = "bpm";
    private static final String ARG_CREATOR_UID = "creator_uid";
    private static final String ARG_PREVIEW_URL = "preview_audio_url";
    private static final String ARG_IS_SHEET    = "is_sheet";

    private static final int REELS_PAGE_SIZE = 12;
    private static final int REQUEST_TRIM_SOUND = 702;

    /**
     * ULTRA optimization: buildStaticWaveform() used to re-run
     * SoundWaveformView#seedStatic()'s Random-based bar generation every
     * single time a SoundDetailFragment instance was created for a given
     * sound — including reopening the exact same sound's detail screen
     * multiple times in one session. The 36 pseudo-random heights are
     * fully determined by soundId, so they're cached here once per
     * process (static — shared across every SoundDetailFragment
     * instance) instead of being recomputed on each open. LruCache caps
     * memory at 64 sounds' worth of float[36] (~9KB total) and evicts the
     * least-recently-opened sound first, so it "frees" naturally when the
     * cache fills rather than growing unbounded.
     */
    private static final LruCache<String, float[]> WAVEFORM_CACHE = new LruCache<>(64);

    /**
     * PERF (ULTRA): WAVEFORM_CACHE above never shrank on its own — an
     * android.util.LruCache only evicts on its own count/size cap, which
     * has nothing to do with actual system memory pressure. Small (~9KB
     * max here), but the codebase's convention for every other L2 cache
     * (see AvatarL2MemoryCache / ReelsAvatarL2Cache) is that ANY process-
     * wide cache hooks ComponentCallbacks2#onTrimMemory rather than relying
     * solely on its own cap — so this follows the same "survive MODERATE,
     * clear at COMPLETE" rule. Registered lazily, once per process, the
     * first time any SoundDetailFragment view is created (see
     * ensureWaveformCacheTrimRegistered() in onViewCreated) — an
     * AtomicBoolean guards against the many SoundDetailFragment instances
     * that come and go in a session (bottom sheet reopens, related-sound
     * hops) trying to register more than once.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean WAVEFORM_TRIM_REGISTERED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void ensureWaveformCacheTrimRegistered(Context ctx) {
        if (!WAVEFORM_TRIM_REGISTERED.compareAndSet(false, true)) return;
        ctx.getApplicationContext().registerComponentCallbacks(new ComponentCallbacks2() {
            @Override public void onTrimMemory(int level) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                    int evicted = WAVEFORM_CACHE.size();
                    WAVEFORM_CACHE.evictAll();
                    Log.d("SoundDetailFragment", "WAVEFORM_CACHE TRIM_MEMORY_COMPLETE — cleared " + evicted + " entries");
                } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                    // Halve rather than wipe — MODERATE fires routinely on
                    // ordinary backgrounding, and this cache is tiny enough
                    // (~9KB max) that a full wipe here buys nothing but
                    // forces the next batch of sound opens to recompute
                    // bars that were fine a second ago. trimToSize() doesn't
                    // change maxSize(), so the cache can grow back to 64
                    // once memory pressure passes.
                    WAVEFORM_CACHE.trimToSize(WAVEFORM_CACHE.maxSize() / 2);
                }
                // Below MODERATE (UI_HIDDEN/BACKGROUND/RUNNING_*): no-op,
                // same reasoning AvatarL2MemoryCache documents — this cache
                // is cheap enough to just leave alone under routine signals.
            }
            @Override public void onLowMemory() {
                WAVEFORM_CACHE.evictAll();
            }
            @Override public void onConfigurationChanged(Configuration newConfig) { }
        });
    }

    // ── Rotation-reload fix (#4) ────────────────────────────────────────────
    // Fragment-scoped ViewModel — Android retains a Fragment's ViewModelStore
    // across a config-change recreate (same fragment identity, new instance),
    // so this instance survives rotation even though every field on
    // SoundDetailFragment itself is thrown away and rebuilt from scratch.
    // Used purely as a state cache (no LiveData) — see SoundDetailViewModel's
    // class doc and restoreFromViewModel() below for the full rationale.
    private SoundDetailViewModel vm;

    // ── Host callback (Activity → finish, Sheet → dismiss) ────────────────────
    private Runnable onCloseListener;

    /** Activity ya Sheet parent set karta hai — close action batane ke liye */
    public void setOnCloseListener(Runnable listener) { this.onCloseListener = listener; }

    // ── State ─────────────────────────────────────────────────────────────────
    private String  soundId, soundTitle, soundUrl, artist, coverUrl, genre, previewAudioUrl;
    private int     durationMs, bpm;
    private int     trimStartMs = 0, trimEndMs = 0;   // ✂ user-picked range from ReelMusicTrimActivity
    private int     pendingUseTarget = -1;             // -1=none  0=camera  1=gallery  (set by dialog before trim)
    private boolean isSheet       = false;
    private boolean isSaved       = false;
    private boolean isPlaying     = false;
    private boolean isPreparing   = false;
    private boolean userSeeking   = false;
    private boolean retried       = false;
    private boolean triedFallbackUrl = false; // already swapped preview↔full-quality URL once
    private boolean skipPreviewUrl   = false; // preview URL failed — force full-quality soundUrl
    private boolean miniPlayerActive = false;
    private boolean autoPlayAttempted = false; // Instagram-style: fire the auto-play exactly once per screen open, whenever the playback URL first becomes available (bundle args or async Firebase fetch)

    private String  creatorUid, creatorName, creatorPhoto;
    // Gap #2: guards resolveCreatorAfterSoundLoad() so it (and the
    // loadCreatorProfile() bundle-args fast-path) only ever resolve the
    // creator once — see resolveCreatorAfterSoundLoad()'s doc.
    private boolean creatorProfileResolutionAttempted = false;

    // ── Pagination ────────────────────────────────────────────────────────────
    private String  lastReelKey        = null;
    private boolean isLoadingMoreReels = false;
    private boolean hasMoreReels       = true;
    private ChildEventListener soundReelsLiveListener = null;
    /**
     * PERF/SAFETY (ULTRA): explicit single-flight guard for
     * attachSoundReelsLiveListener(), in addition to the
     * `soundReelsLiveListener != null` check already inside it. That null
     * check alone relies on the ChildEventListener field being assigned
     * before attachSoundReelsLiveListener() can be re-entered — true on a
     * normal single call, but not guaranteed if loadMoreReelsForSound()
     * ever gets re-triggered (e.g. a debounced pagination Runnable still
     * queued, or a Firebase page callback landing) before that assignment
     * completes for this same Fragment instance. compareAndSet() below
     * claims the attach atomically and immediately, so a second call in
     * that window backs off instead of racing a second
     * addChildEventListener() onto the same query. Reset in
     * detachLiveListener(), which onDestroyView() already calls — so a
     * rotation/fragment-recreate always gets a clean flag on the new
     * instance (this field is per-instance, not static) and a real
     * detach-then-reattach (e.g. related-sound hop reusing lastReelKey)
     * is still allowed.
     */
    private final java.util.concurrent.atomic.AtomicBoolean reelsLiveListenerAttached =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // ── Grid layout manager (UserReelsActivity-style swipe-aware) ─────────────
    private SoundDetailGridLayoutManager soundReelsLayoutManager;

    /**
     * PERF (ULTRA): static/process-wide RecycledViewPool for rvReels, shared
     * across every SoundDetailFragment instance — same pattern as
     * HomeFragment#SUGGESTED_CREATORS_TILE_POOL / UserReelsActivity#gridSharedViewPool
     * elsewhere in this codebase.
     *
     * WHY THIS MATTERS HERE SPECIFICALLY: RelatedAdapter's related-sound
     * click (see bindViews() below) doesn't update the current screen in
     * place — it REPLACES this Fragment with a brand-new SoundDetailFragment
     * instance for the next sound (loadRelatedSounds()'s onClick doc says
     * the same). That means a brand-new rvReels + a brand-new
     * ReelThumbAdapter on every hop, and — without a shared pool — a
     * brand-new, per-instance RecyclerView.RecycledViewPool too, so every
     * single grid cell had to be inflated from scratch (item_sound_reel_thumb.xml)
     * on every hop even though the previous instance's now-discarded pool
     * was sitting on a full set of already-inflated, now-unused ViewHolders.
     * A RecycledViewPool keys purely by (adapter viewType, not adapter
     * identity) — see UserSeriesGridAdapter's doc on this same pattern — so
     * a NEW ReelThumbAdapter instance can freely draw from VHs a PREVIOUS
     * instance returned, as long as the view type matches. ReelThumbAdapter
     * has exactly one cell layout (no getItemViewType() override → always
     * type 0), so this is safe: every hop's grid reuses the same pool of
     * already-inflated item_sound_reel_thumb.xml cells instead of paying
     * layout inflation again per cell, per hop.
     *
     * Sized a bit above rvReels' setItemViewCacheSize(12) below — enough to
     * outlive one grid's cache without holding an unbounded number of
     * scrapped views (same reasoning as the sibling pools' explicit caps).
     */
    private static final RecyclerView.RecycledViewPool SOUND_REELS_SHARED_POOL =
        new RecyclerView.RecycledViewPool();
    static {
        SOUND_REELS_SHARED_POOL.setMaxRecycledViews(0 /* ReelThumbAdapter's only view type */, 18);
    }

    // ── Debounced reels pagination (ported from UserReelsActivity) ────────────
    // Calling loadMoreReelsForSound() as soon as the threshold is crossed means
    // it can fire mid-fling — right when we least want a Firebase read. A 120ms
    // debounce means it only runs once the finger/fling has settled. IDLE short-
    // circuits straight to the check so it fires immediately when scrolling stops.
    private static final long REELS_PAGINATION_DEBOUNCE_MS = 120L;
    private final Handler     reelsPaginationHandler  = new Handler(Looper.getMainLooper());
    private final Runnable    reelsPaginationRunnable = this::maybeLoadMoreReels;

    // ── Views ─────────────────────────────────────────────────────────────────
    // ✅ OPT (ULTRA): ViewBinding instead of 48 raw findViewById() calls.
    // Same field set below, just populated from `binding.*` in bindViews()
    // now — inflate + lookup is a compiled/cached path instead of a tree
    // walk per id, and a renamed/removed id now fails the build instead of
    // handing back a silent null at runtime. Nulled in onDestroyView() so
    // the binding (and the view tree it holds) doesn't outlive the Fragment.
    private FragmentSoundDetailBinding binding;
    private View         viewDragHandle;
    private MaterialCardView rootCard;
    private ImageButton  btnBack, btnShare, btnSaveSound, btnMore, btnPlayPause;
    private TextView     tvSoundTitle, tvArtist, tvDuration, tvReelCount,
                         tvTrendingRank, tvSavesCount, tvBpm, tvGenre,
                         tvOriginalBadge, tvIsVerified;
    private TextView     tvAddToProfile;
    private ImageView    ivSoundTitleVerified;
    private View         btnUseSoundCamera, btnUseSoundGallery, btnAddToProfile;
    // "Used by" row — reused UI pattern from UserReelsActivity's mutual
    // followers row, logic = unique owners of reels made with this sound.
    private LinearLayout    layoutSoundUsers;
    private CircleImageView ivSoundUser1, ivSoundUser2, ivSoundUser3;
    private TextView         tvSoundUsers;
    private final List<String> soundUserUidsList = new ArrayList<>();
    // PERF: soundUserUidsList stays a List (order matters — first 3 drive
    // the avatars), but dedupe-checking against it with .contains() is
    // O(n) per item, O(n²) overall for a large reel-set. This companion
    // Set mirrors the same UIDs purely for O(1) "already added?" checks.
    private final HashSet<String> soundUserUidsSeen = new HashSet<>();
    // PERF: computed once from reelItems already fetched for the grid —
    // guards against redoing the (cheap, in-memory) derivation on every
    // subsequent page/live-listener append.
    private boolean soundUsersComputed = false;
    private ImageView    ivSoundCover, ivDiscRing;
    private RecyclerView rvReels, rvRelated;
    private SoundDetailActivity.RelatedAdapter relatedAdapter;
    private ProgressBar  progressBar, progressReelsPagination;
    private View         layoutSoundInfo, layoutReelsHeader;
    private SoundWaveformView waveformView;
    private SeekBar      seekBar;
    private TextView     tvCurrentTime, tvTotalTime;
    private ShimmerFrameLayout shimmerLayout;
    private LinearLayout layoutCreator;
    private ImageView    ivCreatorAvatar;
    private ImageView    ivCreatorVerified;
    private TextView     tvCreatorName;
    private TextView     tvCreatorFollowers;
    private android.widget.Button btnFollowCreator;
    // v318: gradient ring + glow removed (see bindViews()/bg_sound_play_btn) —
    // play button is now a flat solid circle, no ring drawable needed.
    private SwipeAwareFrameLayout layoutMiniPlayer;
    private ImageView    ivMiniCover;
    private TextView     tvMiniTitle;
    private ImageButton  btnMiniPlayPause, btnMiniClose;
    // Swipe-up/down-to-close on the mini player — same MediaSwipeReplyCloseHelper
    // MediaViewerActivity/ReelPeekPreviewController use, no gesture code duplicated.
    // layoutMiniPlayer is a SwipeAwareFrameLayout (see class doc) so it can
    // forward touch events to this helper before btnMiniPlayPause/btnMiniClose
    // get a chance to consume them.
    private MediaSwipeReplyCloseHelper miniPlayerSwipeHelper;
    private View         layoutFloatingActions;
    private TextView     btnFloatingUseAudio, btnFloatingSave;
    private NestedScrollView scrollSoundDetail;

    // ── Data ──────────────────────────────────────────────────────────────────
    private final List<SoundDetailActivity.ReelThumbItem> reelItems    = new ArrayList<>();
    private final List<SoundDetailActivity.RelatedItem>   relatedItems = new ArrayList<>();
    private SoundDetailActivity.ReelThumbAdapter reelThumbAdapter;
    // ULTRA (UserReelsActivity pattern): long-press on a grid cell opens the
    // same muted-looping "mini player" peek popup as the profile reel grid,
    // instead of jumping straight into the full-screen player.
    private com.callx.app.profile.ReelPeekPreviewController peekController;
    // Feature-parity fix: cached so an owner long-press doesn't hit Firebase
    // on every single peek — loaded once (lazily, on first owner long-press
    // in this screen instance) and kept in sync locally on pin/unpin/delete.
    // PERF: pinned-reel-id resolution/caching now lives in SoundDetailCache
    // (shared, survives across related-sound hops) — see
    // ensurePinnedReelIdLoaded(). cachedPinnedReelId stays as a local mirror
    // so buildOwnerPeekOptions()/confirmDeleteOwnedReel() can read it
    // synchronously without an extra cache lookup.
    private String  cachedPinnedReelId;

    // ── Player ────────────────────────────────────────────────────────────────
    private ExoPlayer exoPlayer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // PERF (ULTRA): DiffUtil.calculateDiff() for the reel grid is O(N) and
    // was running synchronously on the main thread in sortAndApplyReelItems().
    // Fine while the grid is a page or two, but once multiple pages have
    // paginated in (plus live adds from attachSoundReelsLiveListener()) that
    // calculateDiff() call itself can eat a frame. Moved to this single
    // background thread — only dispatchUpdatesTo(), which must touch the
    // adapter, still runs on mainHandler.
    private final java.util.concurrent.ExecutorService diffExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor();

    // ── Thermal (gap #3: waveform + ExoPlayer weren't thermal-aware, unlike
    // Home/Post feed's use of ReelThermalManager) ─────────────────────────────
    private ReelThermalManager thermalManager;
    // Re-evaluated on every thermal change while playing, so the waveform
    // drops out of its animation loop the moment the device goes HOT, and
    // resumes animating if it cools back down — not just at play-start.
    private final Runnable thermalChangeListener = this::onThermalChanged;

    // ── Animations ────────────────────────────────────────────────────────────
    private ObjectAnimator            discAnimator;

    // FIX #BG-SEEK-LOOP: 300ms polling loop earlier only re-checked `isPlaying`
    // before rescheduling itself. If the flag stayed true across an async edge
    // (e.g. Fragment backgrounded via Recents right as a play callback landed),
    // the loop kept firing on the main thread indefinitely — same class of bug
    // Reels' player already guards against. isResumed() is checked on every
    // tick too, so it self-terminates the moment this Fragment is no longer
    // in the foreground, independent of whatever `isPlaying` says.
    //
    // ✅ OPT (ULTRA): this used to be a Handler.postDelayed(this, ...)
    // self-rescheduling chain. postDelayed() schedules each next tick
    // relative to "now" *after* the previous tick already finished running —
    // under any main-thread jank (a GC pause, a big layout/measure pass) the
    // whole chain drifts later and later and never catches back up, since
    // there's nothing re-syncing it to an external clock. A ValueAnimator's
    // update listener is driven by Choreographer instead — re-synced to the
    // display's actual vsync signal every single frame, so it can't drift,
    // and it coalesces with whatever else is already drawing that frame
    // instead of firing on its own independent timer.
    //
    // THERMAL: interval still scales with device thermal state exactly as
    // before — 300ms normally, 800ms once ReelThermalManager reports HOT
    // (same signal startWaveAnimation() already uses to freeze the
    // waveform). The animator's update listener still fires every frame
    // (~16ms, cheap — no player calls happen there), but the actual
    // position read + seekbar/time update only runs once the throttled
    // interval has actually elapsed, gated by an elapsedRealtime() check
    // against seekIntervalMs() — same real work, same cadence, same CPU
    // cost per second as the old Handler version, just frame-synced instead
    // of timer-scheduled.
    private ValueAnimator seekAnimator;
    private long lastSeekTickElapsedMs = 0L;

    private void startSeekTicker() {
        if (seekAnimator != null && seekAnimator.isStarted()) return;
        lastSeekTickElapsedMs = 0L; // force an immediate tick on the first frame
        seekAnimator = ValueAnimator.ofFloat(0f, 1f);
        seekAnimator.setDuration(16L); // one frame — INFINITE repeat below is what keeps it ticking
        seekAnimator.setRepeatCount(ValueAnimator.INFINITE);
        seekAnimator.addUpdateListener(a -> onSeekAnimatorFrame());
        seekAnimator.start();
    }

    private void stopSeekTicker() {
        if (seekAnimator != null) {
            seekAnimator.cancel();
            seekAnimator = null;
        }
    }

    private void onSeekAnimatorFrame() {
        if (!isResumed()) { stopSeekTicker(); return; } // hard stop — fragment not foreground
        if (!isPlaying) { stopSeekTicker(); return; }
        long now = SystemClock.elapsedRealtime();
        if (now - lastSeekTickElapsedMs < seekIntervalMs()) return; // thermal-aware throttle
        lastSeekTickElapsedMs = now;
        if (exoPlayer != null && !userSeeking) {
            long pos = exoPlayer.getCurrentPosition();
            long dur = exoPlayer.getDuration();
            if (dur > 0 && seekBar != null)
                seekBar.setProgress((int)(100L * pos / dur));
            if (tvCurrentTime != null) tvCurrentTime.setText(formatMs((int) pos));
        }
    }

    /** 300ms normally; throttled to 800ms on a HOT device — mirrors the
     *  waveform's isThermalHot() gate so seek-bar polling backs off under
     *  thermal load same as everything else in this screen. */
    private long seekIntervalMs() {
        return isThermalHot() ? 800L : 300L;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Factory
    // ─────────────────────────────────────────────────────────────────────────

    /** Activity mode (isSheet = false) */
    public static SoundDetailFragment newInstance(
            String soundId, String title, String artist, String coverUrl,
            String soundUrl, int durationMs, String genre, int bpm,
            String creatorUid, String previewAudioUrl) {
        return newInstance(soundId, title, artist, coverUrl, soundUrl,
                durationMs, genre, bpm, creatorUid, previewAudioUrl, false);
    }

    /** isSheet = true → sheet mode (drag handle + X button) */
    public static SoundDetailFragment newInstance(
            String soundId, String title, String artist, String coverUrl,
            String soundUrl, int durationMs, String genre, int bpm,
            String creatorUid, String previewAudioUrl, boolean isSheet) {
        SoundDetailFragment f = new SoundDetailFragment();
        Bundle b = new Bundle();
        b.putString(ARG_SOUND_ID,    n(soundId));
        b.putString(ARG_TITLE,       n(title));
        b.putString(ARG_ARTIST,      n(artist));
        b.putString(ARG_COVER_URL,   n(coverUrl));
        b.putString(ARG_SOUND_URL,   n(soundUrl));
        b.putInt   (ARG_DURATION_MS, durationMs);
        b.putString(ARG_GENRE,       n(genre));
        b.putInt   (ARG_BPM,         bpm);
        b.putString(ARG_CREATOR_UID, n(creatorUid));
        b.putString(ARG_PREVIEW_URL, n(previewAudioUrl));
        b.putBoolean(ARG_IS_SHEET,   isSheet);
        f.setArguments(b);
        return f;
    }

    private static String n(String s) { return s != null ? s : ""; }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSoundDetailBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle b = getArguments();
        if (b != null) {
            soundId         = b.getString(ARG_SOUND_ID,    "");
            soundTitle      = b.getString(ARG_TITLE,       "");
            artist          = b.getString(ARG_ARTIST,      "");
            coverUrl        = b.getString(ARG_COVER_URL,   "");
            soundUrl        = b.getString(ARG_SOUND_URL,   "");
            durationMs      = b.getInt(ARG_DURATION_MS, 0);
            genre           = b.getString(ARG_GENRE,       "");
            bpm             = b.getInt(ARG_BPM, 0);
            creatorUid      = b.getString(ARG_CREATOR_UID, "");
            previewAudioUrl = b.getString(ARG_PREVIEW_URL, "");
            isSheet         = b.getBoolean(ARG_IS_SHEET, false);
        }

        // PERF (#4, rotation reload): a Fragment's ViewModelStore survives a
        // config-change recreate, so `vm` here is the SAME instance as
        // before rotation whenever this is a config-change recreate of an
        // already-open screen — and a fresh, empty one on a genuine new
        // open (first open, or a related-sound hop replacing this Fragment
        // with a brand-new instance). vm.soundId lets us tell those two
        // cases apart without any extra flag.
        vm = new ViewModelProvider(this).get(SoundDetailViewModel.class);
        boolean restoringAfterRotation =
            !soundId.isEmpty() && soundId.equals(vm.soundId) && vm.hasAnyData();

        bindViews();
        applyMode();          // drag handle + close icon based on isSheet
        ensureWaveformCacheTrimRegistered(requireContext());
        thermalManager = ReelThermalManager.get(requireContext());
        thermalManager.addChangeListener(thermalChangeListener);
        peekController = new com.callx.app.profile.ReelPeekPreviewController(requireActivity());
        populateSoundInfo();
        setupClickListeners();
        checkIfSaved();
        setupReelGridParallax(); // UserReelsActivity-style cover parallax on scroll

        if (restoringAfterRotation) {
            // ── ROTATION FAST-PATH ──────────────────────────────────────
            // Zero Firebase reads, zero grid rebuild-from-empty: repaint
            // straight from vm, synchronously, right here — see
            // restoreFromViewModel()'s doc for why this must run inline in
            // onViewCreated() rather than from any async callback.
            showShimmer(false);
            restoreFromViewModel();
            deriveSoundUsersFromReelItems(); // reelItems already populated above — no network call
        } else {
            vm.soundId = soundId; // claim this vm for this sound (fresh open)
            showShimmer(true);
            loadSoundData();
            loadReelsForSound(true /* fetchInitialPage */);
            loadRelatedSounds();
            loadCreatorProfile();
            // "used by" row derives from reelItems as soon as the first
            // page lands — see finishAppendingPage() → deriveSoundUsersFromReelItems()
        }
    }

    /**
     * ROTATION FIX (#4): repaints the whole screen from SoundDetailViewModel
     * instead of re-running loadSoundData()/loadReelsForSound()/
     * loadRelatedSounds()/loadCreatorProfile(). SoundDetailCache's TTL
     * already made those calls cheap (no network on a warm cache), but they
     * still meant: a Firebase read, `reelItems` rebuilt from an empty list,
     * and the grid rebound from scratch — which is exactly what threw away
     * scroll position on every rotation. This reads back the same fields
     * applySoundsNodeEntry()/applyMusicLibraryEntry()/bindCreatorRow()/
     * finishAppendingPage()/loadRelatedSounds() already wrote into vm as
     * they resolved, so a rotation costs one field-copy pass instead.
     *
     * Runs synchronously from onViewCreated() — not from inside an async
     * Firebase/cache callback — specifically so rvReels already has its
     * items by the time FragmentManager restores the view hierarchy's saved
     * state (scroll offsets included) right after onViewCreated() returns.
     * Restoring the grid asynchronously (the old behavior, since every load
     * call went through an async callback) meant that restore always lost
     * the race against the framework's state-restore, which is what forced
     * the scroll position back to 0 on every rotation even with the cache.
     */
    private void restoreFromViewModel() {
        if (vm.soundDataLoaded) {
            if (vm.durationMs > 0) {
                durationMs = vm.durationMs;
                String s = formatMs(durationMs);
                if (tvDuration  != null) tvDuration.setText(s);
                if (tvTotalTime != null) tvTotalTime.setText(s);
            }
            if (soundUrl.isEmpty()        && vm.soundUrl        != null) soundUrl        = vm.soundUrl;
            if (previewAudioUrl.isEmpty() && vm.previewAudioUrl != null) previewAudioUrl = vm.previewAudioUrl;
            if (coverUrl.isEmpty() && vm.coverUrl != null && !vm.coverUrl.isEmpty()) {
                coverUrl = vm.coverUrl;
                loadCoverImage(coverUrl);
            }
            if (tvReelCount  != null) tvReelCount.setText(formatCount(vm.reelCount) + " Reels");
            if (tvSavesCount != null) {
                tvSavesCount.setText("•  " + formatCount(vm.totalSaves) + " Saves");
                tvSavesCount.setVisibility(View.VISIBLE);
            }
            if (tvTrendingRank != null) {
                if (vm.trendingRank != null && vm.trendingRank > 0 && vm.trendingRank <= 50) {
                    tvTrendingRank.setVisibility(View.VISIBLE);
                    tvTrendingRank.setText("#" + vm.trendingRank + " Trending");
                } else if (vm.isTrending) {
                    tvTrendingRank.setVisibility(View.VISIBLE);
                    tvTrendingRank.setText("Trending");
                } else {
                    tvTrendingRank.setVisibility(View.GONE);
                }
            }
            if (tvOriginalBadge != null) tvOriginalBadge.setVisibility(vm.isOriginal ? View.VISIBLE : View.GONE);
            if (tvIsVerified    != null) tvIsVerified.setVisibility(vm.isVerified  ? View.VISIBLE : View.GONE);
            VerifiedBadgeUtils.bind(ivSoundTitleVerified, vm.isVerified);
        }

        if (vm.creatorLoaded && vm.creatorUid != null && !vm.creatorUid.isEmpty()) {
            creatorUid    = vm.creatorUid;
            creatorName   = vm.creatorName;
            creatorPhoto  = vm.creatorPhoto;
            creatorProfileResolutionAttempted = true;
            bindCreatorRow(creatorUid, creatorName, creatorPhoto);
        }

        if (vm.reelsLoaded) {
            reelItems.clear();
            reelItems.addAll(vm.reelItems);
            lastReelKey  = vm.lastReelKey;
            hasMoreReels = vm.hasMoreReels;
        }
        // Adapter/scroll-listeners/preloader setup only — reelItems is
        // already populated above, so pass fetchInitialPage=false to skip
        // the Firebase page read; still (re)attaches the live listener so
        // anything added to this sound since we last looked still streams in.
        loadReelsForSound(false);

        if (vm.relatedLoaded && !vm.relatedItems.isEmpty()) {
            relatedItems.clear();
            relatedItems.addAll(vm.relatedItems);
            if (rvRelated != null && relatedAdapter != null) {
                relatedAdapter.submitList(relatedItems);
                View sec = binding != null ? binding.layoutRelatedSoundsSection : null;
                if (sec != null) sec.setVisibility(View.VISIBLE);
            }
        }

        updatePlayButtonState();
        if (scrollSoundDetail != null) {
            scrollSoundDetail.post(SoundDetailFragment.this::updateFloatingActionsVisibility);
            if (vm.savedScrollY >= 0) {
                final int y = vm.savedScrollY;
                scrollSoundDetail.post(() -> scrollSoundDetail.scrollTo(0, y));
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // ── Trim result ───────────────────────────────────────────────────
        if (requestCode == REQUEST_TRIM_SOUND && resultCode == android.app.Activity.RESULT_OK && data != null) {
            trimStartMs = data.getIntExtra(com.callx.app.editor.ReelMusicTrimActivity.RESULT_START_MS, 0);
            trimEndMs   = data.getIntExtra(com.callx.app.editor.ReelMusicTrimActivity.RESULT_END_MS, durationMs);
            // ✅ FIX: after trim dialog, auto-launch the pending destination
            int target = pendingUseTarget;
            pendingUseTarget = -1;
            if (target == 0) openCameraWithSound();
            else if (target == 1) openGalleryForVideo();
            return;
        }

        // ── Gallery video pick (request 701) ─────────────────────────────
        // ✅ FIX: was missing entirely — nothing happened when user picked a video
        if (requestCode == 701 && resultCode == android.app.Activity.RESULT_OK
                && data != null && !isGone()) {
            android.net.Uri videoUri = data.getData();
            if (videoUri == null) return;
            Intent i = new Intent(requireContext(), com.callx.app.editor.ReelEditorActivity.class);
            i.putExtra(com.callx.app.editor.ReelEditorActivity.EXTRA_VIDEO_URI, videoUri.toString());
            // ✅ FIX: gallery gives a content:// URI, not a file path. Without this,
            // ReelEditorActivity defaults EXTRA_IS_FILE_PATH to true and tries
            // new File("content://...") — invalid, so the player never loads
            // and the video shows blank in the edit screen.
            i.putExtra(com.callx.app.editor.ReelEditorActivity.EXTRA_IS_FILE_PATH, false);
            if (!soundId.isEmpty())    i.putExtra("selected_sound_id",    soundId);
            if (!soundTitle.isEmpty()) i.putExtra("selected_sound_title", soundTitle);
            if (!soundUrl.isEmpty())   i.putExtra("selected_sound_url",   soundUrl);
            if (!coverUrl.isEmpty())   i.putExtra("selected_sound_cover", coverUrl);
            if (!artist.isEmpty())     i.putExtra("selected_sound_artist", artist);
            if (trimEndMs > trimStartMs) {
                i.putExtra("music_start_ms", trimStartMs);
                i.putExtra("music_end_ms",   trimEndMs);
            }
            startActivity(i);
            if (onCloseListener != null) onCloseListener.run();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isPlaying) pausePlayback();
        if (peekController != null) peekController.dismiss();
    }

    // FIX #BG-SEEK-LOOP: Reels' player is lifecycle-gated (ReelsFragment#onStop
    // removes its listeners/loops unconditionally on background); SoundDetail
    // previously relied only on onPause()'s `if (isPlaying)` check. That's
    // normally enough, but it leaves no second line of defense if `isPlaying`
    // flips true from an async ExoPlayer callback right around the pause edge
    // (e.g. user hits Recents mid-buffer) — the seek loop and player can then
    // keep running until the OS kills the process. onStop() now force-stops
    // both unconditionally, same as the Reels feed does when it backgrounds.
    @Override
    public void onStop() {
        stopSeekTicker();
        if (exoPlayer != null && isPlaying) pausePlayback();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        // PERF (#4, rotation reload): capture scroll offset into vm right
        // before the view tree goes away — restoreFromViewModel() applies
        // it back on the recreated Fragment. Guarded on vm != null since a
        // process-death recreate can, in rare cases, call onDestroyView()
        // before onViewCreated() ever ran (e.g. state restore edge cases).
        if (vm != null && scrollSoundDetail != null) vm.savedScrollY = scrollSoundDetail.getScrollY();
        stopSeekTicker();
        mainHandler.removeCallbacksAndMessages(null);
        // Cancel any pending debounced pagination check (UserReelsActivity pattern)
        reelsPaginationHandler.removeCallbacksAndMessages(null);
        diffExecutor.shutdownNow(); // avoid leaking this background diff thread past this fragment instance
        stopDiscAnimation();
        stopWaveAnimation();
        releasePlayer();
        detachLiveListener();
        if (thermalManager != null) thermalManager.removeChangeListener(thermalChangeListener);
        if (peekController != null) peekController.dismiss();
        if (layoutMiniPlayer != null) layoutMiniPlayer.setSwipeHelper(null);
        miniPlayerSwipeHelper = null;
        // ✅ Nulled last, after everything above has had its chance to use
        // the view fields it backs — same reasoning as any other
        // ViewBinding fragment: the binding (and the view tree under it)
        // must not outlive this Fragment instance.
        binding = null;
        super.onDestroyView();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mode: Activity vs Sheet
    // ─────────────────────────────────────────────────────────────────────────

    private void applyMode() {
        if (isSheet) {
            // Sheet: drag handle dikhao + X icon + top corners rounded
            if (viewDragHandle != null) viewDragHandle.setVisibility(View.VISIBLE);
            if (btnBack != null) btnBack.setImageResource(R.drawable.ic_close);
            applyRootCorners(true);
        } else {
            // Activity: drag handle chhupao + back arrow + flat corners
            if (viewDragHandle != null) viewDragHandle.setVisibility(View.GONE);
            if (btnBack != null) btnBack.setImageResource(R.drawable.ic_arrow_back);
            applyRootCorners(false);
        }
    }

    /** Sheet mode: round only the top-left/top-right corners (bottom stays
     *  flush with the screen edge, same as a standard bottom sheet). Activity
     *  mode: flat, unchanged. MaterialCardView clips its children to this
     *  shape — a plain View's rectangular background can't. */
    private void applyRootCorners(boolean rounded) {
        if (rootCard == null) return;
        float radiusPx = rounded ? 24 * getResources().getDisplayMetrics().density : 0f;
        rootCard.setShapeAppearanceModel(
            rootCard.getShapeAppearanceModel().toBuilder()
                .setTopLeftCorner(CornerFamily.ROUNDED, radiusPx)
                .setTopRightCorner(CornerFamily.ROUNDED, radiusPx)
                .setBottomLeftCorner(CornerFamily.ROUNDED, 0f)
                .setBottomRightCorner(CornerFamily.ROUNDED, 0f)
                .build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View binding
    // ─────────────────────────────────────────────────────────────────────────

    private void bindViews() {
        viewDragHandle    = binding.viewDragHandle;
        rootCard          = binding.getRoot();
        btnBack           = binding.btnSoundBack;
        btnPlayPause      = binding.btnSoundPlayPause;
        btnShare          = binding.btnSoundShare;
        btnMore           = binding.btnSoundMore;
        btnSaveSound      = binding.btnSaveSound;
        tvSoundTitle      = binding.tvSoundTitle;
        tvArtist          = binding.tvSoundArtist;
        // tv_sound_duration has no view in fragment_sound_detail.xml — that id
        // only exists in item_saved_sound.xml / bottom_sheet_sound_detail.xml
        // (pre-existing, unrelated to this refactor). The old
        // v.findViewById(R.id.tv_sound_duration) silently returned null here
        // too; kept explicit so the `if (tvDuration != null)` guards further
        // down keep behaving exactly as before.
        tvDuration        = null;
        tvReelCount       = binding.tvSoundReelCount;
        tvTrendingRank    = binding.tvSoundTrendingRank;
        tvSavesCount      = binding.tvSoundSavesCount;
        tvBpm             = binding.tvSoundBpm;
        tvGenre           = binding.tvSoundGenre;
        tvOriginalBadge   = binding.tvSoundOriginalBadge;
        tvIsVerified      = binding.tvSoundVerifiedBadge;
        ivSoundTitleVerified = binding.ivSoundTitleVerified;
        btnUseSoundCamera = binding.btnUseSoundCamera;
        btnUseSoundGallery= binding.btnUseSoundGallery;
        btnAddToProfile   = binding.btnAddToProfile;
        layoutSoundUsers  = binding.layoutSoundUsers;
        ivSoundUser1      = binding.ivSoundUser1;
        ivSoundUser2      = binding.ivSoundUser2;
        ivSoundUser3      = binding.ivSoundUser3;
        tvSoundUsers      = binding.tvSoundUsers;
        tvAddToProfile    = binding.tvAddToProfile;
        ivSoundCover      = binding.ivSoundCover;
        ivDiscRing        = binding.ivDiscRing;
        rvReels           = binding.rvSoundReels;
        rvRelated         = binding.rvRelatedSounds;
        progressBar       = binding.progressSound;
        progressReelsPagination = binding.progressReelsPagination;
        layoutSoundInfo   = binding.layoutSoundInfo;
        waveformView      = binding.waveformSound;
        seekBar           = binding.seekbarSound;
        tvCurrentTime     = binding.tvSoundCurrentTime;
        tvTotalTime       = binding.tvSoundTotalTime;
        shimmerLayout     = binding.shimmerSoundDetail;
        layoutReelsHeader = binding.layoutReelsHeader;
        layoutCreator     = binding.layoutSoundCreator;
        ivCreatorAvatar   = binding.ivCreatorAvatar;
        ivCreatorVerified = binding.ivCreatorVerified;
        tvCreatorName     = binding.tvCreatorName;
        tvCreatorFollowers = binding.tvCreatorFollowers;
        btnFollowCreator  = binding.btnFollowCreator;
        layoutMiniPlayer  = binding.layoutMiniPlayer;
        ivMiniCover       = binding.ivMiniCover;
        tvMiniTitle       = binding.tvMiniTitle;
        btnMiniPlayPause  = binding.btnMiniPlayPause;
        btnMiniClose      = binding.btnMiniClose;
        setupMiniPlayerSwipeClose();
        layoutFloatingActions = binding.layoutFloatingSoundActions;
        btnFloatingUseAudio   = binding.btnFloatingUseAudio;
        btnFloatingSave       = binding.btnFloatingSave;
        scrollSoundDetail     = binding.scrollSoundDetail;

        if (rvReels != null) {
            // ── UserReelsActivity-style perf setup ─────────────────────────
            soundReelsLayoutManager = new SoundDetailGridLayoutManager(requireContext(), 3);
            soundReelsLayoutManager.setItemPrefetchEnabled(true);
            soundReelsLayoutManager.setInitialPrefetchItemCount(9);
            rvReels.setLayoutManager(soundReelsLayoutManager);

            // ULTRA (from UserReelsActivity): bounds are match_parent / fixed →
            // RecyclerView skips the extra measure pass setHasFixedSize(false) triggers.
            rvReels.setHasFixedSize(true);
            // ULTRA: disable default per-cell fade animation — photo grid cells
            // don't need cross-fade on insert/remove (same choice as UserReelsActivity).
            rvReels.setItemAnimator(null);
            // ULTRA: cache more off-screen ViewHolders so fast flings and
            // fresh-page appends reuse already-bound views instead of re-inflating.
            rvReels.setItemViewCacheSize(12);
            // ULTRA: process-wide shared pool — see SOUND_REELS_SHARED_POOL's
            // doc above for why this specifically helps the related-sound
            // hop case (new Fragment + new rvReels + new adapter, same
            // underlying pool of inflated cells).
            rvReels.setRecycledViewPool(SOUND_REELS_SHARED_POOL);
            // Must stay enabled so NestedScrollView/CoordinatorLayout intercepts
            // vertical events and the header can collapse properly.
            rvReels.setNestedScrollingEnabled(true);

            // Match UserReelsActivity's bordered grid: white RV background + 1dp
            // padding/gap decoration so each square thumbnail shows a thin
            // white border, same as the profile reels grid.
            if (rvReels.getItemDecorationCount() == 0) {
                rvReels.addItemDecoration(
                    new com.callx.app.profile.ReelGridAdapter.WhiteGridDecoration(requireContext()));
            }
        }
        if (rvRelated != null) {
            rvRelated.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            // ULTRA: every row is a fixed 80dp-tall cell (RelatedAdapter
            // .onCreateViewHolder()), so rv_related_sounds's own
            // wrap_content height never actually changes as items are
            // added/swapped — safe to skip RecyclerView's extra measure
            // pass, same reasoning already applied to rvReels above.
            rvRelated.setHasFixedSize(true);
            // ✅ OPT (ULTRA): adapter created once here and reused via
            // submitList() (DiffUtil) from loadRelatedSounds(), instead of a
            // fresh RelatedAdapter + setAdapter() on every load — see
            // RelatedAdapter.submitList() for why that matters.
            relatedAdapter = new SoundDetailActivity.RelatedAdapter(new ArrayList<>(), item -> {
                showMiniPlayer();
                SoundDetailFragment next = SoundDetailFragment.newInstance(
                    item.id, item.title, item.artist, item.coverUrl, item.audioUrl,
                    0, genre, 0, null, null, isSheet);
                next.setOnCloseListener(onCloseListener);
                requireActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .replace(requireView().getId(), next)
                    .addToBackStack(null)
                    .commit();
            });
            rvRelated.setAdapter(relatedAdapter);
        }

        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (fromUser && exoPlayer != null && tvCurrentTime != null) {
                        long dur = exoPlayer.getDuration();
                        if (dur > 0) tvCurrentTime.setText(formatMs((int)(dur * p / 100L)));
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    userSeeking = false;
                    if (exoPlayer != null && !isPreparing) {
                        long dur = exoPlayer.getDuration();
                        if (dur > 0) exoPlayer.seekTo(dur * sb.getProgress() / 100);
                    }
                }
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Guard
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isGone() { return !isAdded() || getContext() == null; }

    // ─────────────────────────────────────────────────────────────────────────
    // Sheet peek-hint scroll — called by SoundDetailSheetFragment
    // ─────────────────────────────────────────────────────────────────────────

    private ObjectAnimator hintScrollUp, hintScrollDown;

    /**
     * ✅ NEW: sheet ke "peek nudge" ke saath saath andar ka content bhi
     * thoda upar scroll karta hai, taaki "Reels with this sound" grid ka
     * hint dikhe — bilkul waise jaise user khud finger se scroll karta hai.
     * Sirf peekHeight badhne se sheet ka container to bada hota tha, lekin
     * andar ka scroll wahi ka wahi rehta tha — is wajah se grid section
     * upar nahi aata tha. Public taaki SoundDetailSheetFragment (parent
     * sheet host) apne nudge timer se ise call kar sake.
     *
     * @param targetScrollY kitna scroll karna hai (px) — sheet host apni
     *                      expanded height ke hisaab se decide karta hai.
     * @param duration      ek taraf ki animation duration (ms).
     * @param holdMs        upar pahunch ke kitni der ruk kar wapas aana hai.
     */
    /**
     * "Reels with this sound" header tak scroll karne ke liye target Y (px)
     * batata hai — sheet host isse peek-nudge ke duration mein use karta
     * hai. Agar header abhi tak layout/visible nahi hua (reels load ho rahe
     * hain), to ek reasonable fallback estimate deta hai.
     */
    public int getReelsHintScrollTarget() {
        if (layoutReelsHeader != null && layoutReelsHeader.getVisibility() == View.VISIBLE
                && layoutReelsHeader.getTop() > 0) {
            return layoutReelsHeader.getTop();
        }
        return Math.round(280 * getResources().getDisplayMetrics().density); // fallback estimate
    }

    public void playReelsHintScroll(int targetScrollY, long duration, long holdMs) {
        if (scrollSoundDetail == null || isGone() || targetScrollY <= 0) return;
        cancelReelsHintScroll();

        int startY = scrollSoundDetail.getScrollY();
        hintScrollUp = ObjectAnimator.ofInt(scrollSoundDetail, "scrollY", startY, targetScrollY);
        hintScrollUp.setDuration(duration);
        hintScrollUp.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        hintScrollUp.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(@NonNull android.animation.Animator animation) {
                if (isGone() || scrollSoundDetail == null) return;
                hintScrollDown = ObjectAnimator.ofInt(scrollSoundDetail, "scrollY",
                        scrollSoundDetail.getScrollY(), startY);
                hintScrollDown.setDuration(duration);
                hintScrollDown.setStartDelay(holdMs);
                hintScrollDown.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                hintScrollDown.start();
            }
        });
        hintScrollUp.start();
    }

    /** User khud drag/scroll kare to nudge turant cancel — sheet host isse call karta hai. */
    public void cancelReelsHintScroll() {
        if (hintScrollUp   != null) hintScrollUp.cancel();
        if (hintScrollDown != null) hintScrollDown.cancel();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shimmer
    // ─────────────────────────────────────────────────────────────────────────

    private void showShimmer(boolean show) {
        if (shimmerLayout != null) {
            shimmerLayout.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) shimmerLayout.startShimmer(); else shimmerLayout.stopShimmer();
        }
        if (layoutSoundInfo   != null) layoutSoundInfo.setVisibility(show ? View.GONE : View.VISIBLE);
        if (layoutReelsHeader != null) layoutReelsHeader.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Populate static info
    // ─────────────────────────────────────────────────────────────────────────

    private void populateSoundInfo() {
        if (tvSoundTitle != null) tvSoundTitle.setText(soundTitle.isEmpty() ? "Unknown Sound" : soundTitle);
        if (tvArtist     != null) tvArtist.setText(artist.isEmpty() ? "• Original Audio" : "• " + artist);

        if (durationMs > 0) {
            String dur = formatMs(durationMs);
            if (tvDuration  != null) tvDuration.setText(dur);
            if (tvTotalTime != null) tvTotalTime.setText(dur);
        }
        if (tvBpm != null) {
            tvBpm.setVisibility(bpm > 0 ? View.VISIBLE : View.GONE);
            if (bpm > 0) tvBpm.setText(bpm + " BPM");
        }
        if (tvGenre != null) {
            tvGenre.setVisibility(!genre.isEmpty() ? View.VISIBLE : View.GONE);
            if (!genre.isEmpty()) tvGenre.setText(genre);
        }
        loadCoverImage(coverUrl);
        buildStaticWaveform();
    }

    /**
     * Ultra optimization: ivCreatorAvatar (38dp) and ivMiniCover (40dp)
     * were both requesting .override(720, 720) from Glide — a fixed size
     * meant for the large screenshot-style ivSoundCover thumbnail, not a
     * ~40dp circular avatar. That's ~18x more pixels decoded and held in
     * memory than the view can ever show, on every screen open / creator
     * switch, for zero visual gain. Sized to a comfortable 48dp @ device
     * density instead — still crisp on high-density screens, a fraction
     * of the decode/memory cost.
     */
    private int avatarDecodePx() {
        return Math.round(48 * getResources().getDisplayMetrics().density);
    }

    private void loadCoverImage(String url) {
        if (ivSoundCover == null || isGone()) return;
        // Rounded-square crop now that the cover is a larger, static
        // screenshot-style thumbnail (previously CircleCrop, when this was
        // a small spinning vinyl disc). Radius bumped 14dp -> 20dp -> 28dp
        // for a noticeably more rounded corner as the cover was sized down.
        //
        // ✅ BUG FIX: corners weren't visible in the app even though
        // RoundedCorners was applied. Root cause: .override(720, 720) forced
        // a SQUARE bitmap decode, RoundedCorners then rounded that square's
        // corners — but the ImageView's centerCrop scaleType afterwards
        // cropped the square down to the view's portrait 99x119 shape,
        // slicing off exactly the rounded corner strips from top/bottom and
        // leaving straight edges. Fix: chain CenterCrop() before
        // RoundedCorners() so Glide crops to the view's aspect ratio FIRST,
        // then rounds the corners of that already-cropped shape — and match
        // the override() size to the view's real aspect instead of a square.
        // Corner radius dialed back down (28dp was too rounded per feedback,
        // almost circular on the 99dp-wide cover) to a more subtle rounded-square.
        int radiusPx = Math.round(16 * getResources().getDisplayMetrics().density);
        if (url != null && !url.isEmpty()) {
            Glide.with(requireContext()).load(url)
                .transform(new CenterCrop(), new RoundedCorners(radiusPx))
                .placeholder(R.drawable.ic_music_note)
                .override(360, 495) // matches the 99:136 view aspect ratio
                .into(ivSoundCover);
        } else {
            ivSoundCover.setImageResource(R.drawable.ic_music_note);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Waveform
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ULTRA optimization: the 36-child-View + 36-ValueAnimator waveform was
     * replaced by a single SoundWaveformView that draws all 36 bars in one
     * onDraw() and is driven by one animator that only calls invalidate()
     * (never setLayoutParams() / requestLayout()). See SoundWaveformView's
     * class doc for the full rationale. These three methods are kept as
     * thin wrappers so every existing call site (populateSoundInfo(),
     * onPlaybackStateChanged(), resumePlayback(), pausePlayback(),
     * onPlayerError(), onDestroyView()) needs no further changes.
     */
    private void buildStaticWaveform() {
        if (waveformView == null || isGone()) return;
        // ULTRA: soundId is the real per-sound key (soundTitle is only a
        // fallback seed source for the rare case a sound arrives without
        // an id). Same soundId → same cached bars, no Random recompute.
        String key = !soundId.isEmpty() ? soundId : ("title:" + soundTitle.hashCode());
        float[] cached = WAVEFORM_CACHE.get(key);
        if (cached != null) {
            waveformView.setStaticHeights(cached);
        } else {
            float[] generated = waveformView.seedStatic(soundTitle.hashCode());
            WAVEFORM_CACHE.put(key, generated);
        }
    }

    private void startWaveAnimation() {
        if (waveformView == null || isGone()) return;
        // Thermal-aware: on a HOT device, skip the per-frame animation loop
        // entirely (see gap #3) — the view still shows a "playing" pose,
        // it just doesn't redraw every frame.
        waveformView.setForceStatic(isThermalHot());
        waveformView.setPlaying(true);
    }

    private void stopWaveAnimation() {
        if (waveformView != null) waveformView.setPlaying(false);
    }

    private boolean isThermalHot() {
        return thermalManager != null && thermalManager.getLevel() == ReelThermalManager.Level.HOT;
    }

    /** ReelThermalManager change callback — only matters while audio is
     *  actually playing; a level change while paused/stopped needs no
     *  reaction since the waveform is already static. */
    private void onThermalChanged() {
        if (isGone() || waveformView == null || !isPlaying) return;
        waveformView.setForceStatic(isThermalHot());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Disc animation
    // ─────────────────────────────────────────────────────────────────────────

    private void startDiscAnimation() {
        // Intentionally a no-op now: the cover is a large static
        // screenshot-style thumbnail (see loadCoverImage/RoundedCorners),
        // not a spinning vinyl disc, so it must stay still during
        // playback. stopDiscAnimation() is left as-is (harmless if
        // discAnimator is null) in case anything still calls it.
    }

    private void stopDiscAnimation() {
        if (discAnimator != null) { discAnimator.cancel(); discAnimator = null; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firebase — Sound data
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * PERF (Firebase read batching, ULTRA): this used to fire its own
     * "sounds/{id}" listener directly, with a second, separate
     * "musicLibrary/{id}" listener as a manual fallback method
     * (loadSoundDataFromMusicLibrary()) — one of 10+ independent
     * addListenerForSingleValueEvent call sites this screen used to fire
     * on every single open, even when re-opening the exact same sound (or
     * hopping through related sounds, which replaces this fragment with a
     * new instance each time). Now routed through SoundDetailCache: the
     * sounds/ → musicLibrary/ fallback cascade lives there once, and the
     * result is cached for SOUND_TTL_MS, so a repeat open of a sound
     * already resolved this session costs zero reads. See
     * SoundDetailCache's class doc for the full rationale.
     */
    private void loadSoundData() {
        if (soundId.isEmpty()) { showShimmer(false); updatePlayButtonState(); return; }

        SoundDetailCache.getInstance().getSoundData(soundId, entry -> {
            if (isGone()) return;

            if (!entry.found) {
                showShimmer(false);
                updatePlayButtonState();
                resolveCreatorAfterSoundLoad();
                return;
            }

            if (entry.fromMusicLibrary) applyMusicLibraryEntry(entry);
            else                        applySoundsNodeEntry(entry);
        });
    }

    /** Binds the screen from a "sounds/{id}" node read (via SoundDetailCache) — same fields loadSoundData() used to read straight off the DataSnapshot. */
    private void applySoundsNodeEntry(SoundDetailCache.SoundNodeEntry snap) {
        // Creator denormalized
        if (creatorUid.isEmpty() && snap.creatorUid != null) creatorUid = snap.creatorUid;
        if (snap.creatorName  != null && !snap.creatorName.isEmpty())  creatorName  = snap.creatorName;
        if (snap.creatorPhoto != null && !snap.creatorPhoto.isEmpty()) creatorPhoto = snap.creatorPhoto;

        if (!creatorUid.isEmpty() && creatorName != null && !creatorName.isEmpty()) {
            bindCreatorRow(creatorUid, creatorName, creatorPhoto);
            sortAndApplyReelItems();
            creatorProfileResolutionAttempted = true; // resolved straight from this read — no separate creator fetch needed
        } else {
            // Name wasn't denormalized on the sound node (or no
            // creatorUid at all) — piggyback off THIS read instead of
            // loadCreatorProfile() firing its own separate listener.
            resolveCreatorAfterSoundLoad();
        }

        if (soundUrl.isEmpty()        && snap.audioUrl        != null) soundUrl        = snap.audioUrl;
        if (previewAudioUrl.isEmpty() && snap.previewAudioUrl != null) previewAudioUrl = snap.previewAudioUrl;
        if (coverUrl.isEmpty()        && snap.coverUrl        != null) { coverUrl = snap.coverUrl; loadCoverImage(coverUrl); }
        if (durationMs <= 0 && snap.durationMs != null && snap.durationMs > 0) {
            durationMs = snap.durationMs;
            String s = formatMs(durationMs);
            if (tvDuration  != null) tvDuration.setText(s);
            if (tvTotalTime != null) tvTotalTime.setText(s);
        }

        if (tvReelCount  != null) tvReelCount.setText(formatCount(snap.reelCount  != null ? snap.reelCount  : 0) + " Reels");
        if (tvSavesCount != null) { tvSavesCount.setText("•  " + formatCount(snap.totalSaves != null ? snap.totalSaves : 0) + " Saves"); tvSavesCount.setVisibility(View.VISIBLE); }

        if (tvTrendingRank != null) {
            Long rank = snap.trendingRank;
            if (rank != null && rank > 0 && rank <= 50)        { tvTrendingRank.setVisibility(View.VISIBLE); tvTrendingRank.setText("#" + rank + " Trending"); }
            else if (Boolean.TRUE.equals(snap.isTrending))     { tvTrendingRank.setVisibility(View.VISIBLE); tvTrendingRank.setText("Trending"); }
            else                                                  tvTrendingRank.setVisibility(View.GONE);
        }
        if (tvOriginalBadge != null) tvOriginalBadge.setVisibility(Boolean.TRUE.equals(snap.isOriginal) ? View.VISIBLE : View.GONE);
        if (tvIsVerified    != null) tvIsVerified.setVisibility(Boolean.TRUE.equals(snap.isVerified)  ? View.VISIBLE : View.GONE);
        VerifiedBadgeUtils.bind(ivSoundTitleVerified, Boolean.TRUE.equals(snap.isVerified));

        showShimmer(false);
        updatePlayButtonState();
        if (scrollSoundDetail != null) scrollSoundDetail.post(SoundDetailFragment.this::updateFloatingActionsVisibility);

        // PERF (#4): snapshot into vm so a later rotation can repaint from
        // here instead of re-reading "sounds/{id}" (even a cached read).
        if (vm != null) {
            vm.soundDataLoaded    = true;
            vm.fromMusicLibrary   = false;
            vm.soundUrl           = snap.audioUrl;
            vm.previewAudioUrl    = snap.previewAudioUrl;
            vm.coverUrl           = snap.coverUrl;
            vm.durationMs         = durationMs;
            vm.reelCount          = snap.reelCount  != null ? snap.reelCount  : 0;
            vm.totalSaves         = snap.totalSaves != null ? snap.totalSaves : 0;
            vm.trendingRank       = snap.trendingRank;
            vm.isTrending         = Boolean.TRUE.equals(snap.isTrending);
            vm.isOriginal         = Boolean.TRUE.equals(snap.isOriginal);
            vm.isVerified         = Boolean.TRUE.equals(snap.isVerified);
        }
    }

    /**
     * Binds the screen from a "musicLibrary/{id}" fallback read (via
     * SoundDetailCache) — fallback source for sounds picked from Trending
     * Audio's Music tab, which don't exist under sounds/.
     *
     * ✅ FIX (kept from the original): creator/Follow row + follow button
     * used to only ever populate when opened as a bottom sheet from an
     * actual reel. Opened full-screen from MusicPicker/SoundSearch/
     * TrendingAudio/etc, the track lives under musicLibrary/ (not sounds/),
     * so the "sounds/{id}/creatorUid" fallback found nothing and the row +
     * Follow button never showed. musicLibrary tracks denormalize the
     * uploader as uploadedByUid/uploadedByName (see MusicTrack model) —
     * SoundDetailCache resolves creator from there too, so it shows
     * regardless of sheet vs full-screen entry point.
     */
    private void applyMusicLibraryEntry(SoundDetailCache.SoundNodeEntry snap) {
        if (soundUrl.isEmpty()        && snap.audioUrl        != null && !snap.audioUrl.isEmpty())        soundUrl        = snap.audioUrl;
        if (previewAudioUrl.isEmpty() && snap.previewAudioUrl != null && !snap.previewAudioUrl.isEmpty()) previewAudioUrl = snap.previewAudioUrl;
        if (coverUrl.isEmpty()        && snap.coverUrl        != null && !snap.coverUrl.isEmpty())        { coverUrl = snap.coverUrl; loadCoverImage(coverUrl); }
        if (durationMs <= 0 && snap.durationMs != null && snap.durationMs > 0) {
            durationMs = snap.durationMs;
            String s = formatMs(durationMs);
            if (tvDuration  != null) tvDuration.setText(s);
            if (tvTotalTime != null) tvTotalTime.setText(s);
        }
        if (tvReelCount != null) tvReelCount.setText("0 Reels");

        if (creatorUid.isEmpty() && snap.creatorUid != null && !snap.creatorUid.isEmpty()) {
            creatorUid = snap.creatorUid;
            if (snap.creatorName != null && !snap.creatorName.isEmpty()) creatorName = snap.creatorName;
        }
        resolveCreatorAfterSoundLoad(); // handles both "just resolved creatorUid above" and "still empty" cases

        showShimmer(false);
        updatePlayButtonState();

        // PERF (#4): same snapshot as applySoundsNodeEntry(), musicLibrary
        // fallback flavor (0 reel count is deliberate here, see caller).
        if (vm != null) {
            vm.soundDataLoaded = true;
            vm.fromMusicLibrary = true;
            vm.soundUrl        = snap.audioUrl;
            vm.previewAudioUrl = snap.previewAudioUrl;
            vm.coverUrl        = snap.coverUrl;
            vm.durationMs      = durationMs;
            vm.reelCount       = 0;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firebase — Reels
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param fetchInitialPage true on a fresh open (actually hit Firebase
     *                          for the first page via loadMoreReelsForSound());
     *                          false when restoring after rotation, where
     *                          reelItems has already been repopulated from
     *                          SoundDetailViewModel by restoreFromViewModel()
     *                          — in that case this only (re)builds the
     *                          adapter/scroll-listeners/preloader around the
     *                          data that's already there and reattaches the
     *                          live listener, with no extra read.
     */
    private void loadReelsForSound(boolean fetchInitialPage) {
        if (soundId.isEmpty() || rvReels == null) return;
        reelThumbAdapter = new SoundDetailActivity.ReelThumbAdapter(reelItems, position -> {
            if (isGone()) return;
            ArrayList<String> ids = new ArrayList<>();
            for (SoundDetailActivity.ReelThumbItem r : reelItems) ids.add(r.reelId);
            Intent i = new Intent(requireContext(), SingleReelPlayerActivity.class);
            i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
            i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, position);
            i.putExtra(SingleReelPlayerActivity.EXTRA_SHOW_SOUND_ACTIONS, true);
            i.putExtra(SingleReelPlayerActivity.EXTRA_SOUND_ID,    soundId);
            i.putExtra(SingleReelPlayerActivity.EXTRA_SOUND_TITLE, soundTitle);
            i.putExtra(SingleReelPlayerActivity.EXTRA_SOUND_URL,   soundUrl);
            startActivity(i);
        });
        // ULTRA (UserReelsActivity pattern): long-press → muted-looping mini
        // player peek instead of jumping straight to the full player. Reuses
        // the exact same ReelPeekPreviewController as the profile reel grid
        // so both screens share one look/behavior — no duplicate popup code.
        //
        // FEATURE-PARITY FIX: previously always passed `null` for options,
        // so the owner-management card (Insights/Pin/Share/Delete) only
        // ever appeared from UserReelsActivity's own Reels tab, never here
        // — even when the reel under long-press belongs to the current
        // user. Now builds the same options list UserReelsActivity does
        // whenever item.uid matches the signed-in user.
        reelThumbAdapter.setOnItemLongPress(position -> {
            if (isGone() || position < 0 || position >= reelItems.size()) return;
            SoundDetailActivity.ReelThumbItem item = reelItems.get(position);
            if (item.videoUrl == null || item.videoUrl.isEmpty()) return; // nothing to preview

            com.callx.app.models.ReelModel previewReel = new com.callx.app.models.ReelModel();
            previewReel.reelId    = item.reelId;
            previewReel.uid       = item.uid;
            previewReel.videoUrl  = item.videoUrl;
            previewReel.thumbUrl  = item.thumbnailUrl;
            previewReel.viewsCount = (int) item.viewsCount;

            ReelPeekPreviewController.Callback onWatchFull = () -> {
                ArrayList<String> ids = new ArrayList<>();
                for (SoundDetailActivity.ReelThumbItem r : reelItems) ids.add(r.reelId);
                Intent i = new Intent(requireContext(), SingleReelPlayerActivity.class);
                i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
                i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, position);
                i.putExtra(SingleReelPlayerActivity.EXTRA_SHOW_SOUND_ACTIONS, true);
                i.putExtra(SingleReelPlayerActivity.EXTRA_SOUND_ID,    soundId);
                i.putExtra(SingleReelPlayerActivity.EXTRA_SOUND_TITLE, soundTitle);
                i.putExtra(SingleReelPlayerActivity.EXTRA_SOUND_URL,   soundUrl);
                startActivity(i);
            };

            String myUid = FirebaseUtils.getCurrentUid();
            boolean ownerContext = myUid != null && myUid.equals(item.uid);

            View sourceCell = null;
            RecyclerView.ViewHolder svh =
                    rvReels != null ? rvReels.findViewHolderForAdapterPosition(position) : null;
            if (svh != null) sourceCell = svh.itemView;
            final View finalSourceCell = sourceCell;

            if (!ownerContext) {
                peekController.show(previewReel, null, onWatchFull, finalSourceCell);
                return;
            }

            ensurePinnedReelIdLoaded(myUid, () -> {
                if (isGone()) return;
                List<ReelPeekPreviewController.PeekOption> options =
                        buildOwnerPeekOptions(item, previewReel, myUid, position);
                peekController.show(previewReel, options, onWatchFull, finalSourceCell);
            });
        });
        rvReels.setAdapter(reelThumbAdapter);

        // ── NestedScrollView listener — floating actions + mini-player
        // visibility. Pagination is handled entirely by the RecyclerView
        // scroll listener below (debounced, like UserReelsActivity) so this
        // listener never fires loadMoreReelsForSound() mid-fling.
        if (scrollSoundDetail != null) {
            scrollSoundDetail.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)
                (sv, scrollX, scrollY, oldX, oldY) -> {
                    updateFloatingActionsVisibility();
                    updateMiniPlayerVisibility();
                });
        }

        // ── Debounced RecyclerView scroll pagination (UserReelsActivity) ──
        setupReelsScrollPagination();

        // ── Glide preloader — warm upcoming thumbnails (UserReelsActivity) ─
        setupGlidePreloaderForReels();

        if (fetchInitialPage) {
            loadMoreReelsForSound();
        } else {
            // Restoring: reelItems already came from vm — just make sure
            // we're still listening for anything new since we last saw
            // this sound. No Firebase page read here.
            attachSoundReelsLiveListener();
        }
    }

    private void loadMoreReelsForSound() {
        if (isLoadingMoreReels || !hasMoreReels || isGone()) return;
        isLoadingMoreReels = true;
        if (progressReelsPagination != null && lastReelKey != null)
            progressReelsPagination.setVisibility(View.VISIBLE);

        Query q = FirebaseUtils.db().getReference("sounds").child(soundId).child("reels").orderByKey();
        q = lastReelKey != null ? q.startAfter(lastReelKey).limitToFirst(REELS_PAGE_SIZE)
                                : q.limitToFirst(REELS_PAGE_SIZE);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isGone()) return;
                List<SoundDetailActivity.ReelThumbItem> page = new ArrayList<>();
                // PERF (Firebase read batching, ULTRA): legacy-only stragglers —
                // reels linked under this sound BEFORE viewsCount started being
                // denormalized onto sounds/{soundId}/reels/{reelId} (see the
                // exists() check below) collect here for a one-time backfill.
                // Every reel created or viewed after that change already has
                // the field, so in steady state this list is empty and no
                // extra read happens at all.
                List<SoundDetailActivity.ReelThumbItem> legacyBackfill = new ArrayList<>();
                // PERF (ULTRA): no explicit Glide.preload() per item here —
                // setupGlidePreloaderForReels() already runs a
                // RecyclerViewPreloader wired to this same adapter, which
                // scroll-ahead preloads each thumbnail once as it nears the
                // viewport. Preloading again here per page was a duplicate
                // network hit for every thumbnail, every page.
                for (DataSnapshot s : snap.getChildren()) {
                    String rid   = s.getKey();
                    String thumb = firstOf(s, "thumbnailUrl", "thumbnail");
                    String vid   = s.child("videoUrl").getValue(String.class);
                    String uid   = s.child("ownerUid").getValue(String.class);
                    if (rid != null) {
                        SoundDetailActivity.ReelThumbItem item =
                            new SoundDetailActivity.ReelThumbItem(rid, n(thumb), n(vid));
                        item.uid = uid;
                        DataSnapshot vSnap = s.child("viewsCount");
                        if (vSnap.exists()) {
                            Long v = vSnap.getValue(Long.class);
                            item.viewsCount = v != null ? v : 0L;
                        } else {
                            legacyBackfill.add(item);
                        }
                        page.add(item);
                        lastReelKey = rid;
                    }
                }
                if (page.size() < REELS_PAGE_SIZE) hasMoreReels = false;
                if (page.isEmpty() && reelItems.isEmpty() && lastReelKey == null) {
                    hasMoreReels = false; loadReelsFromReelsNode(); return;
                }
                finishAppendingPage(page, true);
                if (!legacyBackfill.isEmpty()) backfillLegacyViewCounts(legacyBackfill);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                isLoadingMoreReels = false;
                if (progressReelsPagination != null) progressReelsPagination.setVisibility(View.GONE);
                if (reelItems.isEmpty()) loadReelsFromReelsNode();
            }
        });
    }

    private void loadReelsFromReelsNode() {
        if (soundId.isEmpty() || isGone()) { isLoadingMoreReels = false; return; }
        FirebaseUtils.db().getReference("reels")
            .orderByChild("musicId").equalTo(soundId)
            .limitToFirst(REELS_PAGE_SIZE)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isGone()) { isLoadingMoreReels = false; return; }
                    List<SoundDetailActivity.ReelThumbItem> page = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        String rid   = s.getKey();
                        String thumb = firstOf(s, "thumbnailUrl", "thumbnail");
                        String vid   = s.child("videoUrl").getValue(String.class);
                        String uid   = s.child("uid").getValue(String.class);
                        Long   views = s.child("viewsCount").getValue(Long.class);
                        if (rid != null) {
                            SoundDetailActivity.ReelThumbItem item =
                                new SoundDetailActivity.ReelThumbItem(rid, n(thumb), n(vid));
                            item.uid = uid;
                            item.viewsCount = views != null ? views : 0L;
                            page.add(item);
                        }
                    }
                    finishAppendingPage(page, false);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { isLoadingMoreReels = false; }
            });
    }

    // ── "Used by" — people who used this sound (Feature reuse of Mutual
    // Followers row/screen from UserReelsActivity) ────────────────────────
    //
    // Row UI: same overlapping-avatar + text pattern as
    // UserReelsActivity#layoutMutualFollowers.
    // Click target: reuses FollowConnectionsActivity as-is (search, list,
    // follow button all reused) — only the UID list and tab label differ,
    // via EXTRA_MUTUAL_UIDS + EXTRA_MUTUAL_TAB_LABEL.

    /**
     * PERF (no extra Firebase read): "used by" needs the same ownerUid data
     * that loadReelsForSound()/loadMoreReelsForSound() already fetch for
     * the reels grid (each ReelThumbItem carries .uid) — so instead of a
     * second "sounds/{soundId}/reels" query duplicating that read, this
     * derives the unique-uid list straight from reelItems already sitting
     * in memory. Called once (see soundUsersComputed) from
     * finishAppendingPage() as soon as the first page of reels lands, and
     * from restoreFromViewModel() on rotation where reelItems is already
     * fully populated synchronously.
     */
    private void deriveSoundUsersFromReelItems() {
        if (isGone() || soundUsersComputed) return;
        soundUsersComputed = true;
        soundUserUidsList.clear();
        soundUserUidsSeen.clear();
        for (SoundDetailActivity.ReelThumbItem item : reelItems) {
            if (item.uid != null && !item.uid.isEmpty() && soundUserUidsSeen.add(item.uid)) {
                soundUserUidsList.add(item.uid);
            }
        }
        fetchSoundUserProfiles();
    }

    /** Fetches name+photo for the first 3 sound-user UIDs, then shows the row. */
    private void fetchSoundUserProfiles() {
        if (soundUserUidsList.isEmpty()) {
            showSoundUsers(new ArrayList<>(), new ArrayList<>());
            return;
        }
        int fetchCount = Math.min(3, soundUserUidsList.size());
        List<String> names  = new ArrayList<>(java.util.Collections.nCopies(fetchCount, (String) null));
        List<String> photos = new ArrayList<>(java.util.Collections.nCopies(fetchCount, (String) null));
        final int[] done = {0};
        MutualFollowersCache profileCache = MutualFollowersCache.getInstance();
        for (int i = 0; i < fetchCount; i++) {
            final int index = i;
            String uid = soundUserUidsList.get(i);
            // PERF: shared cache (5-min TTL) — if this uid was already
            // resolved anywhere else in the app this session (a mutual-
            // followers row, a follow list, etc.), this is a zero-Firebase-
            // read hit instead of a fresh getUserRef() lookup.
            profileCache.getProfile(uid, (name, photo) -> {
                if (isGone()) return;
                names.set(index, name);
                photos.set(index, photo);
                done[0]++;
                if (done[0] >= fetchCount) showSoundUsers(names, photos);
            });
        }
    }

    private void showSoundUsers(List<String> names, List<String> photos) {
        if (layoutSoundUsers == null || isGone()) return;
        int count = soundUserUidsList.size();
        if (count <= 0) {
            layoutSoundUsers.setVisibility(View.GONE);
            return;
        }

        CircleImageView[] ivs = {ivSoundUser1, ivSoundUser2, ivSoundUser3};
        for (int i = 0; i < 3; i++) {
            if (ivs[i] == null) continue;
            if (i < photos.size() && !photos.get(i).isEmpty()) {
                ivs[i].setVisibility(View.VISIBLE);
                Glide.with(this).load(photos.get(i))
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .circleCrop()
                    // PERF: avatars render at only 24dp — 240x240 was decoding
                    // ~10x more pixels than ever get drawn. 60x60 covers up to
                    // ~2.5x hdpi density for a 24dp view, same visual result.
                    .override(60, 60)
                    .into(ivs[i]);
            } else if (i < names.size()) {
                ivs[i].setVisibility(View.VISIBLE);
                ivs[i].setImageResource(R.drawable.ic_person);
            } else {
                ivs[i].setVisibility(View.GONE);
            }
        }

        String text;
        if (count == 1) {
            text = "Used by " + names.get(0);
        } else if (count == 2) {
            text = "Used by " + names.get(0) + " and " + names.get(1);
        } else {
            int others = count - 2;
            text = "Used by " + names.get(0) + ", " + names.get(1)
                + " and " + others + (others == 1 ? " other" : " others");
        }

        if (tvSoundUsers != null) tvSoundUsers.setText(text);
        layoutSoundUsers.setVisibility(View.VISIBLE);
        layoutSoundUsers.setOnClickListener(v -> openSoundUsers());
    }

    private void openSoundUsers() {
        if (isGone()) return;
        Intent i = new Intent(requireContext(), com.callx.app.followers.FollowConnectionsActivity.class);
        // ⚠ FollowConnectionsActivity needs a target UID to open at all
        // (see its `if (targetUid == null) { finish(); return; }` guard) —
        // reuse the sound's own creatorUid if known, else the first sound
        // user, purely as a non-null anchor; the actual list shown comes
        // entirely from EXTRA_MUTUAL_UIDS below, not from this uid's graph.
        String anchorUid = !creatorUid.isEmpty() ? creatorUid
                : (!soundUserUidsList.isEmpty() ? soundUserUidsList.get(0) : "");
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_UID, anchorUid);
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_NAME, soundTitle != null ? soundTitle : "");
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_IS_SELF, false);
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_START_TAB,
                com.callx.app.followers.FollowConnectionsActivity.TAB_MUTUAL);
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_MUTUAL_TAB_LABEL, "Used by");
        i.putStringArrayListExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_MUTUAL_UIDS,
                new ArrayList<>(soundUserUidsList));
        startActivity(i);
    }

    /**
     * PERF (Firebase read batching, ULTRA): this used to be
     * fetchViewCountsForPage() — fired on EVERY single pagination page,
     * fan-out one addListenerForSingleValueEvent per reel (REELS_PAGE_SIZE
     * reads, every time, forever) just to learn a view count. viewsCount is
     * now denormalized straight onto sounds/{soundId}/reels/{reelId} at
     * write time (ReelUploadActivity#registerOrLinkSound seeds it at 0,
     * ReelSocialController / HomeFeedWatchTracker#recordView keep it in
     * sync on every real view — see their PERF notes), so
     * loadMoreReelsForSound() reads it straight off the page snapshot it
     * already fetched, with zero extra round-trips.
     *
     * This method now only runs for the shrinking tail of reels that were
     * linked to a sound before that denormalization shipped and therefore
     * never got the field. For those — and only those — it does one real
     * read of the source of truth (reels/{reelId}/viewsCount) and patches
     * the sound-side node so this exact reel never needs this fallback
     * again, for this user or anyone else.
     */
    private void backfillLegacyViewCounts(List<SoundDetailActivity.ReelThumbItem> legacyItems) {
        if (legacyItems.isEmpty() || soundId.isEmpty()) return;
        for (SoundDetailActivity.ReelThumbItem item : legacyItems) {
            FirebaseUtils.getReelsRef().child(item.reelId).child("viewsCount")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        Long v = snap.getValue(Long.class);
                        long views = v != null ? v : 0L;
                        item.viewsCount = views;

                        // Self-heal: patch it onto the sound-side node so
                        // this fallback never fires again for this reel.
                        FirebaseUtils.db().getReference("sounds").child(soundId)
                            .child("reels").child(item.reelId).child("viewsCount")
                            .setValue(views);

                        if (isGone() || reelThumbAdapter == null) return;
                        int idx = reelItems.indexOf(item);
                        if (idx >= 0) reelThumbAdapter.notifyItemChanged(idx);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { }
                });
        }
    }

    private void finishAppendingPage(List<SoundDetailActivity.ReelThumbItem> page, boolean sort) {
        isLoadingMoreReels = false;
        if (progressReelsPagination != null) progressReelsPagination.setVisibility(View.GONE);
        if (isGone() || page.isEmpty()) return;
        if (sort) {
            for (SoundDetailActivity.ReelThumbItem item : page)
                item.isOriginalCreator = !creatorUid.isEmpty() && creatorUid.equals(item.uid);
            page.sort((a, b) -> {
                if (a.isOriginalCreator != b.isOriginalCreator) return a.isOriginalCreator ? -1 : 1;
                return Long.compare(b.viewsCount, a.viewsCount);
            });
        }
        int start = reelItems.size();
        reelItems.addAll(page);
        if (reelThumbAdapter != null) {
            reelThumbAdapter.notifyItemRangeInserted(start, page.size());
            if (rvReels != null) rvReels.post(() -> {
                if (rvReels != null) rvReels.requestLayout();
                if (scrollSoundDetail != null) scrollSoundDetail.requestLayout();
            });
        }
        syncReelsToViewModel();
        attachSoundReelsLiveListener();
        deriveSoundUsersFromReelItems(); // no-op after first call — see soundUsersComputed
    }

    /**
     * PERF (#4): mirrors reelItems + pagination cursor into vm after every
     * mutation (page append, live add, live remove) so a rotation always
     * restores from the freshest state, not just whatever the first page
     * looked like. reelItems here is thumbnails only (id/thumbUrl/videoUrl/
     * uid/viewsCount) — cheap enough to copy on every mutation.
     */
    private void syncReelsToViewModel() {
        if (vm == null) return;
        vm.reelsLoaded  = true;
        vm.lastReelKey  = lastReelKey;
        vm.hasMoreReels = hasMoreReels;
        vm.reelItems.clear();
        vm.reelItems.addAll(reelItems);
    }

    private void attachSoundReelsLiveListener() {
        if (soundId.isEmpty() || isGone()) return;
        // Single-flight claim FIRST — see reelsLiveListenerAttached's doc.
        // If this returns false, someone else already claimed (or is
        // claiming) the attach for this instance, so bail before touching
        // the query at all.
        if (!reelsLiveListenerAttached.compareAndSet(false, true)) return;
        if (soundReelsLiveListener != null) return; // defensive; unreachable given the claim above, but keeps the old guard intact
        Query liveQ = com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("sounds").child(soundId).child("reels").orderByKey();
        if (lastReelKey != null) liveQ = liveQ.startAfter(lastReelKey);

        soundReelsLiveListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snap, @Nullable String prev) {
                if (isGone()) return;
                String rid = snap.getKey();
                if (rid == null) return;
                for (SoundDetailActivity.ReelThumbItem e : reelItems) if (rid.equals(e.reelId)) return;
                String thumb = firstOf(snap, "thumbnailUrl", "thumbnail");
                String vid   = snap.child("videoUrl").getValue(String.class);
                SoundDetailActivity.ReelThumbItem item =
                    new SoundDetailActivity.ReelThumbItem(rid, n(thumb), n(vid));
                item.uid = snap.child("ownerUid").getValue(String.class);
                reelItems.add(0, item);
                if (reelThumbAdapter != null) {
                    reelThumbAdapter.notifyItemInserted(0);
                    if (rvReels != null) rvReels.post(() -> rvReels.scrollToPosition(0));
                }
                lastReelKey = rid;
                syncReelsToViewModel();
            }
            @Override public void onChildRemoved(@NonNull DataSnapshot snap) {
                if (isGone()) return;
                String rid = snap.getKey();
                for (int i = 0; i < reelItems.size(); i++) {
                    if (rid != null && rid.equals(reelItems.get(i).reelId)) {
                        reelItems.remove(i);
                        if (reelThumbAdapter != null) reelThumbAdapter.notifyItemRemoved(i);
                        syncReelsToViewModel();
                        break;
                    }
                }
            }
            @Override public void onChildChanged(@NonNull DataSnapshot s, @Nullable String p) {}
            @Override public void onChildMoved(@NonNull DataSnapshot s,  @Nullable String p) {}
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        liveQ.addChildEventListener(soundReelsLiveListener);
    }

    private void detachLiveListener() {
        // Always release the single-flight claim, even if there's nothing
        // to remove below — otherwise a partial/failed attach (or a call
        // with soundId already empty) would leave reelsLiveListenerAttached
        // stuck true and permanently block re-attachment for this instance.
        reelsLiveListenerAttached.set(false);
        if (soundReelsLiveListener == null || soundId.isEmpty()) return;
        try {
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("sounds").child(soundId).child("reels")
                .removeEventListener(soundReelsLiveListener);
        } catch (Exception ignored) {}
        soundReelsLiveListener = null;
    }

    // ── Owner peek options (feature-parity with UserReelsActivity) ─────────
    // Mirrors UserReelsActivity#onLongPress()'s ownerContext branch and its
    // pinReel()/unpinReel()/confirmDeleteSingleReel() helpers, scoped to
    // this fragment's own reelItems list + adapter instead of that
    // Activity's diffing adapter.

    /**
     * Resolves reelPinned/{uid}, then runs `then`. PERF: routed through
     * SoundDetailCache (short-TTL, keyed by uid, write-through on pin/
     * unpin below) instead of an instance-local flag — an instance field
     * like the old pinnedReelIdLoaded would reset to unloaded on every
     * related-sound hop (new Fragment instance); the shared cache doesn't.
     */
    private void ensurePinnedReelIdLoaded(String uid, Runnable then) {
        SoundDetailCache.getInstance().getPinnedReelId(uid, id -> {
            cachedPinnedReelId = id;
            then.run();
        });
    }

    private List<ReelPeekPreviewController.PeekOption> buildOwnerPeekOptions(
            SoundDetailActivity.ReelThumbItem item, com.callx.app.models.ReelModel previewReel,
            String myUid, int position) {
        List<ReelPeekPreviewController.PeekOption> options = new ArrayList<>();
        boolean isPinned = item.reelId != null && item.reelId.equals(cachedPinnedReelId);

        options.add(new ReelPeekPreviewController.PeekOption("View Insights", R.drawable.ic_eye, () ->
                com.callx.app.analytics.ReelAnalyticsBottomSheet.newInstance(previewReel)
                        .show(requireActivity().getSupportFragmentManager(), "analytics")));
        options.add(new ReelPeekPreviewController.PeekOption(
                isPinned ? "Unpin Reel" : "Pin Reel", R.drawable.ic_pin, () -> {
                    if (isPinned) unpinOwnedReel(myUid); else pinOwnedReel(myUid, item.reelId);
                }));
        options.add(new ReelPeekPreviewController.PeekOption("Share", R.drawable.ic_share_reel, () ->
                shareOwnedReel(item)));
        options.add(new ReelPeekPreviewController.PeekOption("Delete", R.drawable.ic_delete, () ->
                confirmDeleteOwnedReel(item, position)));
        return options;
    }

    private void pinOwnedReel(String myUid, String reelId) {
        if (isGone() || reelId == null) return;
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reelPinned").child(myUid).setValue(reelId)
            .addOnSuccessListener(v -> {
                cachedPinnedReelId = reelId;
                SoundDetailCache.getInstance().setPinnedReelId(myUid, reelId); // write-through
                if (isAdded()) Toast.makeText(requireContext(), "Reel pinned!", Toast.LENGTH_SHORT).show();
            });
    }

    private void unpinOwnedReel(String myUid) {
        if (isGone()) return;
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reelPinned").child(myUid).removeValue()
            .addOnSuccessListener(v -> {
                cachedPinnedReelId = null;
                SoundDetailCache.getInstance().setPinnedReelId(myUid, null); // write-through
                if (isAdded()) Toast.makeText(requireContext(), "Pinned reel removed", Toast.LENGTH_SHORT).show();
            });
    }

    private void shareOwnedReel(SoundDetailActivity.ReelThumbItem item) {
        if (isGone() || item.reelId == null) return;
        Intent i = new Intent(requireContext(), com.callx.app.social.ReelShareSheetActivity.class);
        i.putExtra(com.callx.app.social.ReelShareSheetActivity.EXTRA_REEL_ID,   item.reelId);
        i.putExtra(com.callx.app.social.ReelShareSheetActivity.EXTRA_VIDEO_URL, item.videoUrl);
        i.putExtra(com.callx.app.social.ReelShareSheetActivity.EXTRA_THUMB_URL, item.thumbnailUrl);
        i.putExtra(com.callx.app.social.ReelShareSheetActivity.EXTRA_OWNER_UID, item.uid);
        startActivity(i);
    }

    private void confirmDeleteOwnedReel(SoundDetailActivity.ReelThumbItem item, int position) {
        if (!isAdded()) return;
        AlertDialogStyler.showReusableConfirm(requireActivity(), "delete_single_reel_sound_detail",
            AlertDialogStyler.DialogSize.DEFAULT,
            "Delete Reel",
            "This reel will be permanently deleted.",
            "Delete", () -> {
                if (isGone() || item.reelId == null) return;
                FirebaseUtils.getReelsRef().child(item.reelId).removeValue();
                if (item.uid != null) FirebaseUtils.getReelsByUserRef(item.uid).child(item.reelId).removeValue();
                if (!soundId.isEmpty())
                    FirebaseUtils.db().getReference("sounds").child(soundId).child("reels").child(item.reelId).removeValue();
                if (item.reelId.equals(cachedPinnedReelId)) unpinOwnedReel(item.uid);

                int idx = reelItems.indexOf(item);
                if (idx >= 0) {
                    reelItems.remove(idx);
                    if (reelThumbAdapter != null) reelThumbAdapter.notifyItemRemoved(idx);
                }
                if (isAdded()) Toast.makeText(requireContext(), "Deleted", Toast.LENGTH_SHORT).show();
            },
            null, null,
            "Cancel");
    }

    private void sortAndApplyReelItems() {
        if (isGone() || creatorUid.isEmpty() || reelItems.isEmpty()) return;
        boolean changed = false;
        for (SoundDetailActivity.ReelThumbItem item : reelItems) {
            boolean o = creatorUid.equals(item.uid);
            if (o != item.isOriginalCreator) changed = true;
            item.isOriginalCreator = o;
        }
        if (!changed) return;

        // PERF (ULTRA): this re-sort only reorders the "Original" creator's
        // reels to the front — it doesn't add/remove anything or change
        // what's inside each cell. notifyDataSetChanged() used to rebind
        // (and re-run Glide loads for) every visible cell just to move a
        // few rows; DiffUtil emits move/change ops for only the rows that
        // actually shifted or flipped the "Original" badge instead.
        //
        // calculateDiff() itself is O(N) and was running synchronously on
        // the main thread here — fine for a page or two, but once several
        // pagination pages plus live adds (attachSoundReelsLiveListener)
        // have grown reelItems, that call alone can eat a frame. The sort +
        // diff calculation now both run on diffExecutor; only
        // dispatchUpdatesTo() — which must touch the adapter — comes back
        // to the main thread.
        final List<SoundDetailActivity.ReelThumbItem> before = new ArrayList<>(reelItems);
        diffExecutor.execute(() -> {
            List<SoundDetailActivity.ReelThumbItem> after = new ArrayList<>(before);
            after.sort((a, b) -> Boolean.compare(!a.isOriginalCreator, !b.isOriginalCreator));
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(
                new ReelThumbDiffCallback(before, after), false);
            mainHandler.post(() -> {
                if (isGone() || reelThumbAdapter == null) return;
                // The live list may have changed shape (a pagination page or
                // a live add/delete landed) while this diff was computing in
                // the background — applying it against a resized list would
                // dispatch adapter ops for positions that no longer exist.
                // Bail; the next mutation to reelItems already reflects
                // current reality, and any missed reorder is harmless (the
                // isOriginalCreator flags above were already applied to the
                // live item objects regardless).
                if (reelItems.size() != before.size()) return;
                reelItems.clear();
                reelItems.addAll(after);
                diff.dispatchUpdatesTo(reelThumbAdapter);
            });
        });
    }

    /** Content diff for {@link SoundDetailActivity.ReelThumbAdapter} — identity by
     *  reelId, content equality by the fields onBindViewHolder() actually renders
     *  (thumbnail, view count, "Original" badge). Used by sortAndApplyReelItems()
     *  so a reorder dispatches DiffUtil move/change ops instead of a blanket
     *  notifyDataSetChanged(). Move detection is disabled (see calculateDiff call)
     *  since this grid's item decoration/animator setup doesn't animate moves —
     *  the change ops alone are enough to avoid rebinding untouched cells. */
    private static class ReelThumbDiffCallback extends DiffUtil.Callback {
        private final List<SoundDetailActivity.ReelThumbItem> oldList, newList;
        ReelThumbDiffCallback(List<SoundDetailActivity.ReelThumbItem> oldList,
                              List<SoundDetailActivity.ReelThumbItem> newList) {
            this.oldList = oldList; this.newList = newList;
        }
        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }
        @Override public boolean areItemsTheSame(int oldPos, int newPos) {
            String a = oldList.get(oldPos).reelId, b = newList.get(newPos).reelId;
            return a != null && a.equals(b);
        }
        @Override public boolean areContentsTheSame(int oldPos, int newPos) {
            SoundDetailActivity.ReelThumbItem a = oldList.get(oldPos), b = newList.get(newPos);
            return a.isOriginalCreator == b.isOriginalCreator
                && a.viewsCount == b.viewsCount
                && java.util.Objects.equals(a.thumbnailUrl, b.thumbnailUrl);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firebase — Related sounds
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * PERF: routed through SoundDetailCache#getRelatedSounds(), cached by
     * genre alone (not by soundId) — every sound sharing a genre reuses the
     * same fetched list instead of re-querying musicLibrary/ every time a
     * different song of that genre opens this screen. soundId exclusion is
     * applied locally below since that part IS specific to this instance.
     */
    private void loadRelatedSounds() {
        if (genre.isEmpty()) return;
        SoundDetailCache.getInstance().getRelatedSounds(genre, related -> {
            if (isGone()) return;
            relatedItems.clear();
            for (SoundDetailCache.RelatedEntry r : related) {
                if (r.id != null && r.id.equals(soundId)) continue;
                relatedItems.add(new SoundDetailActivity.RelatedItem(r.id, r.title, r.artist, r.coverUrl, r.audioUrl));
            }
            if (rvRelated != null && relatedAdapter != null && !relatedItems.isEmpty()) {
                relatedAdapter.submitList(relatedItems);
                View sec = binding != null ? binding.layoutRelatedSoundsSection : null;
                if (sec != null) sec.setVisibility(View.VISIBLE);
            }
            // PERF (#4): snapshot so a rotation doesn't re-hit SoundDetailCache.
            if (vm != null) {
                vm.relatedLoaded = true;
                vm.relatedItems.clear();
                vm.relatedItems.addAll(relatedItems);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firebase — Creator
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Gap #2 (Firebase read batching): this used to fire its own
     * "sounds/{soundId}/creatorUid" read independently and in parallel with
     * loadSoundData()'s read of the FULL "sounds/{soundId}" node — an
     * overlapping round-trip to the same node for a value loadSoundData()
     * was already about to have. Now it only resolves from whatever's
     * already known synchronously (bundle args); if creatorUid isn't known
     * yet, it does nothing and waits — resolveCreatorAfterSoundLoad() (
     * called from loadSoundData()'s and loadSoundDataFromMusicLibrary()'s
     * callbacks) piggybacks the creatorUid off that read instead, so there
     * is at most one round-trip to the sound node instead of two.
     */
    private void loadCreatorProfile() {
        if (!creatorUid.isEmpty() && creatorName != null && !creatorName.isEmpty()) {
            bindCreatorRow(creatorUid, creatorName, creatorPhoto);
            creatorProfileResolutionAttempted = true;
            return;
        }
        if (!creatorUid.isEmpty()) {
            fetchCreatorUserData(creatorUid);
            creatorProfileResolutionAttempted = true;
        }
        // else: creatorUid not known yet from bundle args — leave it to
        // resolveCreatorAfterSoundLoad() once the sound node read lands.
    }

    /**
     * Called exactly once, from loadSoundData()'s onDataChange/onCancelled
     * and loadSoundDataFromMusicLibrary()'s onDataChange/onCancelled — i.e.
     * right after the sound (or fallback musicLibrary) node has already
     * been read in full. Resolves creatorUid from that same read instead
     * of loadCreatorProfile() firing a second, overlapping listener.
     */
    private void resolveCreatorAfterSoundLoad() {
        if (creatorProfileResolutionAttempted) return;
        creatorProfileResolutionAttempted = true;
        if (!creatorUid.isEmpty() && creatorName != null && !creatorName.isEmpty()) {
            bindCreatorRow(creatorUid, creatorName, creatorPhoto);
        } else if (!creatorUid.isEmpty()) {
            fetchCreatorUserData(creatorUid);
        }
        // If creatorUid is still empty here, neither node had a creator to
        // resolve — nothing more to fetch.
    }

    /**
     * PERF: routed through SoundDetailCache#getCreatorProfile(), which owns
     * the reelUsers/ → users/ fallback cascade (previously this method's
     * own two listeners) behind a 5-minute TTL cache keyed by uid. A
     * creator seen on one sound is instantly known on every other sound of
     * theirs opened this session — no repeat reads for the same person.
     */
    private void fetchCreatorUserData(String uid) {
        SoundDetailCache.getInstance().getCreatorProfile(uid, profile -> {
            if (isGone()) return;
            creatorName  = profile.name;
            creatorPhoto = profile.photo;
            bindCreatorRow(uid, profile.name, profile.photo);
        });
    }

    private void bindCreatorRow(String uid, String name, String photo) {
        // PERF (#4): snapshot regardless of the early-return below, so a
        // creator resolved right before a rotation still gets remembered.
        if (vm != null) {
            vm.creatorLoaded = true;
            vm.creatorUid    = uid;
            vm.creatorName   = name;
            vm.creatorPhoto  = photo;
        }
        if (layoutCreator == null || isGone()) return;
        if (tvCreatorName != null) tvCreatorName.setText("@" + name);
        if (ivCreatorAvatar != null) {
            if (photo != null && !photo.isEmpty())
                Glide.with(requireContext()).load(photo).transform(new CircleCrop())
                    .placeholder(R.drawable.ic_person).error(R.drawable.ic_person)
                    .override(avatarDecodePx(), avatarDecodePx()).into(ivCreatorAvatar);
            else ivCreatorAvatar.setImageResource(R.drawable.ic_person);
        }
        layoutCreator.setVisibility(View.VISIBLE);
        // Resolves via the cached lookup (VerifiedStatusCache) so scrolling/
        // reopening the sheet doesn't repeatedly hit Firebase — same pattern
        // as chats/calls tabs. Was previously wired to always show regardless
        // of actual verification status; now reflects the real value.
        VerifiedBadgeUtils.bindForUid(ivCreatorVerified, uid);
        // divider_creator removed from layout — creator row is now its own
        // boxed card (bg_sound_detail_box), so no separate hairline needed.
        layoutCreator.setOnClickListener(v -> openUserProfile(uid, name, photo));
        bindFollowCreatorBtn(uid);
        loadCreatorFollowerCount(uid);
    }

    /**
     * PERF: routed through SoundDetailCache#getFollowerCount(), same
     * short-TTL cache pattern as getCreatorProfile()/getFollowStatus() —
     * a creator's follower count is now read from Firebase at most once per
     * TTL window and reused across every sound of theirs opened this
     * session (including related-sound hops, which replace this fragment
     * instance entirely), instead of a fresh read on every bindCreatorRow().
     */
    private void loadCreatorFollowerCount(String uid) {
        if (tvCreatorFollowers == null || uid == null || uid.isEmpty()) return;
        SoundDetailCache.getInstance().getFollowerCount(uid, count -> {
            if (isGone() || tvCreatorFollowers == null) return;
            tvCreatorFollowers.setText(formatCount(count) + " followers");
        });
    }

    /**
     * Follow/Following pill button on the original-creator row — same
     * filled/outline style reused across FollowConnectionsActivity,
     * ReelLikesBottomSheet, and ReelSharesBottomSheet. Hidden entirely when
     * this sound's creator is the current user (nothing to follow).
     *
     * PERF: follow status now lives in SoundDetailCache (short-TTL,
     * keyed by myUid|creatorUid, write-through on toggle) instead of this
     * fragment's own static map — same "no re-read once known this
     * session" behavior, but shared with every other screen that resolves
     * follow status through the same cache instead of a fragment-local one.
     */
    private void bindFollowCreatorBtn(String uid) {
        if (btnFollowCreator == null) return;
        String myUid = FirebaseUtils.getCurrentUid();
        if (uid == null || uid.isEmpty() || myUid == null || myUid.isEmpty() || uid.equals(myUid)) {
            btnFollowCreator.setVisibility(View.GONE);
            return;
        }
        btnFollowCreator.setVisibility(View.VISIBLE);
        // Consume clicks with a no-op until the real follow state loads
        // below, so a fast tap can't race the Firebase read.
        btnFollowCreator.setOnClickListener(v -> {});

        SoundDetailCache.getInstance().getFollowStatus(myUid, uid, following -> {
            if (isGone()) return;
            attachFollowCreatorClick(uid, myUid, following);
        });
    }

    /** Holds the live follow state in a one-element array so the click
     *  listener always toggles from the current value, not a stale one
     *  captured at bind time. */
    private void attachFollowCreatorClick(String uid, String myUid, boolean initiallyFollowing) {
        if (btnFollowCreator == null || isGone()) return;
        final boolean[] following = {initiallyFollowing};
        styleFollowBtn(btnFollowCreator, following[0]);
        btnFollowCreator.setOnClickListener(v -> {
            following[0] = !following[0];
            SoundDetailCache.getInstance().setFollowStatus(myUid, uid, following[0]); // write-through — instant across every screen sharing the cache
            DatabaseReference ref = FirebaseUtils.getReelFollowsRef(myUid).child(uid);
            if (following[0]) ref.setValue(true);
            else              ref.removeValue();
            styleFollowBtn(btnFollowCreator, following[0]);
            bounceView(btnFollowCreator);
        });
    }

    /**
     * Pill-shaped follow button styling — premium redesign: outline by
     * default ("Follow" — not yet actively followed), filled brand_primary
     * only once actively followed ("Following ✓"), per the brief ("outline
     * instead of solid fill, until actively pressed"). Previously the
     * opposite (filled=Follow, outline=Following). A fresh GradientDrawable
     * is built per call (not cached/shared) since this row's button width
     * changes with the text.
     */
    private void styleFollowBtn(android.widget.Button btn, boolean isFollowing) {
        btn.setText(isFollowing ? "Following ✓" : "Follow");
        float r = 16f * btn.getResources().getDisplayMetrics().density; // pill: half of 32dp height
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(r);
        int brand = getResources().getColor(R.color.brand_primary, null);
        if (isFollowing) {
            bg.setColor(brand);
            bg.setStroke(0, brand);
            btn.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(android.graphics.Color.TRANSPARENT);
            bg.setStroke((int) (1.5f * btn.getResources().getDisplayMetrics().density), brand);
            btn.setTextColor(brand);
        }
        btn.setBackground(bg);
    }

    /**
     * Subtle scale-bounce micro-animation — used on play/pause and the
     * follow toggle so state changes feel like a deliberate tap rather than
     * an instant flat swap, per the "micro-animations" brief.
     */
    private void bounceView(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setScaleX(0.85f);
        v.setScaleY(0.85f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(new android.view.animation.OvershootInterpolator(3f))
            .start();
    }

    private void openUserProfile(String uid, String name, String photo) {
        if (uid.isEmpty() || isGone()) return;
        try {
            Intent i = new Intent().setClassName(requireContext(), "com.callx.app.activities.UserProfileActivity");
            i.putExtra("uid", uid);
            i.putExtra("name",  n(name));
            i.putExtra("photo", n(photo));
            startActivity(i);
        } catch (Exception ex) { android.util.Log.w("SoundDetailFrag", "UserProfileActivity not found", ex); }
    }

    /** PERF: routed through SoundDetailCache — short-TTL cached, and written-through instantly by toggleSave() below, so it costs a read at most once per TTL window instead of on every screen open. */
    private void checkIfSaved() {
        String uid = null;
        try { uid = FirebaseUtils.getCurrentUid(); } catch (Exception ignored) {}
        if (uid == null || soundId.isEmpty()) return;
        SoundDetailCache.getInstance().getSavedStatus(uid, soundId, saved -> {
            if (isGone()) return;
            isSaved = saved;
            updateSaveButton();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Click listeners
    // ─────────────────────────────────────────────────────────────────────────

    private void setupClickListeners() {
        // Close: Activity → finish(), Sheet → dismiss() — callback handles it
        if (btnBack != null) btnBack.setOnClickListener(v -> {
            if (onCloseListener != null) onCloseListener.run();
        });

        if (btnShare     != null) btnShare.setOnClickListener(v -> shareSound());
        if (btnSaveSound != null) {
            btnSaveSound.setOnClickListener(v -> toggleSave());
            btnSaveSound.setOnLongClickListener(v -> { startActivity(new Intent(requireContext(), SavedSoundsActivity.class)); return true; });
        }
        if (btnFloatingSave     != null) btnFloatingSave.setOnClickListener(v -> toggleSave());
        if (btnFloatingUseAudio != null) btnFloatingUseAudio.setOnClickListener(v -> showUseAudioDialog());
        if (btnPlayPause        != null) btnPlayPause.setOnClickListener(v -> { bounceView(btnPlayPause); togglePlayPause(); });

        // ✅ FIX Bug 2: show "Full Audio / Trim Audio" dialog before opening camera
        if (btnUseSoundCamera != null) btnUseSoundCamera.setOnClickListener(v -> {
            if (isGone()) return;
            showUseTypeDialog(0 /* camera */);
        });

        // ✅ FIX Bug 2: show "Full Audio / Trim Audio" dialog before opening gallery
        if (btnUseSoundGallery != null) btnUseSoundGallery.setOnClickListener(v -> {
            if (isGone()) return;
            showUseTypeDialog(1 /* gallery */);
        });

        if (btnMore != null) btnMore.setOnClickListener(v -> showMoreMenu());

        // Add to profile
        String myUid = FirebaseUtils.getCurrentUid();
        if (btnAddToProfile != null && myUid != null) {
            btnAddToProfile.setVisibility(View.VISIBLE);
            btnAddToProfile.setOnClickListener(v -> {
                java.util.Map<String, Object> data = new java.util.HashMap<>();
                data.put("soundId", soundId); data.put("title", soundTitle);
                data.put("artist", artist);   data.put("coverUrl", coverUrl);
                data.put("soundUrl", soundUrl); data.put("durationMs", durationMs);
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("reels/users").child(myUid).child("profileSong").setValue(data)
                    .addOnSuccessListener(u -> { if (tvAddToProfile != null) tvAddToProfile.setText("✓ Added"); })
                    .addOnFailureListener(e -> { if (isAdded()) Toast.makeText(requireContext(), "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show(); });
            });
        }

        if (btnMiniPlayPause != null) btnMiniPlayPause.setOnClickListener(v -> togglePlayPause());
        if (btnMiniClose     != null) btnMiniClose.setOnClickListener(v -> { pausePlayback(); hideMiniPlayer(); });
    }

    private void showMoreMenu() {
        if (isGone()) return;
        PopupMenu popup = new PopupMenu(requireContext(), btnMore);
        popup.getMenu().add(0, 1, 0, "Report sound");
        popup.getMenu().add(0, 2, 1, "Add to playlist");
        popup.getMenu().add(0, 3, 2, "Copy link");
        popup.getMenu().add(0, 4, 3, "Not interested");
        popup.getMenu().add(0, 7, 4, "🔍 Search Sounds");
        popup.getMenu().add(0, 8, 5, "🎚 Remix this Sound");
        // ✂ Set Start Point — moved here from the on-screen chip; sound has a
        // URL to trim (same condition the old chip used to decide visibility)
        if (soundUrl != null && !soundUrl.isEmpty()) {
            popup.getMenu().add(0, 9, 6, "✂ Set Start Point");
        }
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid != null && myUid.equals(creatorUid)) {
            popup.getMenu().add(0, 5, 7, "Upload Sound");
            popup.getMenu().add(0, 6, 8, "View Analytics");
        }
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: confirmReportSound(); return true;
                case 2: { Intent i = new Intent(requireContext(), SoundPlaylistActivity.class);
                    i.putExtra("sound_id", soundId); i.putExtra("sound_title", soundTitle);
                    i.putExtra("sound_url", soundUrl); startActivity(i); return true; }
                case 3: copySoundLink(); return true;
                case 4: markNotInterested(); return true;
                case 5: { Intent i = new Intent(requireContext(), SoundUploadActivity.class);
                    i.putExtra("sound_id", soundId); i.putExtra("sound_title", soundTitle);
                    startActivity(i); return true; }
                case 6: { Intent i = new Intent(requireContext(), SoundAnalyticsActivity.class);
                    i.putExtra(SoundAnalyticsActivity.EXTRA_SOUND_ID, soundId);
                    i.putExtra(SoundAnalyticsActivity.EXTRA_SOUND_TITLE, soundTitle);
                    startActivity(i); return true; }
                case 7: startActivity(new Intent(requireContext(), SoundSearchActivity.class)); return true;
                case 8: { Intent i = new Intent(requireContext(), SoundRemixActivity.class);
                    i.putExtra(SoundRemixActivity.EXTRA_SOUND_A_ID,    soundId);
                    i.putExtra(SoundRemixActivity.EXTRA_SOUND_A_URL,   soundUrl);
                    i.putExtra(SoundRemixActivity.EXTRA_SOUND_A_TITLE, soundTitle);
                    i.putExtra(SoundRemixActivity.EXTRA_SOUND_A_COVER, coverUrl);
                    i.putExtra(SoundRemixActivity.EXTRA_SOUND_A_ARTIST,artist);
                    startActivity(i); return true; }
                case 9: openTrimSound(); return true;
                default: return true;
            }
        });
        popup.show();
    }

    /** ✂ Opens the trim editor — moved here from the on-screen "Set Start
     *  Point" chip into the 3-dot menu. Same intent/extras as before. */
    private void openTrimSound() {
        if (isGone()) return;
        Intent i = new Intent(requireContext(), com.callx.app.editor.ReelMusicTrimActivity.class);
        i.putExtra(com.callx.app.editor.ReelMusicTrimActivity.EXTRA_SOUND_ID,       soundId);
        i.putExtra(com.callx.app.editor.ReelMusicTrimActivity.EXTRA_SOUND_TITLE,    soundTitle);
        i.putExtra(com.callx.app.editor.ReelMusicTrimActivity.EXTRA_SOUND_ARTIST,   artist);
        i.putExtra(com.callx.app.editor.ReelMusicTrimActivity.EXTRA_SOUND_COVER,    coverUrl);
        i.putExtra(com.callx.app.editor.ReelMusicTrimActivity.EXTRA_SOUND_URL,      soundUrl);
        i.putExtra(com.callx.app.editor.ReelMusicTrimActivity.EXTRA_DURATION_MS,    durationMs);
        startActivityForResult(i, REQUEST_TRIM_SOUND);
    }

    /** ✅ FIX: "Report sound" used to be a silent no-op — now asks for a reason
     *  and writes the report to Firebase under sound_reports/{soundId}. */
    private void confirmReportSound() {
        if (isGone() || soundId.isEmpty()) return;
        String[] reasons = {"Copyright violation", "Inappropriate content", "Spam or misleading", "Other"};
        AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Report sound")
            .setItems(reasons, (d, which) -> submitSoundReport(reasons[which]))
            .setNegativeButton("Cancel", null)
            .create());
    }

    private void submitSoundReport(String reason) {
        String uid = null;
        try { uid = FirebaseUtils.getCurrentUid(); } catch (Exception ignored) {}
        if (uid == null) { if (isAdded()) Toast.makeText(requireContext(), "Please sign in to report", Toast.LENGTH_SHORT).show(); return; }
        java.util.Map<String, Object> report = new java.util.HashMap<>();
        report.put("reportedBy", uid);
        report.put("reason", reason);
        report.put("soundId", soundId);
        report.put("timestamp", System.currentTimeMillis());
        FirebaseUtils.db().getReference("sound_reports").child(soundId).push().setValue(report);
        if (isAdded()) Toast.makeText(requireContext(), "Report submitted. Thank you.", Toast.LENGTH_SHORT).show();
    }

    /** ✅ FIX: "Copy link" was previously wired to shareSound() (opened the
     *  share sheet instead of actually copying anything to the clipboard). */
    private void copySoundLink() {
        if (isGone() || soundId.isEmpty()) return;
        String link = com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/sound/" + soundId;
        android.content.ClipboardManager cm =
            (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Sound link", link));
        if (isAdded()) Toast.makeText(requireContext(), "Link copied", Toast.LENGTH_SHORT).show();
    }

    /** ✅ FIX: "Not interested" used to be a silent no-op — now records the
     *  preference so recommendation surfaces (trending/related) can respect it. */
    private void markNotInterested() {
        String uid = null;
        try { uid = FirebaseUtils.getCurrentUid(); } catch (Exception ignored) {}
        if (uid == null || soundId.isEmpty()) return;
        FirebaseUtils.getUserRef(uid).child("sound_not_interested").child(soundId).setValue(System.currentTimeMillis());
        if (isAdded()) Toast.makeText(requireContext(), "You won't see this sound as often", Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mini-player
    // ─────────────────────────────────────────────────────────────────────────

    private void showMiniPlayer() {
        if (layoutMiniPlayer == null || miniPlayerActive || isGone()) return;
        miniPlayerActive = true;
        if (tvMiniTitle != null) tvMiniTitle.setText(soundTitle.isEmpty() ? "Now Playing" : soundTitle);
        if (ivMiniCover != null && !coverUrl.isEmpty())
            Glide.with(requireContext()).load(coverUrl).transform(new CircleCrop())
                .placeholder(R.drawable.ic_music_note).override(avatarDecodePx(), avatarDecodePx()).into(ivMiniCover);
        layoutMiniPlayer.setVisibility(View.VISIBLE);
        updateMiniPlayButton();
    }

    private void hideMiniPlayer() {
        if (layoutMiniPlayer == null || !miniPlayerActive) return;
        miniPlayerActive = false;
        layoutMiniPlayer.setVisibility(View.GONE);
        // Reset any leftover swipe-drag transform (translation/scale/alpha/
        // corner-radius) so the bar comes back in its normal resting state
        // next time showMiniPlayer() makes it VISIBLE again — this view is
        // reused across plays, unlike MediaViewerActivity which just finishes.
        layoutMiniPlayer.setTranslationY(0f);
        layoutMiniPlayer.setScaleX(1f);
        layoutMiniPlayer.setScaleY(1f);
        layoutMiniPlayer.setAlpha(1f);
        if (miniPlayerSwipeHelper != null) miniPlayerSwipeHelper.setLiveCornerRadius(0f, 1f);
    }

    /**
     * Swipe-up/down-to-close on the mini player — same MediaSwipeReplyCloseHelper
     * MediaViewerActivity and ReelPeekPreviewController reuse (no new gesture
     * code duplicated). layoutMiniPlayer is a SwipeAwareFrameLayout, which
     * forwards every touch event to swipeHelper.onTouch() first — the same
     * "see the event before any child view" requirement Activity#dispatchTouchEvent
     * satisfies for MediaViewerActivity and PopupWindow#setTouchInterceptor
     * satisfies for ReelPeekPreviewController.
     *
     * No backgroundView/scrim is passed — this bar sits inline in the
     * fragment layout, not over a dimmed full-screen surface, so only the
     * card itself needs to progressively shrink/translate/fade during drag;
     * the helper's built-in spring-back handles an under-threshold release
     * automatically. On threshold-cross/fling, closeMiniPlayerAnimated()
     * continues the same drag motion out (velocity-adjusted, so a hard
     * fling exits quicker) instead of an instant cut, then pauses playback
     * and hides the bar — mirroring btnMiniClose's action.
     */
    private void setupMiniPlayerSwipeClose() {
        if (layoutMiniPlayer == null || !isAdded()) return;
        miniPlayerSwipeHelper = new MediaSwipeReplyCloseHelper(requireContext(), layoutMiniPlayer, null, null, null,
                new MediaSwipeReplyCloseHelper.Callback() {
                    @Override public void onSwipeUpReply() { /* retired — see MediaSwipeReplyCloseHelper class doc */ }
                    @Override public void onSwipeDownClose(float velocityY) {
                        closeMiniPlayerAnimated(velocityY);
                    }
                });
        layoutMiniPlayer.setSwipeHelper(miniPlayerSwipeHelper);
    }

    /** Continues the live-drag motion the rest of the way off-screen, then pauses + hides — velocity-adjusted so a hard fling exits faster than a just-past-threshold drag. */
    private void closeMiniPlayerAnimated(float velocityY) {
        final View bar = layoutMiniPlayer;
        if (bar == null) { pausePlayback(); hideMiniPlayer(); return; }

        float density = requireContext().getResources().getDisplayMetrics().density;
        float speedDpPerSec = Math.abs(velocityY) / density;
        float speedFactor = Math.min(1f, speedDpPerSec / 2500f);
        long duration = Math.round(220L - speedFactor * (220L - 110L)); // 220ms base → 110ms on a hard fling

        float exitDirection = bar.getTranslationY() < 0 ? -1f : 1f;
        float exitTy = exitDirection * (bar.getHeight() > 0 ? bar.getHeight() : 64f * density);

        bar.animate().cancel();
        bar.animate()
                .translationY(exitTy)
                .alpha(0f)
                .setDuration(duration)
                .withEndAction(() -> { pausePlayback(); hideMiniPlayer(); })
                .start();
    }

    private void updateMiniPlayButton() {
        if (btnMiniPlayPause != null)
            btnMiniPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        updateMiniPlayerVisibility();
    }

    /**
     * FIX: mini-player bar wasn't tied to scrolling away from the main play
     * control at all before — it only ever appeared from the "related
     * sounds" list tap. Now, same pattern as updateFloatingActionsVisibility()
     * (screen-position compare against scrollSoundDetail's top), it shows
     * automatically once audio is playing AND the main btnPlayPause has
     * scrolled off the top of the screen, and hides again once you scroll
     * back to it or playback stops — called on every scroll tick and every
     * play/pause/resume state change (via updateMiniPlayButton()).
     */
    private void updateMiniPlayerVisibility() {
        if (layoutMiniPlayer == null || btnPlayPause == null || scrollSoundDetail == null || isGone()) return;
        if (!isPlaying) { hideMiniPlayer(); return; }
        int[] pl = new int[2], sl = new int[2];
        btnPlayPause.getLocationOnScreen(pl);
        scrollSoundDetail.getLocationOnScreen(sl);
        boolean scrolledPastControl = (pl[1] + btnPlayPause.getHeight()) < sl[1];
        if (scrolledPastControl) showMiniPlayer(); else hideMiniPlayer();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ExoPlayer
    // ─────────────────────────────────────────────────────────────────────────

    private String getPlaybackUrl() {
        if (!skipPreviewUrl && !previewAudioUrl.isEmpty()) return previewAudioUrl;
        return soundUrl;
    }

    private void togglePlayPause() {
        String url = getPlaybackUrl();
        if (url.isEmpty()) { if (isAdded()) Toast.makeText(requireContext(), "Loading audio…", Toast.LENGTH_SHORT).show(); return; }
        if (isPreparing) return;
        if (isPlaying) pausePlayback(); else { if (exoPlayer == null) initAndStartPlayer(); else resumePlayback(); }
    }

    // PERF (SoundPreviewPlayerPool): reuse the app-wide pooled ExoPlayer
    // instead of `new ExoPlayer.Builder(...).build()` on every single open —
    // skips renderer/decoder/thread setup on the 2nd+ open in a session
    // (e.g. bouncing between a reel and its sound page). Audio focus
    // (duck/pause under a call or other apps' audio) is now handled by the
    // pool itself via setAudioAttributes(..., handleAudioFocus=true), so no
    // manual AudioManager/AudioFocusRequest code is needed here.
    //
    // PERF (disk cache): setMediaSource(pool.buildMediaSource(url)) instead
    // of setMediaItem(MediaItem.fromUri(url)) — routes playback through
    // UnifiedVideoCacheManager's disk-backed CacheDataSource (see
    // SoundPreviewPlayerPool#buildMediaSource), so reopening the same sound,
    // or the retry/fallback-URL path below, replays from disk instead of
    // re-downloading.
    //
    // Trade-off vs the old per-open buildThermalAwareLoadControl(): the
    // pooled player's buffer window is fixed once at construction instead
    // of being recomputed per-open from the current thermal level, since
    // ExoPlayer's LoadControl can't be swapped after the player is built.
    // Acceptable here — this is audio-only playback, where the buffer-size
    // difference is a few KB either way, unlike video. thermalManager is
    // still used below purely to gate the waveform animation loop.
    private void initAndStartPlayer() {
        if (isGone()) return;
        isPreparing = true; setPlayButtonLoading(true);
        SoundPreviewPlayerPool pool = SoundPreviewPlayerPool.get(requireContext());
        exoPlayer = pool.acquire();
        exoPlayer.addListener(this);
        exoPlayer.setMediaSource(pool.buildMediaSource(getPlaybackUrl()));
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.prepare();
    }

    @Override public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_READY && isPreparing) {
            isPreparing = false; setPlayButtonLoading(false);
            long dur = exoPlayer.getDuration();
            if (dur > 0 && tvTotalTime != null) tvTotalTime.setText(formatMs((int) dur));
            exoPlayer.play(); isPlaying = true;
            if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_pause);
            startWaveAnimation(); startDiscAnimation();
            startSeekTicker(); updateMiniPlayButton();
        } else if (state == Player.STATE_ENDED) {
            pausePlayback();
            if (seekBar != null) seekBar.setProgress(0);
            if (tvCurrentTime != null) tvCurrentTime.setText("0:00");
        }
    }

    @Override public void onPlayerError(@NonNull PlaybackException error) {
        releasePlayer(); setPlayButtonLoading(false);
        isPlaying = false; isPreparing = false;
        if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
        stopWaveAnimation(); stopDiscAnimation(); stopSeekTicker();
        if (!retried) {
            retried = true; mainHandler.postDelayed(this::initAndStartPlayer, 800);
        } else if (!triedFallbackUrl && !skipPreviewUrl && !previewAudioUrl.isEmpty()
                && !soundUrl.isEmpty() && !soundUrl.equals(previewAudioUrl)) {
            // The low-bitrate preview URL failed twice (missing/expired/bad) — fall back to
            // the full-quality soundUrl so playback still works instead of dead-ending here.
            triedFallbackUrl = true; skipPreviewUrl = true; retried = false;
            mainHandler.postDelayed(this::initAndStartPlayer, 300);
        } else if (isAdded()) {
            Toast.makeText(requireContext(), "Cannot play this audio", Toast.LENGTH_SHORT).show();
        }
    }

    private void resumePlayback() {
        if (exoPlayer == null) { initAndStartPlayer(); return; }
        exoPlayer.play(); isPlaying = true;
        if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_pause);
        startWaveAnimation(); startDiscAnimation();
        startSeekTicker(); updateMiniPlayButton();
    }

    private void pausePlayback() {
        if (exoPlayer != null) exoPlayer.pause();
        isPlaying = false;
        if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
        stopWaveAnimation(); stopDiscAnimation();
        stopSeekTicker(); updateMiniPlayButton();
    }

    private void setPlayButtonLoading(boolean loading) {
        if (btnPlayPause == null) return;
        if (progressBar  != null) progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnPlayPause.setEnabled(!loading); btnPlayPause.setAlpha(loading ? 0.5f : 1f);
    }

    // PERF (SoundPreviewPlayerPool): return the instance to the pool instead
    // of exoPlayer.release() — the pool stops it, clears media items, and
    // strips this Fragment's listener, but keeps the underlying ExoPlayer
    // (decoder/thread) alive for the next Sound Detail screen to reuse.
    // Uses getExisting() (not get(requireContext())) since this can run from
    // an async onPlayerError callback after the Fragment has detached —
    // requireContext() would crash right when we're mid-cleanup.
    private void releasePlayer() {
        if (exoPlayer != null) {
            SoundPreviewPlayerPool pool = SoundPreviewPlayerPool.getExisting();
            if (pool != null) pool.release(this);
            else { try { exoPlayer.removeListener(this); } catch (Exception ignored) {} }
            exoPlayer = null;
        }
        isPlaying = false; isPreparing = false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Save / Share
    // ─────────────────────────────────────────────────────────────────────────

    private void toggleSave() {
        String uid = null;
        try { uid = FirebaseUtils.getCurrentUid(); } catch (Exception ignored) {}
        if (uid == null || soundId.isEmpty()) return;
        isSaved = !isSaved; updateSaveButton();
        bounceView(btnSaveSound);
        SoundDetailCache.getInstance().setSavedStatus(uid, soundId, isSaved); // write-through — instant, no waiting on TTL
        DatabaseReference ref = FirebaseUtils.getUserRef(uid).child("saved_sounds").child(soundId);
        if (isSaved) { ref.setValue(soundTitle); incrementSoundSaves(1); if (isAdded()) Toast.makeText(requireContext(), "Sound saved", Toast.LENGTH_SHORT).show(); }
        else         { ref.removeValue(); incrementSoundSaves(-1); if (isAdded()) Toast.makeText(requireContext(), "Sound removed", Toast.LENGTH_SHORT).show(); }
    }

    private void incrementSoundSaves(int delta) {
        if (soundId.isEmpty()) return;
        FirebaseUtils.db().getReference("sounds").child(soundId).child("total_saves")
            .runTransaction(new Transaction.Handler() {
                @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Long v = d.getValue(Long.class); d.setValue(Math.max(0, (v != null ? v : 0) + delta)); return Transaction.success(d);
                }
                @Override public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
            });
    }

    private void shareSound() {
        if (isGone()) return;
        String text = "Check out this sound: " + soundTitle + (artist.isEmpty() ? "" : " by " + artist);
        Intent i = new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "Share sound via"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Floating bar
    // ─────────────────────────────────────────────────────────────────────────

    private void updateFloatingActionsVisibility() {
        if (layoutFloatingActions == null || btnUseSoundCamera == null || scrollSoundDetail == null) return;
        if (btnUseSoundCamera.getVisibility() != View.VISIBLE) { layoutFloatingActions.setVisibility(View.GONE); return; }
        int[] bl = new int[2], sl = new int[2];
        btnUseSoundCamera.getLocationOnScreen(bl); scrollSoundDetail.getLocationOnScreen(sl);
        layoutFloatingActions.setVisibility((bl[1] + btnUseSoundCamera.getHeight()) < sl[1] ? View.VISIBLE : View.GONE);
    }

    private void showUseAudioDialog() {
        if (isGone()) return;
        AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(soundTitle.isEmpty() ? "Use this sound" : soundTitle)
            .setItems(new String[]{"🎥  Use in Camera", "🎬  Use in Video"}, (d, which) -> {
                if (which == 0) showUseTypeDialog(0);
                else            showUseTypeDialog(1);
            }).setNegativeButton("Cancel", null).create());
    }

    /**
     * ✅ FIX Bug 2: Alert dialog — Full Audio OR Trim Audio.
     * target: 0 = camera, 1 = gallery/video
     */
    private void showUseTypeDialog(int target) {
        if (isGone()) return;
        String title = soundTitle.isEmpty() ? "Use this sound" : soundTitle;
        AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(new String[]{"🎵  Full Audio", "✂️  Trim Audio"}, (d, which) -> {
                if (which == 0) {
                    // Full audio — clear any previous trim and open directly
                    trimStartMs = 0;
                    trimEndMs   = 0;
                    if (target == 0) openCameraWithSound();
                    else             openGalleryForVideo();
                } else {
                    // Trim audio — open trimmer; onActivityResult will open target after
                    pendingUseTarget = target;
                    Intent i = new Intent(requireContext(), com.callx.app.editor.ReelMusicTrimActivity.class);
                    i.putExtra(com.callx.app.editor.ReelMusicTrimActivity.EXTRA_SOUND_ID,    soundId);
                    i.putExtra(com.callx.app.editor.ReelMusicTrimActivity.EXTRA_SOUND_TITLE, soundTitle);
                    i.putExtra(com.callx.app.editor.ReelMusicTrimActivity.EXTRA_SOUND_URL,   soundUrl);
                    i.putExtra(com.callx.app.editor.ReelMusicTrimActivity.EXTRA_DURATION_MS, durationMs);
                    startActivityForResult(i, REQUEST_TRIM_SOUND);
                }
            })
            .setNegativeButton("Cancel", (d, w) -> pendingUseTarget = -1)
            .create());
    }

    /** ✅ FIX Bug 1: Opens camera, always carrying the sound + trim range. */
    private void openCameraWithSound() {
        if (isGone()) return;
        Intent i = new Intent(requireContext(), com.callx.app.camera.ReelCameraActivity.class);
        i.putExtra("selected_sound_id",        soundId);
        i.putExtra("selected_sound_title",     soundTitle);
        i.putExtra("selected_sound_url",       soundUrl);
        i.putExtra("selected_sound_cover",     coverUrl);
        i.putExtra("selected_sound_artist",    artist);
        i.putExtra("replace_audio_with_sound", true);
        if (trimEndMs > trimStartMs) {
            i.putExtra("selected_sound_start_ms", trimStartMs);
            i.putExtra("selected_sound_end_ms",   trimEndMs);
        }
        startActivity(i);
        if (onCloseListener != null) onCloseListener.run();
    }

    /** Opens gallery; onActivityResult 701 will open editor with sound + trim.
     *  Bug fix: was launched via requireActivity().startActivityForResult(),
     *  which delivers the result straight to the host Activity — since
     *  neither SoundDetailActivity nor SoundDetailSheetFragment overrides
     *  onActivityResult() to forward it, this Fragment's own
     *  onActivityResult() (below, request 701) never ran, so picking a
     *  video silently did nothing. Fixed by calling this Fragment's own
     *  startActivityForResult() (same method REQUEST_TRIM_SOUND already
     *  correctly uses above) so FragmentManager routes the result back
     *  here. */
    private void openGalleryForVideo() {
        if (isGone()) return;
        Intent pick = new Intent(Intent.ACTION_PICK,
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        pick.setType("video/*");
        startActivityForResult(pick, 701);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void updatePlayButtonState() {
        if (btnPlayPause == null) return;
        boolean has = !getPlaybackUrl().isEmpty();
        btnPlayPause.setAlpha(has ? 1f : 0.4f); btnPlayPause.setEnabled(has);
        // Instagram-style auto-play: as soon as we have a URL to play (bundle
        // args resolved this synchronously in onViewCreated, or it just
        // landed from the async Firebase fetch in loadSoundData()), start
        // playback without waiting for a tap. Guarded to fire once so a
        // later refresh/relayout of this same screen can't restart it out
        // from under the user if they've already paused it themselves.
        if (has && !autoPlayAttempted && !isGone()) {
            autoPlayAttempted = true;
            togglePlayPause();
        }
    }

    private void updateSaveButton() {
        if (btnSaveSound    != null) btnSaveSound.setImageResource(isSaved ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
        if (btnFloatingSave != null) btnFloatingSave.setText(isSaved ? "✅  Saved" : "🔖  Save");
    }

    private String formatMs(int ms) { int s = ms/1000; return String.format(Locale.US, "%d:%02d", s/60, s%60); }
    private String formatCount(long c) {
        if (c >= 1_000_000) return String.format(Locale.US, "%.1fM", c/1_000_000.0);
        if (c >= 1_000)     return String.format(Locale.US, "%.1fK", c/1_000.0);
        return String.valueOf(c);
    }

    /** DataSnapshot se pehla non-null value lata hai given keys mein se */
    private static String firstOf(DataSnapshot snap, String... keys) {
        for (String k : keys) { String v = snap.child(k).getValue(String.class); if (v != null && !v.isEmpty()) return v; }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Debounced RecyclerView scroll pagination  (ported from UserReelsActivity)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attaches an OnScrollListener to rvReels that debounces the pagination
     * threshold check by 120 ms (same constant as UserReelsActivity).
     *
     * onScrolled() only *schedules* the check, cancelling any previously
     * scheduled one, so rapid scroll events (fast flings, nested-scroll
     * delivery) collapse into a single Firebase read once motion settles.
     * onScrollStateChanged(IDLE) short-circuits directly so the check fires
     * the instant scrolling actually stops instead of waiting out the delay.
     */
    private void setupReelsScrollPagination() {
        if (rvReels == null) return;
        rvReels.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return; // scrolling up — no next-page needed
                reelsPaginationHandler.removeCallbacks(reelsPaginationRunnable);
                reelsPaginationHandler.postDelayed(reelsPaginationRunnable, REELS_PAGINATION_DEBOUNCE_MS);
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // Fling/drag has actually stopped — check right away
                    reelsPaginationHandler.removeCallbacks(reelsPaginationRunnable);
                    maybeLoadMoreReels();
                }
            }
        });
    }

    /**
     * Runs the actual threshold check — only once scrolling has settled.
     * Mirrors UserReelsActivity#maybeLoadNextPage() but adapted for the
     * reels-within-sound-detail grid.
     */
    private void maybeLoadMoreReels() {
        if (isGone() || soundReelsLayoutManager == null) return;
        if (isLoadingMoreReels || !hasMoreReels) return;
        int total       = soundReelsLayoutManager.getItemCount();
        int lastVisible = soundReelsLayoutManager.findLastVisibleItemPosition();
        // Load next page when the user is within 6 items of the end (3-column
        // grid → that's ~2 rows), same threshold as UserReelsActivity.
        if (lastVisible >= total - 6) loadMoreReelsForSound();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Glide RecyclerViewPreloader  (ported from UserReelsActivity)
    // ─────────────────────────────────────────────────────────────────────────

    /** Pre-warm upcoming thumbnails into Glide's cache based on scroll direction,
     *  so cells are already decoded by the time they scroll into view. */
    private void setupGlidePreloaderForReels() {
        if (rvReels == null || reelThumbAdapter == null || isGone()) return;
        // Size each preload request to the same dimensions the adapter uses
        final int thumbPx = (int) (requireContext().getResources().getDisplayMetrics().widthPixels / 3f);
        ListPreloader.PreloadSizeProvider<String> sizeProvider =
                (item, adapterPosition, perItemPosition) -> new int[]{thumbPx, thumbPx};
        ListPreloader.PreloadModelProvider<String> modelProvider =
                new ListPreloader.PreloadModelProvider<String>() {
                    @androidx.annotation.NonNull
                    @Override
                    public java.util.List<String> getPreloadItems(int position) {
                        if (position < 0 || position >= reelItems.size()) return java.util.Collections.emptyList();
                        String url = reelItems.get(position).thumbnailUrl;
                        return (url != null && !url.isEmpty()) ? java.util.Collections.singletonList(url)
                                : java.util.Collections.emptyList();
                    }
                    @androidx.annotation.Nullable
                    @Override
                    public com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> getPreloadRequestBuilder(
                            @androidx.annotation.NonNull String item) {
                        return Glide.with(SoundDetailFragment.this)
                                .load(item)
                                .apply(new RequestOptions().centerCrop().override(thumbPx, thumbPx));
                    }
                };
        RecyclerViewPreloader<String> preloader = new RecyclerViewPreloader<>(
                Glide.with(this), modelProvider, sizeProvider, /* maxPreload= */ 6);
        rvReels.addOnScrollListener(preloader);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cover / header parallax  (ported from UserReelsActivity#setupHeaderParallax)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * As the user scrolls down through the NestedScrollView, the cover art
     * (ivSoundCover / ivDiscRing) trails the scroll at 45% speed and fades
     * out — giving depth instead of sliding 1:1 with content. This mirrors
     * the avatar parallax in UserReelsActivity#setupHeaderParallax().
     *
     * On API 31+ the disc cover additionally gains a gentle blur that grows
     * with scroll depth, so it reads as receding rather than just moving.
     */
    private void setupReelGridParallax() {
        if (scrollSoundDetail == null || ivSoundCover == null) return;
        scrollSoundDetail.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (isGone()) return;
            int scrollY    = scrollSoundDetail.getScrollY();
            // Estimate the "header" height as the cover image's height (fallback 300dp).
            float headerH  = ivSoundCover.getHeight();
            if (headerH <= 0) {
                headerH = requireContext().getResources().getDisplayMetrics().density * 300f;
            }
            float fraction = Math.min(1f, scrollY / headerH);

            // Parallax: cover trails at 45% of actual scroll speed
            float translateY = scrollY * 0.45f;
            ivSoundCover.setTranslationY(translateY);
            ivSoundCover.setAlpha(Math.max(0f, 1f - fraction * 1.3f));

            if (ivDiscRing != null) {
                ivDiscRing.setTranslationY(translateY);
                ivDiscRing.setAlpha(Math.max(0f, 1f - fraction * 1.3f));
                // Scale down slightly as it disappears (UserReelsActivity avatar effect)
                float scale = 1f - (fraction * 0.18f);
                ivDiscRing.setScaleX(scale);
                ivDiscRing.setScaleY(scale);

                // Blur on API 31+ (same as UserReelsActivity#setupHeaderParallax)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    try {
                        if (fraction <= 0.01f) {
                            ivDiscRing.setRenderEffect(null);
                        } else {
                            float blurPx = 1f + fraction * 10f;
                            ivDiscRing.setRenderEffect(
                                android.graphics.RenderEffect.createBlurEffect(
                                    blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP));
                        }
                    } catch (Throwable ignored) {}
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Idle-frame deferral  (ported from UserReelsActivity#runWhenMainThreadIdle)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs {@code r} only once the main thread's message queue is completely
     * idle — i.e. only AFTER the current frame has been measured/laid out/
     * drawn and delivered to the system. Use for low-urgency work (analytics
     * pings, non-critical pre-loads) so it can never contend with the frame
     * the user is waiting to see. Self-removing (returns false from IdleHandler).
     */
    private static void runWhenMainThreadIdle(Runnable r) {
        android.os.Looper.myQueue().addIdleHandler(() -> { r.run(); return false; });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SoundDetailGridLayoutManager  (ported from UserReelsActivity#SwipeAwareGridLayoutManager)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GridLayoutManager variant whose vertical scrolling can be suspended on
     * demand — e.g. while a parent horizontal swipe gesture owns the touch
     * stream. In SoundDetailFragment this also ensures that
     * canScrollVertically() always returns true even when content is shorter
     * than the RecyclerView, so NestedScrollView / CoordinatorLayout always
     * receives nested-scroll events and the floating action bar
     * appears/disappears correctly regardless of grid size.
     *
     * Ported 1-to-1 from UserReelsActivity.SwipeAwareGridLayoutManager.
     */
    private static class SoundDetailGridLayoutManager extends GridLayoutManager {
        private volatile boolean verticalScrollEnabled = true;

        SoundDetailGridLayoutManager(android.content.Context ctx, int spanCount) {
            super(ctx, spanCount);
        }

        /** Call with false at the start of a horizontal swipe, true when it ends. */
        void setVerticalScrollEnabled(boolean enabled) {
            verticalScrollEnabled = enabled;
        }

        @Override
        public boolean canScrollVertically() {
            // Always report scrollable so NestedScrollView keeps receiving
            // nested-scroll events even when the grid content is short — this
            // fixes the floating action bar not hiding when there are few reels.
            return verticalScrollEnabled;
        }
    }
}
