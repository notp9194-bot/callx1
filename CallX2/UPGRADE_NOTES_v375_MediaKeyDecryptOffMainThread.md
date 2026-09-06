# v375 — MessagePagingAdapter: media-key decrypt moved off the main thread

## Bug
Three call sites inside `bindCanvasMessage()` (called synchronously from
`onBindViewHolder`, i.e. on the main thread, on every RecyclerView bind) ran
`MediaE2ECrypto` decrypt calls inline:

1. Image auto-download — `decryptEnvelopeForMessage()` for every visible
   image message eligible for auto-download.
2. Video thumbnail — `decryptThumbKeyOnly()`, run **unconditionally**, even
   when the decoded thumb bitmap was already in `DECODED_BITMAP_CACHE` and
   the key was never going to be used.
3. Audio warm-download — `decryptKeyOnly()` for every visible voice-note
   bubble not yet locally cached.

All three route through `E2EEncryptionManager#decrypt()`, which — even on a
cache **hit** — acquires a per-partner lock and reads an
`EncryptedSharedPreferences` entry (disk-backed, AES-encrypted); on a cache
**miss** it runs a full Double-Ratchet decrypt. Same class of main-thread
violation the v150 note already fixed for incoming message *text* decrypt,
just not caught for these three media-key sites.

There was also a latent correctness issue: `E2EEncryptionManager#decrypt()`
for a media-key envelope walks the exact same per-partner ratchet
(`lockFor(partnerUid)`) as message-text decrypt. Text decrypt already runs on
the dedicated single-thread `E2eeDecryptExecutor` (v150) for strict FIFO
ordering; these media-key decrypts were running on the main thread — i.e.
two different threads racing to acquire the same per-partner lock, with no
guarantee of ratchet-correct ordering between them.

## Fix
Added three small async wrappers — `resolveFullMediaKeyAsync()`,
`resolveThumbMediaKeyAsync()`, `resolveFullMediaKeyOnlyAsync()` — that post
the decrypt onto **`E2eeDecryptExecutor`** (the same thread text-decrypt
already uses, not a new/separate pool) and hop back to the main thread via a
new `MEDIA_KEY_MAIN_HANDLER` before touching `cv`/`h`. Each checks
`h.canvasBindToken != token` after the hop and drops the result if the
holder was recycled or rebound to a different message in the meantime — same
guard pattern already used everywhere else in this file's async callbacks.

Using the same executor as text-decrypt isn't just for consistency: it
collapses the two-thread ratchet race described above onto one FIFO thread.

The video-thumbnail site also moved the decrypt call from before the
pool-hit check to after it — it's now only ever attempted on a
`DECODED_BITMAP_CACHE` miss, instead of running (and discarding the result)
on every bind of an already-cached video thumb.

## Files touched
- `feature-chat/src/main/java/com/callx/app/conversation/MessagePagingAdapter.java`
