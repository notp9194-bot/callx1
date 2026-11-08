package com.callx.app.utils;

import androidx.annotation.Nullable;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * v743: "N used" counter of photo-slot templates — statusTemplateUses/{templateOwnerUid}/{statusId}/{userUid} = server time.
 * Matches the v743 rules: one row per (template, user), never by the template's owner, and only while the template is live.
 * Every call is best-effort (a rejected write just means the counter doesn't move).
 */
public final class StatusTemplateUses {

    /** Rules cap templateFromName at 120 chars. */
    public static final int MAX_NAME = 120;
    /** Rules cap templateJson / templateStickersJson at 60000 chars. */
    public static final int MAX_JSON = 60_000;
    /** v750: rules cap templateLayoutJson (reveal geometry on a status made from a template) at 8000 chars. */
    public static final int MAX_LAYOUT_JSON = 8_000;

    private StatusTemplateUses() { }

    private static boolean ne(@Nullable String s) { return s != null && !s.isEmpty(); }

    /** Trims a display name to what the rules accept. */
    public static String capName(@Nullable String name) {
        if (name == null) return "";
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    /** Counts {@code me} as a user of the template (originUid, originId). Skips self-use and empty ids. */
    public static void record(@Nullable final String originUid, @Nullable final String originId) {
        record(originUid, originId, null, null, null);
    }

    /**
     * v770: same as {@link #record(String, String)} + keeps a per-user \"Used\" index (userTemplateUsed/{me}/{owner_statusId})
     * so My Templates > Used still works after the 24h status is gone. {@code json} / {@code stickers} are an optional snapshot
     * (preview + use again); without it the Used row can only be opened while the original status is live.
     */
    public static void record(@Nullable final String originUid, @Nullable final String originId,
                              @Nullable final String originName, @Nullable final String json, @Nullable final String stickers) {
        try {
            final String me = FirebaseUtils.getCurrentUid();
            if (!ne(originUid) || !ne(originId) || !ne(me) || me.equals(originUid)) return;
            FirebaseUtils.db().getReference("statusTemplateUses").child(originUid).child(originId).child(me)
                    .setValue(ServerValue.TIMESTAMP)
                    // v767: the row is the "who used" list; the counter below is the cheap total + per-day buckets.
                    // Bumped only when the row was accepted (rules: once per user, never owner, not when reuse is off).
                    .addOnSuccessListener(v -> { invalidateCount(originUid, originId); bumpStats(originUid, originId); markUsed(me, originUid, originId, originName, json, stickers); });
        } catch (Exception ignored) { /* counter is best-effort */ }
    }

    /** v770: userTemplateUsed/{me}/{ownerUid_statusId} = { ownerUid, statusId, ownerName?, templateJson?, stickersJson?, ts }. Best-effort. */
    private static void markUsed(@NonNull String me, @NonNull String originUid, @NonNull String originId,
                                 @Nullable String originName, @Nullable String json, @Nullable String stickers) {
        try {
            final Map<String, Object> m = new java.util.HashMap<>();
            m.put("ownerUid", originUid);
            m.put("statusId", originId);
            if (ne(originName)) m.put("ownerName", capName(originName));
            if (ne(json) && json.length() <= MAX_JSON) m.put("templateJson", json);
            if (ne(stickers) && stickers.length() <= MAX_JSON) m.put("stickersJson", stickers);
            m.put("ts", ServerValue.TIMESTAMP);
            FirebaseUtils.db().getReference("userTemplateUsed").child(me).child(originUid + "_" + originId).setValue(m);
        } catch (Exception ignored) { }
    }

    /** v767: templateStats/{owner}/{statusId} = { uses: N, days: { yyyyMMdd (UTC): n } } — one transaction, +1 each. */
    private static void bumpStats(@NonNull String originUid, @NonNull String originId) {
        try {
            final String day = dayKey(System.currentTimeMillis());
            FirebaseUtils.db().getReference("templateStats").child(originUid).child(originId)
                    .runTransaction(new Transaction.Handler() {
                        @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                            final Long u = d.child("uses").getValue(Long.class);
                            d.child("uses").setValue((u != null ? u : 0L) + 1L);
                            final Long n = d.child("days").child(day).getValue(Long.class);
                            d.child("days").child(day).setValue((n != null ? n : 0L) + 1L);
                            return Transaction.success(d);
                        }
                        @Override public void onComplete(DatabaseError e, boolean committed, DataSnapshot s) { /* best-effort */ }
                    }, false);
        } catch (Exception ignored) { }
    }

    /** UTC day bucket key, e.g. 20261108. */
    public static String dayKey(long ms) {
        final SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    public interface CountCb { void onCount(long uses); }

    /**
     * v767: total "N used". Reads the single counter number; templates made before v767 have no counter node,
     * so they fall back to counting the who-used rows (old behaviour). Callback runs on the main thread.
     */
    public static void fetchCount(@Nullable final String ownerUid, @Nullable final String statusId, @NonNull final CountCb cb) {
        if (!ne(ownerUid) || !ne(statusId)) { cb.onCount(0L); return; }
        final String key = ownerUid + "/" + statusId;
        // perf: short-lived cache + in-flight dedupe — N cards / Preview / Status Viewer asking for the same template = ONE read
        synchronized (COUNT_LOCK) {
            final long[] hit = COUNT_CACHE.get(key);
            if (hit != null && System.currentTimeMillis() - hit[1] < COUNT_TTL_MS) { cb.onCount(hit[0]); return; }
            final java.util.List<CountCb> waiting = COUNT_WAIT.get(key);
            if (waiting != null) { waiting.add(cb); return; }
            final java.util.List<CountCb> l = new java.util.ArrayList<>();
            l.add(cb);
            COUNT_WAIT.put(key, l);
        }
        readCount(ownerUid, statusId, n -> {
            final java.util.List<CountCb> done;
            synchronized (COUNT_LOCK) {
                COUNT_CACHE.put(key, new long[]{n, System.currentTimeMillis()});
                done = COUNT_WAIT.remove(key);
            }
            if (done != null) for (CountCb c : done) c.onCount(n);
        });
    }

    /** Forget the cached count (call after the number is known to have changed, e.g. this user just used the template). */
    public static void invalidateCount(@Nullable String ownerUid, @Nullable String statusId) {
        if (!ne(ownerUid) || !ne(statusId)) return;
        synchronized (COUNT_LOCK) { COUNT_CACHE.remove(ownerUid + "/" + statusId); }
    }

    private static final long COUNT_TTL_MS = 60_000L;
    private static final Object COUNT_LOCK = new Object();
    private static final Map<String, long[]> COUNT_CACHE = new LinkedHashMap<String, long[]>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, long[]> e) { return size() > 300; }
    };
    private static final Map<String, java.util.List<CountCb>> COUNT_WAIT = new java.util.HashMap<>();

    private static void readCount(@NonNull final String ownerUid, @NonNull final String statusId, @NonNull final CountCb cb) {
        try {
            FirebaseUtils.db().getReference("templateStats").child(ownerUid).child(statusId).child("uses")
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            final Long n = s.getValue(Long.class);
                            if (n != null) { cb.onCount(Math.max(0L, n)); return; }
                            FirebaseUtils.db().getReference("statusTemplateUses").child(ownerUid).child(statusId)
                                    .addListenerForSingleValueEvent(new ValueEventListener() {
                                        @Override public void onDataChange(@NonNull DataSnapshot r) { cb.onCount(r.getChildrenCount()); }
                                        @Override public void onCancelled(@NonNull DatabaseError e) { cb.onCount(0L); }
                                    });
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) { cb.onCount(0L); }
                    });
        } catch (Exception e) { cb.onCount(0L); }
    }

    /** Counter node as read for analytics: total + per-day buckets (UTC yyyyMMdd → n). {@code total < 0} = no counter (old template). */
    public static final class Stats {
        public long total = -1;
        public final Map<String, Long> days = new LinkedHashMap<>();
    }

    public interface StatsCb { void onStats(@NonNull Stats stats); }

    public static void fetchStats(@Nullable String ownerUid, @Nullable String statusId, @NonNull final StatsCb cb) {
        final Stats out = new Stats();
        if (!ne(ownerUid) || !ne(statusId)) { cb.onStats(out); return; }
        try {
            FirebaseUtils.db().getReference("templateStats").child(ownerUid).child(statusId)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            final Long u = s.child("uses").getValue(Long.class);
                            if (u != null) out.total = Math.max(0L, u);
                            for (DataSnapshot c : s.child("days").getChildren()) {
                                final Long n = c.getValue(Long.class);
                                if (c.getKey() != null && n != null) out.days.put(c.getKey(), n);
                            }
                            cb.onStats(out);
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) { cb.onStats(out); }
                    });
        } catch (Exception e) { cb.onStats(out); }
    }

    /** Owner removes the whole counter of a status he deleted. */
    public static void clear(@Nullable String ownerUid, @Nullable String statusId) {
        try {
            final String me = FirebaseUtils.getCurrentUid();
            if (!ne(ownerUid) || !ne(statusId) || !ne(me) || !me.equals(ownerUid)) return;
            invalidateCount(ownerUid, statusId);
            FirebaseUtils.db().getReference("statusTemplateUses").child(ownerUid).child(statusId).removeValue();
            FirebaseUtils.db().getReference("templateStats").child(ownerUid).child(statusId).removeValue(); // v767
            FirebaseUtils.db().getReference("templateControl").child(ownerUid).child(statusId).removeValue(); // v750: no stale switch
        } catch (Exception ignored) { }
    }
}
