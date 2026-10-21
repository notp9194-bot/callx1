# v644 — Media bubble progress polish

4. Stage label: controller reports stage (compress / upload) -> tracker -> pill shows "Compressing 34%" while compressing.
5. Tick throttling: identical ticks dropped; non-urgent ticks limited to ~10/s per message with a trailing flush (adapter).
6. Ring glides to the new percentage (160ms ease, `mediaDisplayProgress`) instead of jumping. No fade-out at 100%: the gate is
   replaced by the normal bubble on the next bind, so a fade would need a lingering overlay (not done).
7. Failed-upload reason: `MediaFailureReasons` (in-memory) set by the foreground controller and the outbox worker;
   retry pill says "Network error / Too large / File unavailable · tap to retry". Falls back to "Tap to retry" after a restart.
8. Size: "45% · 5.4 MB / 12 MB" using the REAL post-compression size (image full file / video compressedBytes); falls back to
   plain "NN%" when the pill would not fit the media width.
9. Cancel: hit area grown to >=48dp (also for the offline pill), haptic kept, and ChatActivity asks "Cancel upload?" first.
10. Accessibility: TalkBack contentDescription for upload gates ("Uploading, 45 percent. Double tap to cancel", waiting, failed)
    plus announcements at 0/25/50/75/100%.
11. NOT done on purpose: persisting the last percent across process death. Uploads restart from 0 after a restart (no resume),
    so a stored percent would be wrong; the worker now publishes fresh progress via OutboxProgressBus instead (v642).
