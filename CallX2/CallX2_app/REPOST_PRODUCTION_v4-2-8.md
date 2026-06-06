# CallX2 — Repost System: All Production Features (v4.2.8)

## What was added on top of v4.2.7 (7 bug fixes)

---

## Feature 1 — RepostWithCaptionActivity (NEW)
**File:** `feature-reels/.../activities/RepostWithCaptionActivity.java`

Instagram-style "Repost with your own caption" screen.

- Live looping video preview of the original reel (ExoPlayer, muted)
- Original creator attribution badge: "🔁 Reposting from @username"
- `EditText` for user's own caption (max 200 chars) with live char counter
- Emoji shortcut row: 🔥 ❤️ 😂 🎉 👏 💯 🙌 ✨
- Privacy selector: Everyone / Followers / Close Friends
- Current repost count fetched from `reelReposts/{reelId}`
- 2-second rate-limit guard (anti-spam)
- Writes to:
  - `reelReposts/{reelId}/{myUid}` = timestamp
  - `userReposts/{myUid}/{reelId}` = timestamp
  - `repostCaptions/{reelId}/{myUid}` = {caption, uid, name, timestamp}
  - `reels/{reelId}/repostCount` (transaction +1)
- Dispatches `ReelRepostWorker` (caption included in notification)
- Returns `RESULT_OK` so `ReelPlayerFragment` can update UI

**Launch from ReelPlayerFragment (long-press repost btn) or ReelShareSheetActivity:**
```java
Intent i = new Intent(ctx, RepostWithCaptionActivity.class);
i.putExtra(RepostWithCaptionActivity.EXTRA_REEL_ID,    reel.reelId);
i.putExtra(RepostWithCaptionActivity.EXTRA_OWNER_UID,  reel.uid);
i.putExtra(RepostWithCaptionActivity.EXTRA_OWNER_NAME, reel.ownerName);
i.putExtra(RepostWithCaptionActivity.EXTRA_THUMB_URL,  reel.thumbUrl);
i.putExtra(RepostWithCaptionActivity.EXTRA_VIDEO_URL,  reel.videoUrl);
i.putExtra(RepostWithCaptionActivity.EXTRA_CAPTION,    reel.caption);
startActivity(i);
```

---

## Feature 2 — ReelRepostListActivity (NEW)
**File:** `feature-reels/.../activities/ReelRepostListActivity.java`

"Who reposted this?" list — shown when user taps the repost count.

- Real-time Firebase listener on `reelReposts/{reelId}`
- Shows: avatar, @username, "🔁" badge, repost caption (if any), relative timestamp
- Newest reposters first
- Fetches user photo from `users/{uid}/photoUrl` or `thumbUrl`
- Fetches caption from `repostCaptions/{reelId}/{uid}`
- Tap row → opens `UserReelsActivity` for that reposter
- Live repost count in toolbar subtitle

**Launch:**
```java
Intent i = new Intent(ctx, ReelRepostListActivity.class);
i.putExtra(ReelRepostListActivity.EXTRA_REEL_ID, reelId);
startActivity(i);
```

---

## Feature 3 — "Reposts" filter tab in ReelNotificationsActivity
**File:** `feature-reels/.../activities/ReelNotificationsActivity.java`

Added "Reposts" chip between "Shares" and "Challenges" in the filter tab row.

- `TAB_LABELS` now: All / Likes / Comments / Mentions / Follows / Shares / **Reposts** / Challenges / Recos / Sales
- `TAB_TYPES` maps "Reposts" → `"repost"` filter
- The activity already had the `case "repost"` display logic — now the tab makes it filterable

---

## Feature 4 — ReelModel: allowReposts + attribution fields
**File:** `core/.../models/ReelModel.java`

New fields:
```java
public boolean allowReposts      = true;   // Creator's privacy setting
public String  repostCaption;              // User's caption when reposting
public String  repostedFromReelId;         // Original reel ID (if this is a repost)
public String  repostedFromUid;            // Original creator UID
public String  repostedFromName;           // Original creator name (for attribution banner)
```

---

## Feature 5 — Attribution banner in ReelPlayerFragment
**File:** `feature-reels/.../fragments/ReelPlayerFragment.java`

