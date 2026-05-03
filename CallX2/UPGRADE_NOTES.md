# CallX2 Status System — Production Upgrade Notes

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
