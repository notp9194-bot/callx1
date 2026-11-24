# v835e — lock gate fast-path sync when prefs warm, async only if cold; timing marks + adapter/bubble accumulators + ChatLockGate prefs build moved off main thread

- New `core/.../debug/OpenPerfLog` (always on, debug + release, ~zero cost).
- `ChatActivity`: `mark()` at onCreate start, inflate, RV setup, input bar, listeners/db warm,
  db ready, onCreate end, first data ready, first real frame.
- Read it: chat → 3-dot → "🐞 Paging Debug Log" → top section "CHAT OPEN TIMING".
- Copy button copies everything; Clear resets both logs.
- Open the chat 2-3 times (cold + warm), compare gaps between marks.
