package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room DB entity — disk-backed link preview cache.
 *
 * LinkPreviewFetcher (feature-chat) previously cached OG-tag/oEmbed results
 * ONLY in an in-memory LinkedHashMap (LRU, cap 200) — every value was lost
 * the instant the process died, so the very same link shared in a chat
 * always paid a full network round-trip (OG scrape or YouTube oEmbed call)
 * again on the next cold app open, even though the linked page's title/
 * image/description had not changed. This table persists that same Result
 * across process death so a cold app open can paint a previously-seen
 * link preview instantly from disk instead of waiting on the network.
 *
 * Keyed by the raw URL string (matches the in-memory cache's key) so a
 * disk hit can be dropped straight into that in-memory map as-is.
 */
@Entity(
    tableName = "link_preview_cache",
    indices = { @Index(value = {"cachedAt"}) }
)
public class LinkPreviewCacheEntity {

    @PrimaryKey
    @NonNull
    public String url = "";

    public String title;
    public String domain;
    public String imageUrl;
    public String description;

    /** Wall-clock time this row was written — used for TTL pruning (see
     *  LinkPreviewCacheManager.MAX_AGE_MS) since a page's OG tags can
     *  eventually change even though this isn't a live subscription. */
    public long cachedAt;
}
