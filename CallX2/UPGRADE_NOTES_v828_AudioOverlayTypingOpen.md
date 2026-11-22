# v828 — Audio overlay, typing alloc, chat-open
1. Voice note playing no longer bypasses the bubble cache: live row (button, waveform+progress, elapsed) drawn in the overlay via `AudioRenderer.drawRow()`; background/reply/footer stay in the cached RenderNode. Play-state flips set `fullBubbleDirty`; progress/elapsed ticks don't.
2. `ChatActivity.handleMessageTextChanged`: no per-keystroke String/trim allocation (`TextUtils.getTrimmedLength`); `toString()` only when live-typing or a link-preview consumer needs it.
3. Chat open: `LastMessagesDiskCache.loadIntoMemory` runs on the IO executor from onCreate, joined (250 ms max) in `seedInstantRenderFromCache()`; `setupNetworkMonitor()` posted after first traversal.
4. Firebase->Room batching: already present (30 ms debounce + `applyBufferedChanges` transaction + version-coalesced `saveAsync`), no change.
Not compiled offline — run CI.
