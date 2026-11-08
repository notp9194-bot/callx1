package com.callx.app.followers;

import android.content.Context;
import android.util.LruCache;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.ChatEntity;
import com.google.firebase.database.DataSnapshot;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v760: profile cache for FollowConnectionsActivity (same idea as TemplateUsesActivity's v758 cache).
 *
 *  - Memory: static LruCache(1500) -> survives re-opening the screen.
 *  - Disk: up to 1000 profiles in SharedPreferences, loaded once per process; stale profiles are still painted
 *    instantly and only re-read from users/{uid} when a row is bound AND the copy is older than 6 h.
 *  - Room seeds: chat partners (name / photo / avatarVersion) are shown immediately without any Firebase read.
 *
 * All disk / Room work runs on {@link #BG} (single ordered thread). FollowConnectionsActivity builds its rows on the
 * same thread, so rows built after {@link #warmUp} always see the disk copy and the seeds.
 */
final class FollowProfileCache {

    private FollowProfileCache() {}

    static final long TTL_MS = 6 * 60 * 60 * 1000L;
    private static final String PREFS = "follow_profiles", KEY = "v1";
    private static final int MEM_MAX = 1500, DISK_MAX = 1000, BIO_MAX = 120;
    private static final long SEEDS_REFRESH_MS = 5 * 60 * 1000L;

    /** Single ordered background thread: disk load/save, Room seeds, row building. */
    static final ExecutorService BG = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "follow-profile-bg");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** Immutable profile snapshot. {@code at == 0} means "seed, not read from users/{uid} yet" (always stale). */
    static final class Prof {
        final String name, username, photo, bio;
        final long avatarVersion, at;

        Prof(String name, String username, String photo, String bio, long avatarVersion, long at) {
            this.name = name; this.username = username; this.photo = photo; this.bio = bio;
            this.avatarVersion = avatarVersion; this.at = at;
        }

        boolean fresh() { return at > 0L && System.currentTimeMillis() - at < TTL_MS; }
    }

    private static final LruCache<String, Prof> MEM = new LruCache<>(MEM_MAX);
    private static volatile Map<String, Prof> seeds = Collections.emptyMap();
    private static volatile long seedsAt;
    private static volatile boolean diskLoaded;

    /** Memory copy first, then a Room seed; null when nothing is known. */
    static Prof get(String uid) {
        if (uid == null) return null;
        final Prof p = MEM.get(uid);
        return p != null ? p : seeds.get(uid);
    }

    static void put(String uid, Prof p) {
        if (uid != null && p != null) MEM.put(uid, p);
    }

    /** Same field choice the screen always used: thumbUrl first, photoUrl as fallback. */
    static Prof parse(DataSnapshot snap, String uid) {
        final String name = snap.child("name").getValue(String.class);
        final String username = snap.child("username").getValue(String.class);
        final String thumb = snap.child("thumbUrl").getValue(String.class);
        final String photo = snap.child("photoUrl").getValue(String.class);
        final String bio = snap.child("bio").getValue(String.class);
        final Long ver = snap.child("avatarVersion").getValue(Long.class);
        final String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
        return new Prof(name != null ? name : uid, username != null ? username : "", p != null ? p : "",
                bio != null ? bio : "", ver != null ? ver : 0L, System.currentTimeMillis());
    }

    /** Loads the disk copy (once per process) and refreshes the Room seeds. Queue this BEFORE building any rows. */
    static void warmUp(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        BG.execute(() -> {
            if (!diskLoaded) {
                try {
                    final String raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
                    if (raw != null) for (String line : raw.split("\n")) {
                        final String[] f = line.split("\t", -1);
                        if (f.length < 7 || f[0].isEmpty() || MEM.get(f[0]) != null) continue;   // fresher in-memory copy wins
                        try {
                            MEM.put(f[0], new Prof(f[1], f[3], f[2], f[4], Long.parseLong(f[5]), Long.parseLong(f[6])));
                        } catch (NumberFormatException ignored) { }
                    }
                } catch (Exception ignored) { }
                diskLoaded = true;
            }
            if (System.currentTimeMillis() - seedsAt > SEEDS_REFRESH_MS) {
                try {
                    final List<ChatEntity> chats = AppDatabase.getInstance(app).chatDao().getAllChatsSync();
                    final HashMap<String, Prof> m = new HashMap<>();
                    if (chats != null) for (ChatEntity c : chats) {
                        if (c == null || c.partnerUid == null || c.partnerUid.isEmpty()) continue;
                        if (c.partnerName == null || c.partnerName.isEmpty()) continue;
                        final String ph = c.partnerThumb != null && !c.partnerThumb.isEmpty() ? c.partnerThumb
                                : (c.partnerPhoto != null ? c.partnerPhoto : "");
                        m.put(c.partnerUid, new Prof(c.partnerName, "", ph, "", c.partnerAvatarVersion, 0L));
                    }
                    seeds = m;
                    seedsAt = System.currentTimeMillis();
                } catch (Exception ignored) { }
            }
        });
    }

    static void saveAsync(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        BG.execute(() -> {
            try {
                final StringBuilder sb = new StringBuilder();
                int n = 0;
                for (Map.Entry<String, Prof> e : MEM.snapshot().entrySet()) {
                    final Prof p = e.getValue();
                    if (p == null || p.at <= 0L) continue;   // seeds are never persisted
                    if (n++ >= DISK_MAX) break;
                    String bio = clean(p.bio);
                    if (bio.length() > BIO_MAX) bio = bio.substring(0, BIO_MAX);
                    sb.append(clean(e.getKey())).append('\t').append(clean(p.name)).append('\t').append(clean(p.photo)).append('\t')
                      .append(clean(p.username)).append('\t').append(bio).append('\t')
                      .append(p.avatarVersion).append('\t').append(p.at).append('\n');
                }
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, sb.toString()).apply();
            } catch (Exception ignored) { }
        });
    }

    private static String clean(String x) {
        return x == null ? "" : x.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
