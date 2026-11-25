# v854 — Live overlay for download/upload progress, reply press, quick-forward (on top of v853)

- **Single media gate pill** (bg + ring + % + upload label + cancel X): `MediaRenderer.drawMediaGatePillLive()` is painted
  from `drawDynamicOverlayLayer()`. Only the scrim stays in the cached node. Progress ticks, the 160ms % animator,
  the SpinnerClock tick and upload-detail/cancel changes now `invalidate(pill row)` only.
- **File bubble ring**: `FileBubbleRenderer.drawRingLive()` — same idea; only the download start/end re-records.
- `shouldSkipFullCache()` no longer bypasses the cache for single media / file downloads (only albums still do).
  Inner media/file picture cache disabled (not needed any more).
- **Reply-box press tint**: drawn in the overlay; touch down/up invalidate only the reply box rect.
- **Quick-forward button visibility**: `super.invalidate()` (button already lives in the overlay) — no re-record.
- Not changed: album cell progress (per-cell rings) still uses the old bypass path.
Built without Gradle/SDK — run a normal build.
