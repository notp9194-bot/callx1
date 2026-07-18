# Reel Caption Mention System — Instagram-style @mention

## Overview

This document describes the Instagram-style @mention system added to the reel media upload flow.  
When a user types `@` in the caption field on the **Post Details** screen, a live suggestion dropdown appears listing their followers. Selecting a name inserts `@Name` with a blue highlight. After the reel is published, all mentioned users receive a push notification and an entry in their **Mentions** inbox.

---

## New Files

| File | Module | Purpose |
|------|--------|---------|
| `ReelCaptionMentionController.java` | `feature-reels/upload/` | Watches caption EditText for `@`, loads followers, shows/hides suggestion dropdown, inserts blue mention spans, resolves name→uid map |
| `ReelMentionNotifier.java` | `feature-reels/upload/` | Fire-and-forget: writes `notifications/{uid}` + `reelMentions/{uid}/{reelId}` to Firebase after upload succeeds |
| `item_mention_suggest_reel.xml` | `feature-reels/res/layout/` | Single-row layout using `MentionRowCanvasView` (avatar + name + "@" badge) |

---

## Modified Files

| File | Change |
|------|--------|
| `activity_reel_post_details.xml` | Added `rv_mention_suggest_post` RecyclerView (id) between toolbar and scroll area; appears as animated slide-up overlay when `@` is typed |
| `ReelPostDetailsActivity.java` | Wires up `ReelCaptionMentionController`; adds `RESULT_MENTION_UIDS` constant; passes `ArrayList<String>` of mentioned UIDs to `ReelUploadActivity` via Intent extra |
| `ReelUploadActivity.java` | Reads `RESULT_MENTION_UIDS` from Intent; calls `ReelMentionNotifier.notifyAll()` inside the `addOnSuccessListener` after the reel is confirmed published |
| `ReelModel.java` | Added `public List<String> mentionedUids` field (stored in Firebase, available for feed renderers and deep-link mention screens) |

---

## Data Flow

```
User types "@" in etCaption
        │
        ▼
ReelCaptionMentionController (TextWatcher)
        │  lazy-load followers/{myUid} → users/{uid} (once)
        │  contains-match filter on query
        ▼
rv_mention_suggest_post (RecyclerView)
    MentionRowCanvasView rows (avatar + name)
        │
        │  user taps a row
        ▼
insertMention() → "@Name " + blue ForegroundColorSpan into etCaption
                  nameToUidMap.put(mentionToken, uid)
        │
        │  user taps "Next →"
        ▼
getMentionedUids(caption) → ArrayList<String> uids
        │
        │  Intent.putStringArrayListExtra(RESULT_MENTION_UIDS, uids)
        ▼
ReelUploadActivity.onCreate() → mentionedUids = getIntent().getStringArrayListExtra(...)
        │
        │  upload video → saveReelToFirebase() → setValue(reel) succeeds
        ▼
ReelMentionNotifier.notifyAll(posterUid, posterName, reelId, thumbUrl, caption, mentionedUids)
        │
        ├─ notifications/{mentionedUid}/push() → type:"reel_mention", read:false
        └─ reelMentions/{mentionedUid}/{reelId} → full mention entry
```

---

## Firebase Schema

### `notifications/{uid}/{pushKey}`
```json
{
  "type":      "reel_mention",
  "fromUid":   "abc123",
  "fromName":  "Ayesha Khan",
  "reelId":    "reelXYZ",
  "thumbUrl":  "https://…",
  "caption":   "Check this out @ali!",
  "timestamp": 1721234567890,
  "read":      false
}
```

### `reelMentions/{uid}/{reelId}`
```json
{
  "reelId":        "reelXYZ",
  "mentionerUid":  "abc123",
  "mentionerName": "Ayesha Khan",
  "caption":       "Check this out @ali!",
  "thumbUrl":      "https://…",
  "timestamp":     1721234567890,
  "read":          false
}
```

---

## Existing Infrastructure Reused

- **`MentionRowCanvasView`** (`core/mention/canvas/`) — single-draw canvas View for avatar + name + "@" badge. Same component used in chat mention suggestions.
- **`MentionSuggestAdapter`** (`feature-chat/chat/ui/`) — filterable RecyclerView adapter with `contains`-match. Reused directly in `ReelCaptionMentionController`.
- **`ReelMentionsActivity`** (`feature-reels/upload/`) — existing inbox showing reels where the user was tagged. The `reelMentions/{uid}` node written by `ReelMentionNotifier` is consumed here.

---

## Integration Notes

- `ReelCaptionMentionController.loadFollowers()` loads at most **50** followers on the first `@` trigger. Subsequent `@` uses the in-memory list — no extra Firebase reads.
- A mention token is inserted **without spaces** in the name (`@AyeshaKhan `) to avoid ambiguous word boundaries. The `nameToUidMap` stores both the space-joined and the space-removed key to handle both forms at resolution time.
- `ReelMentionNotifier.notifyAll()` is **fire-and-forget** — it never blocks the UI thread or delays the "Reel posted!" toast. A failure in any one notification does not affect the others.
- The `mentionedUids` list on `ReelModel` is stored in Firebase alongside the reel, enabling future features (mention highlighting in the feed caption, deep-link to mentioned user's profile, etc.).
