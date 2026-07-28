# v39 — Status/Story Highlights: Delete Choice, Permanence, Editing & Settings

Teen requested cheezein fix ki gayi (feature-status module):

## 1. Delete confirmation — 2 options
`StatusDeleteConfirmBottomSheet` ab status ke `isHighlighted` state ke hisaab se
do options deta hai:
- **Delete Status Only** — sirf live status/story delete hota hai, Highlight
  album me copy waisi hi permanent rehti hai.
- **Delete & Remove from Highlights** — status delete + saare Highlight albums
  se bhi hata deta hai.
Agar status kabhi highlight me add hi nahi hua, purana single "Delete Status"
button hi dikhta hai.

Wired in `StatusViewerActivity.showOwnerMoreMenu()`.

## 2. Highlights ab truly permanent (Instagram-style)
Bug tha: `StatusHighlightManager.addToHighlight()` sirf ek copy
`statusHighlights/{uid}/{albumId}/{statusId}` me likhta tha, par original live
status doc (`status/{uid}/{statusId}`) kabhi update nahi hota tha — isliye
`isHighlighted` / `highlightAlbumId` hamesha stale/false rehte the.

Fix:
- `addToHighlight()` ab original status doc ko bhi sync karta hai
  (`isHighlighted`, `highlightAlbumId`, `highlightAlbumName`,
  `highlightAlbumIds/{albumId}`).
- `StatusItem` me naya field: `Map<String,Boolean> highlightAlbumIds` — status
  multiple albums me bhi ho sakta hai (RTDB-safe map, list nahi).
- Highlight album ka data alag node me pehle se hi tha (permanent by design) —
  live feed filtering (`StatusFragment`, `StatusBackgroundService`,
  `StatusViewerActivity`'s live loader) sirf `status/` node par `expiresAt`
  check karta hai, `statusHighlights/` par kabhi nahi. Ab viewer bhi highlight
  mode me expiresAt ko ignore karta hai + expiry label kabhi nahi dikhata.

## 3. Highlight viewing + editing/settings system (naya)
Pehle album tap karne par galti se OWNER ka **live** status feed khul jaata
tha (album ka content hi nahi dikhta tha) — `StatusHighlightsActivity` sirf
`ownerUid` pass karta tha, `StatusViewerActivity` hamesha `status/` se load
karta.

Fix:
- `StatusViewerActivity` me naya extra `EXTRA_HIGHLIGHT_ALBUM_ID` — jab set ho
  to viewer `statusHighlights/{uid}/{albumId}` se load karta hai
  (`loadHighlightAlbum()`), expiry-filter ke bina.
- Owner ke liye highlight-mode me alag "..." menu:
  `Who viewed this / Remove from Highlight / Rename Highlight / Set as Cover /
  Delete Entire Highlight`.
- `StatusHighlightsActivity` long-press ab naye
  `StatusHighlightSettingsBottomSheet` ko kholta hai: **Rename**, **Change
  Cover** (grid picker album ke apne items se), **Delete Highlight**.
- Naya Firebase node: `statusHighlightMeta/{uid}/{albumId}` — album ka
  custom name/cover store karta hai; list screen ab ismein se override padhta
  hai.
- `StatusHighlightManager` me naye methods: `renameAlbum()`, `setAlbumCover()`,
  `deleteAlbum()`, `removeStatusFromAllHighlights()`, `getAlbumMetaRef()`.

### Files changed
- `core/.../models/StatusItem.java`
- `feature-status/.../utils/StatusHighlightManager.java`
- `feature-status/.../interactions/StatusDeleteConfirmBottomSheet.java`
- `feature-status/.../viewer/StatusViewerActivity.java`
- `feature-status/.../highlights/StatusHighlightsActivity.java`
- `feature-status/.../highlights/StatusHighlightSettingsBottomSheet.java` (new)

### Firebase rules note
Naye nodes `statusHighlightMeta/{uid}/{albumId}` ke liye security rules add
karna — same pattern jaisa `statusHighlights/{uid}` ke liye hai (owner
read/write, others read-only agar profile public hai).
