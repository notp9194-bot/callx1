package com.callx.app.cache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.ReelFirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SoundDetailCache — app-wide singleton cache for the Sound Detail screen
 * (SoundDetailFragment, used by both SoundDetailActivity and
 * SoundDetailSheetFragment).
 *
 * WHY THIS EXISTS (PERF):
 * Before this cache, opening the Sound Detail screen fired 10+ independent
 * addListenerForSingleValueEvent calls scattered across the fragment —
 * sound node, musicLibrary fallback, creator profile (reelUsers, then a
 * users/ fallback), follow status, saved status, related sounds, pinned
 * reel id — every single time the screen opened, even for a sound (or
 * creator) already resolved seconds earlier. That last part matters more
 * than it looks: loadRelatedSounds()'s onClick REPLACES the fragment with a
 * brand-new instance for the next sound, so hopping through 3-4 related
 * sounds re-ran the entire cascade from zero every time, including
 * re-resolving the exact same creator/follow state repeatedly.
 *
 * This cache fixes that the same way MutualFollowersCache does for the reel
 * bio row: short-TTL, session-scoped, in-memory layers, keyed so a repeat
 * lookup (revisiting a sound, or a creator who appears on multiple sounds)
 * costs ZERO Firebase reads until the TTL expires. Concurrent identical
 * in-flight requests (e.g. two SoundDetailFragment instances resolving the
 * same creator within the same frame) are collapsed into a single round
 * trip, same pattern as MutualFollowersCache's inFlight maps.
 *
 * "Combined multi-path fetch": on screen open, SoundDetailFragment now
 * fires getSoundData() + getSavedStatus() in parallel (independent paths),
 * then getCreatorProfile() + getFollowStatus() in parallel once creatorUid
 * is known from the sound read — instead of the previous serial/ad-hoc
 * chain of separate listeners. getPinnedReelId() and getRelatedSounds()
 * remain fully independent, on-demand fetches (pinned reel is only needed
 * for the owner long-press menu; related sounds only once genre is known).
 *
 * Deliberately NOT covered here: fetchViewCountsForPage()'s per-reel
 * viewsCount reads. Those are inherently one distinct, frequently-changing
 * value per paginated grid item (not a single "open this sound" value), so
 * folding them into a TTL cache would mostly just serve stale view counts
 * on a screen whose whole point is showing per-reel numbers — not worth it.
 */
public final class SoundDetailCache {

    private static SoundDetailCache sInstance;

    // Reel/save/trending counters on the sound node change often but a few
    // tens of seconds of staleness is invisible, and this is what saves a
    // full re-read every time a related-sound hop replaces the fragment.
    private static final long SOUND_TTL_MS           = 45_000L;
    // Name/photo rarely changes — long TTL, same reasoning as
    // MutualFollowersCache's PROFILE_TTL_MS, and reused across every sound
    // by the same creator (not just the current one).
    private static final long CREATOR_PROFILE_TTL_MS = 5 * 60_000L;
    // Saved/follow are also written-through immediately on toggle (see
    // setSavedStatus/setFollowStatus below), so the TTL here only matters
    // for state changed from ANOTHER device/session.
    private static final long SAVED_STATUS_TTL_MS    = 60_000L;
    private static final long FOLLOW_STATUS_TTL_MS    = 2 * 60_000L;
    private static final long RELATED_TTL_MS          = 3 * 60_000L;
    private static final long PINNED_REEL_TTL_MS       = 3 * 60_000L;

    private SoundDetailCache() {}

    public static synchronized SoundDetailCache getInstance() {
        if (sInstance == null) sInstance = new SoundDetailCache();
        return sInstance;
    }

    // ── Generic short-TTL entry wrapper ─────────────────────────────────────

    private static final class Entry<T> {
        final T value;
        final long fetchedAtMs;
        Entry(T value) { this.value = value; this.fetchedAtMs = System.currentTimeMillis(); }
        boolean isFresh(long ttlMs) { return System.currentTimeMillis() - fetchedAtMs < ttlMs; }
    }

    // ── Sound node data ──────────────────────────────────────────────────────

