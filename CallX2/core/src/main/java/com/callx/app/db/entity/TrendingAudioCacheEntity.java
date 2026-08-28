package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/**
 * Room DB entity — offline cache for ReelTrendingAudioActivity (v50).
 *
 * Caches the last-loaded page of the Trending Audio browser (both the
 * `musicLibrary` list and the user-generated `sounds` list) so reopening
 * the screen paints instantly from disk instead of always waiting on a
 * fresh Firebase read. `source` distinguishes the two lists ("library" /
 * "sounds") since ids aren't guaranteed unique across them; `sortOrder`
 * preserves the order they were displayed in on last load. Firebase is
 * still always re-queried on open — this table only fills the gap before
 * that response lands (and covers a fully-offline cold open).
 */
@Entity(
    tableName = "trending_audio_cache",
    primaryKeys = { "audioId", "source" },
    indices = { @Index(value = {"source", "sortOrder"}) }
)
public class TrendingAudioCacheEntity {

    @NonNull
    public String audioId = "";

    /** "library" (musicLibrary/) or "sounds" (sounds/). */
    @NonNull
    public String source = "library";

    public String title;
    public String artist;
    public String audioUrl;
    public String previewAudioUrl;
    public String coverUrl;
    public String genre;
    public String mood;
    public long   usageCount;
    public long   durationMs;
    public long   trendingRank;
    public int    bpm;
    public long   addedAt;

    /** Position within the cached page — lets us restore original ordering. */
    public int    sortOrder;

    public long   cachedAt;

    public TrendingAudioCacheEntity() {
        this.cachedAt = System.currentTimeMillis();
    }
}
