# v282 — Home Tab: Stories Bar Parallel Load (biggest remaining cold-start bottleneck)

## The problem

`collectStoryEntries()` walked the (up to 15) contacts **one at a time**,
and for each contact chained `getUserRef(uid)` → *only then*
`getUserStatusRef(uid)` → *only then* moved to the next contact's pair of
reads. That's up to **30 fully sequential Firebase round-trips** standing
between opening the Home tab and the very first row (the stories bar)
showing anything — the single biggest source of Home's cold-start latency,
bigger than anything in the vertical feed itself since it blocks the very
top of the screen.

Neither read a contact needs (profile, status) depends on the other, and no
contact's reads depend on any other contact's — nothing here actually
required being sequential.

## The fix

`collectStoryEntriesParallel()` fires **all** reads for **all** contacts
concurrently:

- Both of a contact's reads (profile + status) go out together.
- All contacts' reads go out together (capped at 15, same limit as before).
- A per-contact `maybeFinishContact` runnable joins that contact's own pair
  of reads; a shared counter joins all contacts. Firebase's Android SDK
  always delivers `ValueEventListener` callbacks on the main thread one at a
  time, so plain `int[]` counters are enough — no `AtomicInteger`, no
  synchronization needed, even though the underlying network requests race
  in parallel.
- Results land in an **index-addressed** `slots` array rather than being
  appended in completion order, specifically so the final
  `unseen-first` sort ties break in the same original-contact-list order
  the old sequential version produced (`List.sort` is stable — only
  insertion order into the sorted list needs to be preserved).

`collectStoryEntries(uids, index, seenMap, collected)` is kept as a thin
wrapper delegating to the parallel version, so nothing else needed to
change.

## Net effect

Story bar render time drops from "sum of up to 30 round-trips" to
"roughly 1 round-trip" (the slowest of the parallel reads) — this is the
first thing the user sees on the Home tab, so it's a direct hit to
perceived app-open speed, on top of the v281 feed/suggestion-strip
recycling work.
