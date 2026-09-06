# v370 — ImageCompressor: parallel multi-image compression (WhatsApp-level)

## The problem

`ImageCompressor.BG` was `Executors.newSingleThreadExecutor()`. Every photo
in a multi-image send (album/multi-select picker) queued onto that single
thread and compressed strictly one at a time — a 10-photo album send
compressed sequentially even on an 8-core phone, while WhatsApp compresses
several images in parallel.

## The fix

`BG` is now a bounded `newFixedThreadPool`, sized to
`clamp(availableProcessors() - 1, 2, 4)` — parallel, but capped rather
than unbounded:

- Each concurrent compress job holds a full `ARGB_8888` decode + resized
  bitmap in memory at once. Unbounded parallelism on an 8-core device
  sending a 10-photo album could spike memory hard on lower-RAM phones —
  2–4 concurrent jobs (matching WhatsApp's own observed concurrency here)
  keeps peak memory bounded regardless of core count.
- Threads run at `NORM_PRIORITY - 1` so several compress jobs running
  alongside UI work (scrolling, other sends) don't compete with it.

## Correctness check (why this was safe to parallelize)

- `ImageCompressor` has zero shared mutable state across calls — every
  step (`decodeSampled`, `fixExifRotation`, `resize`,
  `centerCropSquare`, `writeWebP`) works on purely local `Bitmap`/`File`
  objects, and output filenames are UUID-random, so concurrent calls
  never collide.
- Checked all 4 call sites (`ChatMediaController`, `GroupChatActivity`):
  multi-image sends already track each item by `index`/`itemIdx` and
  update `liveItems`/grid state by that index, not by assuming
  compress-callback completion order — so photos finishing out of order
  (the whole point of parallelizing) was already handled correctly
  upstream. No caller needed changes.

Net effect: a 10-photo album send now compresses ~2-4x faster on modern
phones (bounded by the 4-thread cap, not full core count, to protect
memory) instead of one strictly sequential queue.
