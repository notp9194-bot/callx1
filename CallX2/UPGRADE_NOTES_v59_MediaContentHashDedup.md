# v59 — Media Content-Hash Dedup (WhatsApp-style instant forward/re-send)

Adds a two-tier content-hash dedup check to the chat media upload path so
sending/forwarding the exact same file skips a fresh Cloudinary upload and
reuses the existing URL instead — matching WhatsApp's "hash first, upload
only on a miss" behavior.

## Why

Every media send previously re-uploaded bytes from scratch, even when the
EXACT same file had already been uploaded seconds earlier (e.g. picking
the same photo from the gallery to send to two different chats). There
was no hash check anywhere in `CloudinaryUploader` — every send was a
full network upload regardless of whether the bytes were new.

## Two tiers, checked in order

1. **Local (Room, this device only)** — `MediaHashCacheEntity` /
   `MediaHashCacheDao` (new table `media_hash_cache`, migration 58→59).
   Zero network calls. Covers the everyday "same file to several chats in
   a row" case.
2. **Server (Firebase-backed, shared across every user)** — new
   `/media/dedup-lookup` and `/media/dedup-register` endpoints
   (`media_dedup/{sha256Hex}` node, server-only access — same access
   pattern as `e2e_prekeys`). A server hit also warms the local cache so
   the next identical send from this device skips even that round trip.

Both are keyed by the SHA-256 of the **exact bytes about to be
uploaded** (post-compression — the same bytes Cloudinary would actually
receive), computed in the new `MediaHashUtil`.

## Where it's wired in

All of it lives inside `CloudinaryUploader#upload` — the single funnel
almost every non-avatar chat media send already goes through (images,
videos, audio/voice notes, GIFs, stickers, files, multi-media group
items). No call site needed to change:

- Right after `readBytes()` succeeds: hash the bytes, call
  `MediaDedupManager.lookup()`. A hit returns the cached `Result`
  straight to the existing `UploadCallback` — sign+upload is skipped
  entirely.
- Right before the success callback on a normal upload: call
  `MediaDedupManager.register()` — local save is a synchronous Room
  insert (cheap), the server register fires on its own thread so it
  never delays the callback.

## New files

- `core/db/entity/MediaHashCacheEntity.java`, `core/db/dao/MediaHashCacheDao.java`
- `core/utils/MediaHashUtil.java` — streaming SHA-256 (byte[] and Uri overloads)
- `core/utils/MediaDedupManager.java` — the two-tier lookup/register orchestration

## Server (`index.js`)

- `POST /media/dedup-lookup` — `{ hash }` → `{ found, result? }`
- `POST /media/dedup-register` — `{ hash, secureUrl, ... }` → `{ ok }`
- Both require `Authorization: Bearer <Firebase ID token>` (existing
  `verifyFirebaseAuth` middleware). Register uses a Firebase transaction
  so the first registration for a given hash wins; later ones are a
  harmless no-op since identical bytes mean the stored URL is already
  correct.
- **Action needed**: add a Firebase Realtime Database rule locking
  `media_dedup` to server-only access (`.read`/`.write`: false), the same
  way `e2e_prekeys` is locked down — every read/write goes through the
  two endpoints above, never a direct client rule.

## Known simplification / scope

- **Not applied to E2E-encrypted media** (the audio/image/video paths
  that call `MediaE2ECrypto` and attach `mediaKeyEnc`). Ciphertext is
  unique per recipient key by design there, so identical plaintext never
  produces identical uploaded bytes — there's nothing to dedup against
  without changing the E2E design itself.
- No TTL/pruning job yet for `media_hash_cache` — `cachedAt` is stored
  and indexed for a future cleanup pass if the table grows large.

---

# v59.1 — Ultra-fast pass: merged round trip, in-memory tier, bounded executors

Follow-up optimization pass. The v59 design above worked but had a real
inefficiency: it added a **whole extra network round trip** to every
single upload, even brand-new files with no dedup benefit at all —
`/media/dedup-lookup` was always called before `/cloudinary/sign`, so a
miss (the common case) paid for both.

## What changed

1. **Dedup check merged into `/cloudinary/sign` (and `/cloudinary/sign/video`)**
   — the client now sends the content hash as an extra field on the sign
   request it needed to make anyway. Server responds either
   `{ dedup: true, result }` (skip the multipart upload entirely) or the
   normal `{ dedup: false, signature, ... }`. Net effect: a cache miss now
   costs the exact same one round trip it always needed for the
   signature; a cache hit resolves in that same one round trip instead of
   two. `/media/dedup-lookup` is kept as a standalone endpoint for any
   other caller, but the main upload path no longer uses it.
2. **Tier 0: in-memory LRU** (`MediaDedupManager.memoryCache`, ~200
   entries, bounded `LinkedHashMap`) sits in front of the Room table.
   Zero I/O — covers rapid multi-forward within one session without
   touching SQLite at all. A Room or server hit warms this tier so the
   very next identical send in the session is instant.
3. **Bounded shared executors replace raw `new Thread()`**:
   - `MediaUploadExecutor` (4 threads) — `CloudinaryUploader#upload`'s
     whole body now runs here instead of a brand-new thread per call.
     Matters most for multi-image group sends, where picking 15-20
     photos previously spun up 15-20 raw threads at once.
   - `MediaDedupExecutor` (2 threads) — `register()`'s local Room insert
     and server call both run here, kept separate from `AppBgExecutor` so
     a slow/flaky server register never queues behind (or blocks) other
     unrelated background writes.
   Both follow the app's existing shared-executor convention
   (`ChatIoExecutor`, `AppBgExecutor`, `E2eeDecryptExecutor`).
4. **`register()` is now fully non-blocking** — the in-memory tier is
   updated synchronously (cheap), then the Room write and the server call
   are both handed off to `MediaDedupExecutor`. The upload's success
   callback fires immediately without waiting on either.
5. **Firebase ID token cached in-memory for ~45 min** in
   `MediaDedupManager` — skips the `Tasks.await()` hop on back-to-back
   registers within a burst (e.g. a multi-image group upload finishing at
   once). Firebase's SDK already avoids a network refresh via
   `getIdToken(false)`, so this only shaves synchronization overhead, not
   a network call — a small win, included for completeness.

## Files touched this pass

- New: `core/utils/MediaUploadExecutor.java`, `core/utils/MediaDedupExecutor.java`
- Rewritten: `core/utils/MediaDedupManager.java` (added tier 0, merged tier 2 into the sign flow, non-blocking register, cached ID token)
- Updated: `core/utils/CloudinaryUploader.java` (uses the new executor, sends `hash` on the sign call, handles `dedup:true` responses)
- Updated: `index.js` — `/cloudinary/sign` and `/cloudinary/sign/video` both accept an optional `hash` and can short-circuit with a dedup hit; `/media/dedup-lookup` kept for other callers; `/media/dedup-register` unchanged in shape

