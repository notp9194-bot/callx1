# v508 — Status viewer perf (1: sticker reads, 2: window/theme, 3: more stubs)

## 1. Sticker Firebase reads (quiz / poll / slider / countdown)
- Viewer ka apna answer ab static memory cache me (`STICKER_OWN_STATE`, key = type|owner|status|stickerIdx|me).
  Cache hit -> zero network, usi frame me apply.
- Cache miss -> read `deferAfterFirstFrame()` ke baad hota hai (pehle render ke dauraan sync fire hota tha).
- Jab viewer khud answer kare (quiz/poll/slider/countdown toggle) to cache turant update hota hai.
- Music sticker: `soundId -> URL` ka `SOUND_URL_CACHE` — dobara dekhne par `sounds/` / `musicLibrary/` read nahi.
- Poll/slider ke live counts (aggregate) pehle ki tarah fresh read hote hain.
- Chhota behaviour change: agar answer record exist kare par value unreadable ho (quiz), sticker ab arm ho jata hai (pehle kuch nahi hota tha).

## 2. Window / theme
- Naya `Theme.CallX.StatusViewer` (`values/status_viewer_theme.xml`): windowBackground `#0B0F17` (white flash khatam),
  system window animations OFF, opaque window. Manifest me `StatusViewerActivity` ko assign.
- `onCreate` me `overridePendingTransition(0,0)` aur `finish()` override (drag-close / fade viewer khud animate karta hai).

## 3. More ViewStubs / hidden views
- `ll_last_reply_bubble` -> ViewStub `stub_last_reply_bubble` (`view_status_last_reply_bubble.xml`), tabhi inflate jab status me replies hon.
- Hidden proxy column (`btn_download`, `btn_forward`, `btn_stabilizer`, `btn_repost`) aur hidden `btn_react` layout se hata diye.
  Unke click bodies ab Runnables hain (`actDownload/actForward/actRepost/actStabilizer/actReact`); "⋯" menu, paper-plane aur heart long-press wahi use karte hain.
