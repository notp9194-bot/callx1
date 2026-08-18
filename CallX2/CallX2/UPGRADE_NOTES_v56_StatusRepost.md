# Upgrade Notes — v56: Status Repost / Allow Sharing

## Feature: Status Repost (WhatsApp-style "Allow sharing")

### What was added

When someone views your status, they now see a **repost icon** (two circular arrows) on the right side.
Tapping it opens the existing `StoryReshareActivity` which lets them add your status to their own story — with full **"Forwarded from @ownerName"** attribution card shown on top.

---

### Files changed

| File | Change |
|------|--------|
| `core/.../models/StatusItem.java` | Added `allowSharing` boolean field (default `true`). Also ensured reshare fields are written to `toMap()`. |
| `feature-status/.../viewer/StatusViewerActivity.java` | Added `btn_repost` setup + show/hide logic. Repost icon shown only for viewers when `allowSharing == true`. Handles `status_reshare` type in switch + attribution card. |
| `feature-status/.../res/layout/activity_status_viewer.xml` | Added `btn_repost` ImageButton below the existing download + forward buttons. |
| `feature-status/.../res/drawable/ic_repost.xml` | **New** — vector icon (two circular arrows). |
| `feature-status/.../utils/StatusReshareHelper.java` | Added `canReshareStatus()` + `buildReshareStatusIntent()` for status-to-status repost. |
| `feature-status/.../social/StoryReshareActivity.java` | Handles `contentType = "status"` — writes `type = "status_reshare"` in Firebase, increments `forwardCount` on original status. |
| `feature-status/.../compose/NewStatusActivity.java` | Added `allowSharing` field + `setupAllowSharingToggle()` method. Set `item.allowSharing` in both `saveStatus()` and batch save. |

---

### How it works end-to-end

1. **Owner creates status** → `allowSharing = true` by default. If owner has a `toggle_allow_sharing` CompoundButton in the composer layout (add it to `activity_new_status.xml` with tag `"toggle_allow_sharing"`), they can disable reposting.

2. **Viewer opens status** → `StatusViewerActivity.showCurrent()` checks `s.allowSharing`. If `true` and viewer ≠ owner: `btn_repost` becomes visible.

3. **Viewer taps repost icon** → `setupRepostButton()` calls `StatusReshareHelper.buildReshareStatusIntent()` → launches `StoryReshareActivity` with `contentType = "status"`.

4. **Viewer posts** → `StoryReshareActivity.publishReshare()` writes to `statuses/{myUid}/{newId}` with:
   - `type = "status_reshare"`
   - `resharedFromType = "status"`
   - `resharedFromOwnerName`, `resharedFromOwnerUid`, attribution fields
   - Increments `forwardCount` on the original status at `statuses/{ownerUid}/{contentId}/forwardCount`

5. **Friends view the reposted status** → `StatusViewerActivity` recognises `status_reshare` type, renders media, shows attribution card with badge **"Status"** + owner name + "View Original →".

---

### Optional: Add "Allow sharing" toggle to the composer UI

In `feature-status/src/main/res/layout/activity_new_status.xml`, add near the privacy/expiry row:

```xml
<!-- Allow sharing toggle — let viewers repost this status -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:padding="8dp">

    <ImageView
        android:layout_width="28dp"
        android:layout_height="28dp"
        android:src="@drawable/ic_repost"
        android:tint="@color/white"
        android:alpha="0.8"/>

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical"
        android:layout_marginStart="12dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Allow sharing"
            android:textColor="@color/white"
            android:textSize="15sp"/>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Let people who can see your status reshare and forward it."
            android:textColor="#AAFFFFFF"
            android:textSize="12sp"/>

    </LinearLayout>

    <Switch
        android:tag="toggle_allow_sharing"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:checked="true"/>

</LinearLayout>
```

---

### Firebase rules (status node)

No new rules needed — `status_reshare` items are written at `statuses/{resharer_uid}/{newId}` (same node as every other status). The `forwardCount` field on the original is incremented via a Firebase Transaction.

### No chain-reposting

`canReshareStatus()` returns `false` when `status.type == "status_reshare"`, preventing
chain-reposting of already-reshared statuses. The repost button is also hidden for these items.
