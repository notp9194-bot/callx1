# v703 — Status "Seen by" sheet: Instagram-style viewers screen

Screenshot jaisa dark, full-height "Seen by N" screen. **Sirf UI layer badli** — live listeners, lazy profile fetch, `compute()`, hide/block/close-friend menu, filters sab v29 wala same.

## Files
- `feature-status/.../interactions/StatusSeenByBottomSheet.java` (v29 -> v30)
- `feature-status/.../interactions/StatusSheetAvatar.java` (+ `ROW_TIER`, tier-wala `load()`/`options()`)
- `feature-status/.../viewer/StatusViewerActivity.java` (3 call sites: ab `items` list bhi pass hoti hai)
- `feature-status/src/main/res/values/strings_status_seenby.xml` + `values-hi/…` (naye strings + plurals)

## Naya layout (top -> bottom)
1. **Top bar:** `‹` back | "Seen by N" | `✕` close (dono sheet dismiss karte hain).
2. **Story carousel:** owner ke saare stories ke 9:16 cards, har card par `👁 count`. Selected card = white frame + thoda bada. Card tap = story switch (live listeners detach/attach, list + title + count refresh). Text story = bgColor/bgColor2 gradient + text.
3. **"👁 N viewers"** + `🔍` + **"Sort by ⌄"** pill. Filter/reaction lagne par pill ka label badal jata hai (e.g. "Replies", "❤️").
4. **Rows:** story-ring avatar (54dp), naam, "2h ago", reaction emoji, `⋯` (purana menu), paper-plane (message). Follower/Contact tags hata diye; sirf "★ Close friend" aur "Hidden" marker bacha.
5. **Sort by popup:** Order (Most recent / Relevance) + Show (All, Followers, Not a follower, Reactions, Replies, Close friends — counts ke saath) + Reaction (emoji filter). Selected par green ✓.
6. Search ab 🔍 ke peeche (tap = search box + keyboard, carousel collapse; dobara tap = clear + band).

## Drawables — sab app me pehle se the (naya drawable koi nahi)
| Jagah | Drawable | Module |
|---|---|---|
| Back `‹` | `ic_chevron_left` | core |
| Close `✕` | `ic_close` (white tint) | feature-status |
| Eye | `ic_seenby_eye` | feature-status |
| Sort chevron | `ic_chevron_down` | feature-chat |
| Search | `ic_seenby_search` | feature-status |
| `⋯` | `ic_seenby_more` (90° rotate) | feature-status |
| Paper-plane | `ic_status_share` (Instagram outline plane) | feature-status |

## Reuse / pipeline
- Card thumb: `StatusListAdapter.cardThumb()` (video ka mediaUrl image nahi maana jata) + `AvatarUrlBuilder.buildCard()` (exact-size CDN crop) — Status tab cards wali hi pipeline.
- Row avatar: `AvatarSizeTier.MEDIUM` (54dp avatar ko 32dp tier se upscale nahi karna padta). Reply sheet wala TINY path unchanged.
- Ring: `StoryRingApplier.applyWithClick` pehle jaisa.

## Behaviour changes (jaan-boojh ke)
- Palette hamesha dark (`#0F0F0F`), theme follow nahi karta — screenshot jaisa.
- Hata: stats strip (views/likes/replies/shares/avg watch), emoji summary strip, filter chips. Data popup me hai (counts + emoji filter); likes/shares/avg-watch ab is screen par nahi dikhte.
- Title "Viewers" -> "Seen by %1$d" (hi: "%1$d ने देखा"). Purane unused strings (`seenby_stat_*`, `seenby_seen_fmt`, `seenby_count_of`, `seenby_badge_follower/contact/not_follower`) resources me chhode hain — cleanup chahiye to bol dena.
- Carousel me non-selected stories ka count static (`getViewCount()`), sirf selected story live hai.

## Verify
- Build: `./gradlew :feature-status:assembleDebug` (yahan gradle/network nahi tha — Java syntax parse + XML + string refs check kiye, full compile device/CI par).
- Check: sheet full height khulta hai; carousel tap par list + count badalta hai; 🔍 khulne par carousel chhupta hai; ⋯ menu / message / hide / block pehle jaise; keyboard ke saath list resize.
- Agar status bar ke neeche content chipke to `root` ka `ViewCompat` inset listener dekho (`show()`).
