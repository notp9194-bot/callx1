# v857 — Download progress tick no longer re-records (on top of v856)

- `setMediaDownloadGate(true, percent, null)` is called by the adapter on every onProgress tick (auto-download and tap-download paths, 3 sites). It always did a full `invalidate()` -> one full bubble re-record per percent tick. Now, when the gate is already an in-flight plain download (not upload / retry / waiting / idle label, percent >= 0), it routes to `setMediaDownloadProgress()` (live pill rect redraw). Transitions (idle->downloading, indeterminate, upload, retry) still take the full path once.
- Audited and left unchanged (already minimal or unsafe without a build): upload-complete / text-edit / delete full rebind in `applyRealtimeUpdate` (1 record per rebind; v856 same-bitmap guard removes the extra one), view-cache size (already 12 idle / 16 fling), size-change re-record, avatar-setting / theme rebinds, API 23-28 plain-text direct draw.
- Trace tip: `Bubble.record` = on-draw record; idle record-ahead uses a separate `Bubble.preRecord` section, so it is not in the `Bubble.record` count.
Built without Gradle/SDK — run a normal build.
