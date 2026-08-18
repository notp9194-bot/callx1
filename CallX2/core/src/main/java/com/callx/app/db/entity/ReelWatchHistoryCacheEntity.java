package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Local cache of the CURRENT device's signed-in user's own reel-watch
 * history — mirrors the server-side reelWatchHistory/{myUid}/{reelId}
 * timestamp written by ReelSocialController#recordView().
 *
 * Purely local (device-wide "callx_database", not scoped per-uid — like the
 * rest of Room's tables here, it's wiped on account switch, see
 * AppDatabase#wipeForAccountSwitch()) and purely a lookup cache: the whole
 * point is to answer "have I already watched reel X?" instantly, from disk,
 * without a Firebase round-trip every time ANY profile's Reels grid opens —
 * this is what powers the Instagram-style "Just watched" grid overlay (see
 * ReelGridAdapter#setWatchedReelIds / UserReelsActivity#loadWatchedReelIds).
 */
@Entity(
    tableName = "reel_watch_history_cache",
    indices = { @Index(value = {"watchedAt"}) }
)
public class ReelWatchHistoryCacheEntity {

    @PrimaryKey
    @NonNull
    public String reelId = "";

    public long watchedAt = 0L;

    public ReelWatchHistoryCacheEntity() {}

    public ReelWatchHistoryCacheEntity(@NonNull String reelId, long watchedAt) {
        this.reelId = reelId;
        this.watchedAt = watchedAt;
    }
}
