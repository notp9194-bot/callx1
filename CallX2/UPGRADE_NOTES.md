# CallX2 Status System — Production Upgrade Notes

---

## v18 — Offline Improvements (6 Fixes)

### DB Version: 3 → 4
Migration adds 4 new columns — handled automatically via `MIGRATION_3_4`.

### Files Changed

| File | Change |
|------|--------|
| `db/entity/ChatEntity.java` | +`draft TEXT`, +`pendingMarkRead INTEGER` |
| `db/entity/MessageEntity.java` | +`mediaLocalPath TEXT`, +`mediaResourceType TEXT` |
| `db/dao/ChatDao.java` | +`saveDraft`, +`getDraft`, +`queueMarkRead`, +`getPendingMarkReadChats`, +`clearPendingMarkRead` |
| `db/dao/MessageDao.java` | +`getAllPendingMessages`, +`getMessageById`, +`getFailedMediaUploads` |
| `db/dao/GroupDao.java` | +`getGroup(id)` |
| `db/AppDatabase.java` | version 3→4, +`MIGRATION_3_4` |
| `services/NotificationActionReceiver.java` | ACTION_REPLY + ACTION_GROUP_REPLY → offline-first Room save, online check, pending retry |
| `activities/ChatActivity.java` | +`saveDraft()` / `restoreDraft()` on onPause/onDestroy/onCreate; `markMessagesRead()` → offline queue |
| `activities/ProfileActivity.java` | `load()` → Room pehle, Firebase overwrite, Room update |
| `activities/GroupInfoActivity.java` | `loadGroupData()` → Room pehle, Firebase overwrite, Room update |
| `fragments/ChatsFragment.java` | `loadFromRoom()` → unread badge from Room; `loadContacts()` → unread saved to Room |
| `sync/SyncWorker.java` | +pending text retry, +read receipt flush, +failed media upload retry (HEAVY) |

### Improvement Summary

| # | Feature | What was broken | Fix |
|---|---------|-----------------|-----|
| 1 | Notification Reply offline | Silently failed — no Room save, no retry | Room save first (pending), SyncWorker retry online |
| 2 | Draft Messages | Lost on navigate away | `ChatEntity.draft`, save onPause, restore onCreate |
| 3 | Profile + GroupInfo offline | Blank screen — pure Firebase | Room se pehle load, Firebase overwrite |
| 4 | Read Receipts offline | markRead silently failed | Room queue (`pendingMarkRead`), SyncWorker flush |
| 5 | Media Upload offline | Upload fails, message stuck pending forever | `mediaLocalPath` stored, SyncWorker HEAVY retry |
| 6 | Unread Badge offline | Badge went 0 or wrong offline | Room `unread` column se badge — no Firebase needed |

---



## Files Changed / Added

### New Java files
| File | Description |
|------|-------------|
| `models/StatusItem.java` | Enhanced model: reactions, seenBy map, privacy, captions, bg color, font style, thumbnails, highlights, location, soft-delete |
| `utils/StatusSeenTracker.java` | Thread-safe Firebase seen-marking, batch writes, reaction helpers |
| `utils/StatusPrivacyManager.java` | Privacy modes: everyone / contacts / except / only, SharedPrefs storage |
| `utils/StatusNotificationHelper.java` | Rich notification builder: BigPicture (image), avatar fetch via Glide, grouped notifications, deep-link PendingIntent |
| `services/StatusBackgroundService.java` | Foreground DATA_SYNC service: Firebase listener alive when killed, FCM-wake handler, contact-list watcher, dedup |

### Updated Java files
| File | Description |
|------|-------------|
| `fragments/StatusFragment.java` | Sectioned list (My Status / Recent / Viewed), real-time seen sync, DiffUtil, proper listener cleanup |
| `activities/NewStatusActivity.java` | Text bg color picker (10 presets), font style selector, privacy picker, caption, character counter, live preview, draft save |
| `activities/StatusViewerActivity.java` | Multi-segment progress bars, hold-to-pause, tap zones, reactions sheet, seen tracking, owner sees "Seen by N", font/color rendering |
| `adapters/StatusListAdapter.java` | Section headers, My Status row, seen/unseen rings, badge counts, thumbnails, DiffUtil animations |
| `utils/FirebaseUtils.java` | Added: getUserStatusRef, getStatusSeenByRef, getStatusSeenRef, getStatusReactionRef, getStatusHighlightsRef |
| `utils/Constants.java` | Added: STATUS_FCM_* keys, STATUS_NOTIF_BASE, STATUS_BG_SERVICE_ID, CHANNEL_STATUS_BG_SERVICE, GROUP_KEY_STATUS |
| `utils/PushNotify.java` | Added: notifyStatusRich (photo + statusType + mediaUrl + text), postAsync helper |

### New layout files
| File | Description |
|------|-------------|
| `layout/item_my_status.xml` | My Status row at top: ring + avatar + add overlay + thumb |
| `layout/item_status_header.xml` | Section header row ("Recent updates" / "Viewed updates") |
| `layout/bottom_sheet_status_reactions.xml` | Emoji reaction picker: ❤️😂😮😢😡👍 |

