package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room DB entity — disk-persisted L2 cache for {@link com.callx.app.utils.ThumbHashPlaceholder}.
 *
 * The in-memory LruCache(50) in ThumbHashPlaceholder only survives the
 * current process. This table lets a decoded placeholder survive app
 * restart / process death too: on next cold start, ThumbHashPlaceholder's
 * warm-up reads the most-recent rows here straight into the LruCache
 * before any RecyclerView row even binds, so those first binds are an
 * instant cache hit instead of re-running ThumbHash.decode().
 *
 * Stores raw pixel bytes (not a PNG) so restoring a Bitmap is a plain
 * buffer copy (copyPixelsFromBuffer) — no image decode step needed.
 *
 * v70: rows can be either RGB_565 (2 bytes/pixel, the default for opaque
 * hashes as of ThumbHash's advance #2) or ARGB_8888 (4 bytes/pixel, kept
 * for hashes with real alpha — stickers/transparent PNGs) — see
 * {@code configName}. A row's byte layout only makes sense together with
 * its own configName; the two are always written together.
 */
@Entity(
    tableName = "thumbhash_cache",
    indices = { @Index(value = {"cachedAt"}) }
)
public class ThumbHashCacheEntity {

    /** Same key shape ThumbHashPlaceholder already uses in-memory: hash_width_height. */
    @PrimaryKey
    @NonNull
    public String cacheKey = "";

    public int width;
    public int height;

    /** Raw pixel bytes in whatever config configName says (RGB_565 = 2
     *  bytes/pixel, ARGB_8888 = 4 bytes/pixel). */
    public byte[] pixels;

    /** Bitmap.Config enum name ("RGB_565" or "ARGB_8888") the pixels above
     *  were captured in — needed to reconstruct the Bitmap correctly.
     *  Defaults to ARGB_8888 for rows written before v70 introduced RGB_565. */
    public String configName = "ARGB_8888";

    public long cachedAt;

    public ThumbHashCacheEntity() {
        this.cachedAt = System.currentTimeMillis();
    }
}