- If `reel.repostedFromName` is set, shows `"🔁 Reposted from @originalCreator"` banner
- Wire up the `TextView` with tag `"tv_repost_attribution"` in your reel layout

Rate limit guard added to `toggleRepost()`:
```java
private static final long REPOST_RATE_LIMIT_MS = 2_000L;
private long lastRepostActionMs = 0L;
// Check: if now - lastRepostActionMs < 2000ms → show "Please wait…"
```

Long-press on repost button → `openRepostList()` → `ReelRepostListActivity`
New helper: `openRepostWithCaption()` → `RepostWithCaptionActivity`

Creator privacy check in `toggleRepost()`:
```java
if (!reel.allowReposts && !isReposted) {
    Toast: "This creator has disabled reposts"
    return;
}
```

---

## Feature 6 — allowReposts switch in ReelPrivacySettingsActivity
**File:** `feature-reels/.../activities/ReelPrivacySettingsActivity.java`

- Added `switchAllowRepost` Switch field
- Binds to `R.id.switch_allow_repost` (add to layout XML, or auto-fallback if missing)
- Loaded from `reels/{reelId}/privacy/allowReposts`
- Saved to `reels/{reelId}/privacy/allowReposts`

**Add to `activity_reel_privacy_settings.xml`:**
```xml
<Switch
    android:id="@+id/switch_allow_repost"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="Allow Reposts"
    android:textColor="@color/white"
    android:checked="true"
    android:paddingTop="8dp"
    android:paddingBottom="8dp"/>
```

---

## Feature 7 — Repost count in ReelAnalyticsActivity
**File:** `feature-reels/.../activities/ReelAnalyticsActivity.java`

- Reads `repostCount` from Firebase alongside views/likes/comments/shares
- Displays in `tvReposts` (bind `R.id.tv_reposts_count` in layout)
- Engagement rate now includes reposts at 2× weight (matching trendingScore formula)

**Add to `activity_reel_analytics.xml`:**
```xml
<TextView android:id="@+id/tv_reposts_count" ... />
```

---

## Feature 8 — ReelRepostWorker: caption support + enqueue overload
**File:** `feature-reels/.../workers/ReelRepostWorker.java`

- `KEY_CAPTION = "caption"` added
- Caption included in in-app notification body: `"sender reposted your reel"`
- New overload: `enqueue(..., String caption)` — called by `RepostWithCaptionActivity`
- Original `enqueue(...)` without caption still works (delegates to new overload with null)

---

## Firebase Nodes (new in v4.2.8)

| Node | Key | Value |
|------|-----|-------|
| `repostCaptions/{reelId}/{uid}` | — | `{caption, uid, name, timestamp}` |
| `reels/{reelId}/privacy/allowReposts` | — | boolean |
| `reels/{reelId}/repostCount` | — | int |

See updated `firebase_repost_rules.json` for security rules.

---

## Layout Updates Needed (Manual)

### `activity_reel_privacy_settings.xml` — add after switch_allow_share:
```xml
<Switch android:id="@+id/switch_allow_repost"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="Allow Reposts"
    android:checked="true"/>
```

### `activity_reel_analytics.xml` — add repost row:
```xml
<TextView android:id="@+id/tv_reposts_count"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:textColor="@color/white"
    android:textSize="20sp"/>
```

### `fragment_reel_player.xml` — add attribution banner at top of overlay:
```xml
<TextView android:tag="tv_repost_attribution"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:textColor="#4CAF50"
    android:textSize="12sp"
    android:background="#80000000"
    android:padding="6dp"
    android:visibility="gone"/>
```

### `activity_reel_share_sheet.xml` — add "Repost with Caption" button:
```xml
<Button android:id="@+id/btn_repost_with_caption"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="🔁 Repost with Caption"
    android:textColor="@color/white"
    android:backgroundTint="#4CAF50"/>
```

---

## Server-side (`index.js`) — still required
```javascript
const VALID_REEL_TYPES = [
  // existing...
  "repost",   // ← ADD THIS
];
```

---

*CallX2 v4.2.8 — Repost System: All Production Features*
