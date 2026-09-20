# v9 Measured Chat Baseline-Profile Refresh

## Included

- Added exact baseline-profile rules for the v7/v8 hot paths:
  `ChatUiEventBatcher`, `MessagePagingAdapter.applyRealtimeUpdate()`,
  `MessageDao` bulk merge methods, `ChatRepository` sync entry points, and
  `MediaCache` single-flight helpers.
- Added the new frame coalescer and media-cache classes to the primary-dex
  keep list used by the benchmark/release layout.
- Kept the existing Macrobenchmark producer journeys for cold startup, chat
  open, message scroll/fling, group chat, media, and memory tracking. The
  source profile now covers the new methods until a device-generated trace
  replaces it.

## Device measurement

The archive is prepared for the real measured run:

```text
./gradlew :macrobenchmark:generateBaselineProfile
```

Run that on the target low/mid-range device to regenerate
`app/src/main/baseline-prof.txt` and the narrow startup profile. The current
archive was not built or benchmarked, per request.