### Updated layout files
| File | Description |
|------|-------------|
| `layout/fragment_status.xml` | CoordinatorLayout + ExtendedFAB with label + scroll-collapse |
| `layout/item_status.xml` | Added: tv_badge (unseen count), tv_sub (caption preview), iv_thumb (media preview) |
| `layout/activity_new_status.xml` | Full redesign: NestedScrollView, color swatches, font buttons, live preview card, caption field, privacy row, upload hint |
| `layout/activity_status_viewer.xml` | Multi-segment progress container, pause/touch layer, seen-by text, reaction + reply bottom row, captions |

### Server (index.js)
- `/notify/status` — now sends rich FCM payload: `fromPhoto`, `statusType`, `mediaUrl`, `text`
- `/notify/status/seen` — NEW: fan-out seen-receipt to status owner (normal priority)
- `/status/cleanup` — NEW: server-side TTL cleanup for Cloud Scheduler (protected by `x-cleanup-secret` header)

## AndroidManifest Changes Required
1. Register `StatusBackgroundService` with `foregroundServiceType="dataSync"` and `stopWithTask="false"`
2. Ensure `FOREGROUND_SERVICE_DATA_SYNC` permission is declared

## Firebase Rules
Add rules for `statusSeen/`, `statusHighlights/` nodes — see `AndroidManifest_STATUS_ADDITIONS.xml`

## CallxApp.onCreate Integration
To start StatusBackgroundService on app launch (keeps listener alive):

```java
// In CallxApp.onCreate(), after Firebase init:
if (FirebaseAuth.getInstance().getCurrentUser() != null) {
    Intent svc = new Intent(this, StatusBackgroundService.class);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(svc);
    } else {
        startService(svc);
    }
}
```

## CallxMessagingService FCM Handler
Add a case for `type == "status"` in your FCM `onMessageReceived`:

```java
case "status": {
    Intent svc = new Intent(ctx, StatusBackgroundService.class);
    svc.putExtra("fromUid",    data.get("fromUid"));
    svc.putExtra("fromName",   data.get("fromName"));
    svc.putExtra("fromPhoto",  data.get("fromPhoto"));
    svc.putExtra("statusType", data.get("statusType"));
    svc.putExtra("text",       data.get("text"));
    svc.putExtra("mediaUrl",   data.get("mediaUrl"));
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        ctx.startForegroundService(svc);
    else
        ctx.startService(svc);
    break;
}
```
## v20 — WhatsApp-level Image System

### New File
| File | Description |
|------|-------------|
| `utils/ImageCompressor.java` | WebP compression: 5MB → thumb (~30KB) + full (~400KB), EXIF rotation fix, background thread (no UI freeze) |

### Modified Files
| File | Change |
|------|--------|
| `activities/ChatActivity.java` | `uploadAndSend()` for images now compresses first (thumb + full) → uploads both to Cloudinary → stores both URLs in message |
| `activities/MediaViewerActivity.java` | Full-screen image: progressive load (thumb → full crossfade 500ms) |
| `adapters/MessagePagingAdapter.java` | Image case: progressive loading (thumb instantly → Glide crossfade to full) |
| `app/build.gradle` | +`androidx.exifinterface:exifinterface:1.3.7` |

### How It Works

```
User picks image (5MB) from gallery/camera
         ↓
ImageCompressor (background thread — UI freeze ZERO)
   ├── Fix EXIF rotation (portrait selfies ab ulta nahi aayega)
   ├── Memory-safe decode (OOM se protection)
   └── Generate 2 files:
       ├── thumb_xxx.webp  (~30KB, 200×200px square)
       └── full_xxx.webp   (~400KB, max 1280px)
         ↓
Upload thumb to Cloudinary (callx/thumb/)
         ↓
Upload full to Cloudinary (callx/image/)
         ↓
Firebase message: { mediaUrl: fullUrl, thumbnailUrl: thumbUrl }
         ↓
Chat list / adapter:
   thumbnailUrl → instant load (cached, tiny)
   mediaUrl     → Glide progressive crossfade replaces thumb
         ↓
User taps → MediaViewerActivity:
   thumbUrl → shown instantly
   fullUrl  → crossfade 500ms
```

### Size Comparison
| Original | Thumb | Full |
|---------|-------|------|
| 5 MB    | ~30 KB | ~400 KB |
| 3 MB    | ~25 KB | ~300 KB |
| 1 MB    | ~20 KB | ~150 KB |

### Key Improvements vs v19
1. **No UI freeze** — sab background thread pe (ExecutorService)
2. **EXIF fix** — portrait/selfie images ab kabhi ulta nahi aayega
3. **Dual upload** — thumb (chat list fast) + full (viewer quality)
4. **Progressive load** — thumb turant dikhta hai, full crossfade se replace hota hai
5. **Adaptive quality** — agar WebP bhi bada ho to quality automatically reduce hoti hai
6. **OOM protection** — inSampleSize se huge images bhi safely load hoti hain

