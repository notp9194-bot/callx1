package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room DB entity — local (this-device-only) content-hash → uploaded-URL
 * dedup cache.
 *
 * WHY THIS EXISTS (WhatsApp-parity "instant forward"): before this, every
 * media send/forward re-uploaded the file's bytes to Cloudinary from
 * scratch, even when the EXACT same bytes had already been uploaded by
 * this device moments earlier (e.g. picking the same photo from the
 * gallery to send to two different chats). WhatsApp instead hashes the
 * file first and, on a hash match, skips the upload entirely and reuses
 * the existing URL — forwarding becomes instant with zero bytes sent.
 *
 * Keyed by the SHA-256 hex digest of the exact bytes that were uploaded
 * (post-compression — the same bytes Cloudinary actually received), so a
 * hash hit here is a guarantee the stored secureUrl/thumbnailUrl already
 * serve identical content. See MediaHashUtil for how the hash is computed
 * and MediaDedupManager for how this table is read/written.
 *
 * This is tier 1 of the two-tier dedup (see MediaDedupManager's class
 * doc for tier 2, the server-wide check) — it never requires a network
 * call, so it's checked first.
 */
@Entity(
    tableName = "media_hash_cache",
    indices = { @Index(value = {"cachedAt"}) }
)
public class MediaHashCacheEntity {

    @PrimaryKey
    @NonNull
    public String hash = "";

    public String secureUrl;
    public String thumbnailUrl;
    public String publicId;
    public String resourceType;
    public String format;
    public Long bytes;
    public Long durationMs;

    /** Wall-clock time this row was written — reserved for future TTL pruning. */
    public long cachedAt;
}
