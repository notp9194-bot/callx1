# v948 — Chats row perf pass (text cache hits, shared icon bitmaps, fixed-size meta, 4-view row)

1. ChatListTextPrecompute: width math now mirrors the views integer-for-integer (per-component px rounding, ceil of meta
   width, separate truncation of tick/icon reserve, verified-badge width). Bold (unread) preview variant is precomputed
   too (key prefix "b"). Preview capped at 120 chars (emoji-safe). Msg cache 300 -> 600. => no UI-thread
   TextUtils.ellipsize in onDraw for normal / unread / verified rows.
2. canvas/ChatRowIconCache (new): person/group placeholders, video/phone call icons and the verified badge are rasterized
   once per process and shared (drawBitmap). No per-VH VectorDrawable.mutate() / Bitmap.
3. ChatRowMetaView: fixed 58dp height, block centred inside it (same placement as before); requestLayout only when WIDTH
   changes. item_chat.xml root FrameLayout removed (LinearLayout carries margin + ripple foreground, clipChildren=false):
   5 -> 4 views.
4. ChatListAdapter: time memo per VH (no Long boxing on unchanged time); story-ring status lookups skipped on
   message/unread partial binds; PerformanceMonitor bind timing only in debuggable builds; settle work armed from
   onViewAttachedToWindow, cancelled on detach.
5. BUGFIX: cancelPendingBindWork() nulled h.pendingBindRunnable, so postDelayed(null) -> typing listener, preloadChatIfDue
   and adjacent-avatar preload never ran. Now they run (after the 180ms settle). If you prefer them off, remove the post.
6. ChatRowContentView: hasOverlappingRendering=false, shared verified bitmap.

Not build-tested (no Gradle/SDK here; javac parse pass only). Verify on device: unread bold previews / verified names
ellipsize identically, call strip + badge position vs v947, group rows, selection mode, typing indicator now appears.