    /** Everything SoundDetailFragment.loadSoundData()/loadSoundDataFromMusicLibrary() used to read, in one place. */
    public static final class SoundNodeEntry {
        public boolean found;              // true if resolved from either sounds/ or musicLibrary/
        public boolean fromMusicLibrary;    // true if resolved via the musicLibrary/ fallback path
        public Long reelCount, trendingRank, totalSaves;
        public Boolean isOriginal, isVerified, isTrending;
        public String creatorUid, creatorName, creatorPhoto;
        public String audioUrl, previewAudioUrl, coverUrl;
        public Integer durationMs;
    }

    public interface SoundDataCallback { void onReady(@NonNull SoundNodeEntry entry); }

    private final Map<String, Entry<SoundNodeEntry>> soundCache = new HashMap<>();
    private final Map<String, List<SoundDataCallback>> inFlightSound = new HashMap<>();

    public void getSoundData(@NonNull String soundId, @NonNull SoundDataCallback callback) {
        Entry<SoundNodeEntry> cached = soundCache.get(soundId);
        if (cached != null && cached.isFresh(SOUND_TTL_MS)) { callback.onReady(cached.value); return; }

        List<SoundDataCallback> waiters = inFlightSound.get(soundId);
        if (waiters != null) { waiters.add(callback); return; }
        waiters = new ArrayList<>();
        waiters.add(callback);
        inFlightSound.put(soundId, waiters);

        FirebaseUtils.db().getReference("sounds").child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (snap.exists()) {
                        finishSound(soundId, buildFromSoundsNode(snap));
                    } else {
                        fetchFromMusicLibrary(soundId);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    fetchFromMusicLibrary(soundId);
                }
            });
    }

    private void fetchFromMusicLibrary(String soundId) {
        FirebaseUtils.getMusicLibraryRef().child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    finishSound(soundId, snap.exists() ? buildFromMusicLibraryNode(snap) : new SoundNodeEntry());
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    finishSound(soundId, new SoundNodeEntry()); // found=false — caller degrades gracefully, same as old onCancelled paths
                }
            });
    }

    private void finishSound(String soundId, SoundNodeEntry entry) {
        soundCache.put(soundId, new Entry<>(entry));
        List<SoundDataCallback> pending = inFlightSound.remove(soundId);
        if (pending != null) for (SoundDataCallback cb : pending) cb.onReady(entry);
    }

    private static SoundNodeEntry buildFromSoundsNode(DataSnapshot snap) {
        SoundNodeEntry e = new SoundNodeEntry();
        e.found = true;
        e.reelCount    = snap.child("reel_count").getValue(Long.class);
        e.trendingRank = snap.child("trending_rank").getValue(Long.class);
        e.totalSaves   = snap.child("total_saves").getValue(Long.class);
        e.isOriginal   = snap.child("is_original").getValue(Boolean.class);
        e.isVerified   = snap.child("is_verified").getValue(Boolean.class);
        e.isTrending   = snap.child("is_trending").getValue(Boolean.class);
        e.creatorUid   = snap.child("creatorUid").getValue(String.class);
        e.creatorName  = snap.child("creatorName").getValue(String.class);
        e.creatorPhoto = snap.child("creatorPhoto").getValue(String.class);
        for (String key : new String[]{"audioUrl", "audio_url", "url"}) {
            String u = snap.child(key).getValue(String.class);
            if (u != null && !u.isEmpty()) { e.audioUrl = u; break; }
        }
        String pu = snap.child("previewAudioUrl").getValue(String.class);
        if (pu != null) e.previewAudioUrl = pu;
        for (String key : new String[]{"coverUrl", "cover_url"}) {
            String c = snap.child(key).getValue(String.class);
            if (c != null && !c.isEmpty()) { e.coverUrl = c; break; }
        }
        for (String key : new String[]{"duration_ms", "durationMs"}) {
            Long d = snap.child(key).getValue(Long.class);
            if (d != null && d > 0) { e.durationMs = d.intValue(); break; }
        }
        return e;
    }

    private static SoundNodeEntry buildFromMusicLibraryNode(DataSnapshot snap) {
        SoundNodeEntry e = new SoundNodeEntry();
        e.found = true;
        e.fromMusicLibrary = true;
        String u = snap.child("audioUrl").getValue(String.class);
        if (u != null && !u.isEmpty()) e.audioUrl = u;
        String pu = snap.child("previewAudioUrl").getValue(String.class);
        if (pu != null && !pu.isEmpty()) e.previewAudioUrl = pu;
        String c = snap.child("coverUrl").getValue(String.class);
        if (c != null && !c.isEmpty()) e.coverUrl = c;
        Long d = snap.child("durationMs").getValue(Long.class);
        if (d != null && d > 0) e.durationMs = d.intValue();
        String uUid = snap.child("uploadedByUid").getValue(String.class);
        if (uUid != null && !uUid.isEmpty()) {
            e.creatorUid = uUid;
            String uName = snap.child("uploadedByName").getValue(String.class);
            if (uName != null && !uName.isEmpty()) e.creatorName = uName;
        }
        return e;
    }

    /** Call after edits that change sound-node counters the fragment itself just wrote (e.g. save toggle already updates total_saves via transaction) so the next open re-fetches instead of serving a stale count for up to SOUND_TTL_MS. Safe no-op if nothing cached. */
    public void invalidateSound(String soundId) { soundCache.remove(soundId); }

    // ── Creator profile ──────────────────────────────────────────────────────

    public static final class ProfileEntry {
        public final String name, photo;
        ProfileEntry(String name, String photo) { this.name = name; this.photo = photo; }
    }

    public interface ProfileCallback { void onReady(@NonNull ProfileEntry profile); }

    private final Map<String, Entry<ProfileEntry>> profileCache = new HashMap<>();
    private final Map<String, List<ProfileCallback>> inFlightProfile = new HashMap<>();

    public void getCreatorProfile(@NonNull String uid, @NonNull ProfileCallback callback) {
        Entry<ProfileEntry> cached = profileCache.get(uid);
        if (cached != null && cached.isFresh(CREATOR_PROFILE_TTL_MS)) { callback.onReady(cached.value); return; }

        List<ProfileCallback> waiters = inFlightProfile.get(uid);
        if (waiters != null) { waiters.add(callback); return; }
        waiters = new ArrayList<>();
        waiters.add(callback);
        inFlightProfile.put(uid, waiters);

        ReelFirebaseUtils.reelUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String name  = firstOf(snap, "displayName", "handle");
                String photo = firstOf(snap, "photoUrl", "thumbUrl");
                if (name != null && !name.isEmpty()) finishProfile(uid, new ProfileEntry(name, photo));
                else fetchFromMainUsersNode(uid);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { fetchFromMainUsersNode(uid); }
        });
    }

    private void fetchFromMainUsersNode(String uid) {
        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String name  = firstOf(snap, "displayName", "username", "name");
                String photo = firstOf(snap, "photoUrl", "profilePic", "avatar");
                finishProfile(uid, new ProfileEntry(name != null ? name : "Unknown", photo));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                finishProfile(uid, new ProfileEntry("Unknown", null));
            }
        });
    }

    private void finishProfile(String uid, ProfileEntry entry) {
        profileCache.put(uid, new Entry<>(entry));
        List<ProfileCallback> pending = inFlightProfile.remove(uid);
        if (pending != null) for (ProfileCallback cb : pending) cb.onReady(entry);
    }

    // ── Saved status ──────────────────────────────────────────────────────────

    public interface BoolCallback { void onReady(boolean value); }

    private final Map<String, Entry<Boolean>> savedCache = new HashMap<>();
    private final Map<String, List<BoolCallback>> inFlightSaved = new HashMap<>();

    public void getSavedStatus(@NonNull String myUid, @NonNull String soundId, @NonNull BoolCallback callback) {
        String key = myUid + "|" + soundId;
        Entry<Boolean> cached = savedCache.get(key);
        if (cached != null && cached.isFresh(SAVED_STATUS_TTL_MS)) { callback.onReady(cached.value); return; }

        List<BoolCallback> waiters = inFlightSaved.get(key);
        if (waiters != null) { waiters.add(callback); return; }
        waiters = new ArrayList<>();
        waiters.add(callback);
        inFlightSaved.put(key, waiters);

        FirebaseUtils.getUserRef(myUid).child("saved_sounds").child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    boolean saved = snap.exists();
                    savedCache.put(key, new Entry<>(saved));
                    List<BoolCallback> pending = inFlightSaved.remove(key);
                    if (pending != null) for (BoolCallback cb : pending) cb.onReady(saved);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    inFlightSaved.remove(key);
                    callback.onReady(false); // don't cache a failed read — next open should retry
                }
            });
    }

    /** Write-through — call right after toggleSave() flips isSaved, so this device's own toggle is reflected instantly without waiting on a re-fetch. */
    public void setSavedStatus(String myUid, String soundId, boolean saved) {
        savedCache.put(myUid + "|" + soundId, new Entry<>(saved));
    }

    // ── Follow status ─────────────────────────────────────────────────────────

    private final Map<String, Entry<Boolean>> followCache = new HashMap<>();
    private final Map<String, List<BoolCallback>> inFlightFollow = new HashMap<>();

    private static String followKey(String myUid, String targetUid) { return myUid + "|" + targetUid; }

    public void getFollowStatus(@NonNull String myUid, @NonNull String targetUid, @NonNull BoolCallback callback) {
        String key = followKey(myUid, targetUid);
        Entry<Boolean> cached = followCache.get(key);
        if (cached != null && cached.isFresh(FOLLOW_STATUS_TTL_MS)) { callback.onReady(cached.value); return; }

        List<BoolCallback> waiters = inFlightFollow.get(key);
        if (waiters != null) { waiters.add(callback); return; }
        waiters = new ArrayList<>();
        waiters.add(callback);
        inFlightFollow.put(key, waiters);

        FirebaseUtils.getReelFollowsRef(myUid).child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    boolean following = snap.exists() && Boolean.TRUE.equals(snap.getValue(Boolean.class));
                    followCache.put(key, new Entry<>(following));
                    List<BoolCallback> pending = inFlightFollow.remove(key);
                    if (pending != null) for (BoolCallback cb : pending) cb.onReady(following);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    inFlightFollow.remove(key);
                    callback.onReady(false); // don't cache a failed read — next bind should retry
                }
            });
    }

    /** Write-through — call right after the Follow/Following button toggles, so it's reflected instantly for this device across every sound/reel screen sharing this cache. */
    public void setFollowStatus(String myUid, String targetUid, boolean following) {
        followCache.put(followKey(myUid, targetUid), new Entry<>(following));
    }

    // ── Creator follower count ───────────────────────────────────────────────
    // PERF: SoundDetailFragment's "@username · N followers" line used to fire
    // a fresh users/{uid}/followersCount read on every bindCreatorRow() call —
    // i.e. every single sound-detail open, even for the same creator seen
    // seconds earlier via a related-sound hop (which replaces the fragment
    // instance, see class doc above). Same TTL as the creator profile itself:
    // a follower count a few minutes stale is invisible, and it's shared
    // across every sound by that creator this session, not just the current one.

    private final Map<String, Entry<Long>> followerCountCache = new HashMap<>();
    private final Map<String, List<LongCallback>> inFlightFollowerCount = new HashMap<>();

    public interface LongCallback { void onReady(long value); }

    public void getFollowerCount(@NonNull String uid, @NonNull LongCallback callback) {
        Entry<Long> cached = followerCountCache.get(uid);
        if (cached != null && cached.isFresh(CREATOR_PROFILE_TTL_MS)) { callback.onReady(cached.value); return; }

        List<LongCallback> waiters = inFlightFollowerCount.get(uid);
        if (waiters != null) { waiters.add(callback); return; }
        waiters = new ArrayList<>();
        waiters.add(callback);
        inFlightFollowerCount.put(uid, waiters);

        // ✅ FIX: users/{uid}/followersCount is a denormalized counter that's
        // only ever decremented (one unfollow path) and never incremented
        // anywhere in the app, so it always reads 0/stale. The real,
        // reliable source — same one FollowersListActivity's header count
        // uses — is reelFollowers/{uid}'s child count.
        FirebaseUtils.getReelFollowersRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    long value = snap.getChildrenCount();
                    followerCountCache.put(uid, new Entry<>(value));
                    List<LongCallback> pending = inFlightFollowerCount.remove(uid);
                    if (pending != null) for (LongCallback cb : pending) cb.onReady(value);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    inFlightFollowerCount.remove(uid);
                    callback.onReady(0L); // don't cache a failed read — next bind should retry
                }
            });
    }

    /** Write-through — call right after a follow/unfollow toggle changes this uid's follower count by ±1, so it's reflected instantly without a fresh Firebase read. */
    public void adjustFollowerCountLocally(String uid, int delta) {
        Entry<Long> cached = followerCountCache.get(uid);
        long base = cached != null ? cached.value : 0L;
        followerCountCache.put(uid, new Entry<>(Math.max(0L, base + delta)));
    }

    /** Write-through — call after a follow/unfollow toggle changes the target's own count locally, if that ever gets tracked client-side. Not currently called (server-authoritative counter), kept for parity with the other write-through setters. */
    public void invalidateFollowerCount(String uid) { followerCountCache.remove(uid); }

    // ── Pinned reel id ────────────────────────────────────────────────────────

    private final Map<String, Entry<String>> pinnedReelCache = new HashMap<>();
    private final Map<String, List<StringCallback>> inFlightPinned = new HashMap<>();

    public interface StringCallback { void onReady(@Nullable String value); }

    public void getPinnedReelId(@NonNull String uid, @NonNull StringCallback callback) {
        Entry<String> cached = pinnedReelCache.get(uid);
        if (cached != null && cached.isFresh(PINNED_REEL_TTL_MS)) { callback.onReady(cached.value); return; }

        List<StringCallback> waiters = inFlightPinned.get(uid);
        if (waiters != null) { waiters.add(callback); return; }
        waiters = new ArrayList<>();
        waiters.add(callback);
        inFlightPinned.put(uid, waiters);

        FirebaseUtils.db().getReference("reelPinned").child(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String id = snap.getValue(String.class);
                    pinnedReelCache.put(uid, new Entry<>(id));
                    List<StringCallback> pending = inFlightPinned.remove(uid);
                    if (pending != null) for (StringCallback cb : pending) cb.onReady(id);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    inFlightPinned.remove(uid);
                    callback.onReady(null); // don't cache a failed read — next peek should retry
                }
            });
    }

    /** Write-through for pin/unpin so every open of this uid's grids reflects it instantly. */
    public void setPinnedReelId(String uid, @Nullable String reelId) {
        pinnedReelCache.put(uid, new Entry<>(reelId));
    }

    // ── Related sounds (by genre) ────────────────────────────────────────────

    public static final class RelatedEntry {
        public final String id, title, artist, coverUrl, audioUrl;
        public RelatedEntry(String id, String title, String artist, String coverUrl, String audioUrl) {
            this.id = id; this.title = title; this.artist = artist; this.coverUrl = coverUrl; this.audioUrl = audioUrl;
        }
    }

    public interface RelatedCallback { void onReady(@NonNull List<RelatedEntry> items); }

    private final Map<String, Entry<List<RelatedEntry>>> relatedCache = new HashMap<>();
    private final Map<String, List<RelatedCallback>> inFlightRelated = new HashMap<>();

    /** Cached by genre only (not by soundId) — the same "Pop" list is reused
     *  whichever pop song opened it; caller filters out its own soundId locally. */
    public void getRelatedSounds(@NonNull String genre, @NonNull RelatedCallback callback) {
        Entry<List<RelatedEntry>> cached = relatedCache.get(genre);
        if (cached != null && cached.isFresh(RELATED_TTL_MS)) { callback.onReady(cached.value); return; }

        List<RelatedCallback> waiters = inFlightRelated.get(genre);
        if (waiters != null) { waiters.add(callback); return; }
        waiters = new ArrayList<>();
        waiters.add(callback);
        inFlightRelated.put(genre, waiters);

        FirebaseUtils.getMusicLibraryRef().orderByChild("genre").equalTo(genre).limitToFirst(10)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<RelatedEntry> items = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        String id    = s.getKey();
                        String title = firstOf(s, "title", "name");
                        String art   = s.child("artist").getValue(String.class);
                        String cover = s.child("coverUrl").getValue(String.class);
                        String url   = s.child("audioUrl").getValue(String.class);
                        if (title != null) items.add(new RelatedEntry(
                                n(id), title, n(art), n(cover), n(url)));
                    }
                    relatedCache.put(genre, new Entry<>(items));
                    List<RelatedCallback> pending = inFlightRelated.remove(genre);
                    if (pending != null) for (RelatedCallback cb : pending) cb.onReady(items);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    inFlightRelated.remove(genre);
                    callback.onReady(new ArrayList<>());
                }
            });
    }

    // ── Small shared helpers (same semantics as SoundDetailFragment's private ones) ──

    private static String firstOf(DataSnapshot snap, String... keys) {
        for (String k : keys) {
            String v = snap.child(k).getValue(String.class);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private static String n(String s) { return s != null ? s : ""; }
}
