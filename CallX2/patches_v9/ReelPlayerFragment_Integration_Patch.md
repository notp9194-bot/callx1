# ReelPlayerFragment — Collab Repost Integration Patch

Add the following code blocks to your existing `ReelPlayerFragment.java`.

---

## 1. New Fields (add near top of class)

```java
// Collab Repost fields
private RepostManager repostManager;
private boolean hasReposted = false;
private long currentRepostCount = 0;
private ImageButton btnRepost;
private TextView tvRepostCount;
private Chip chipViewReposts, chipCollabInvite, chipViewChain, chipAnalytics;
```

---

## 2. Init repostManager (add in onViewCreated or setupPlayer)

```java
FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
if (u != null) {
    repostManager = new RepostManager(
        u.getUid(),
        u.getDisplayName() != null ? u.getDisplayName() : "User",
        u.getPhotoUrl() != null ? u.getPhotoUrl().toString() : ""
    );
}
```

---

## 3. populateStaticData() — add after existing duet/stitch chips

```java
// ── Repost count chip ───────────────────────────────────────
tvRepostCount.setText(formatCount(reel.repostCount) + " Reposts");

// Check has reposted
repostManager.checkHasReposted(reel.reelId, (hasRep, existing) -> {
    hasReposted = hasRep;
    btnRepost.setImageResource(hasRep
        ? R.drawable.ic_repost_filled
        : R.drawable.ic_repost_outline);
});

// Repost privacy guard
String myUid = FirebaseAuth.getInstance().getUid();
RepostPrivacyManager.canUserRepost(reel.reelId, myUid, reel.uid, canRepost -> {
    btnRepost.setEnabled(canRepost);
    btnRepost.setAlpha(canRepost ? 1f : 0.4f);
});

// "View Reposts" chip (shown if repostCount > 0)
if (reel.repostCount > 0 && (reel.repostOf == null || reel.repostOf.isEmpty())) {
    addViewRepostsChip();
}

// "Viral" badge display
if (reel.viralBadge != null && !reel.viralBadge.isEmpty()) {
    showViralBadge(reel.viralBadge);
}

// Collab: show co-author handles if present
if (reel.collaboratorUids != null && !reel.collaboratorUids.isEmpty()) {
    showCollabAuthors(reel.collaboratorUids);
}

// Show "Collab" invite chip only for owner
if (myUid != null && myUid.equals(reel.uid)) {
    addCollabInviteChip();
    addAnalyticsChip();
}
```

---

## 4. Repost button click handler

```java
btnRepost.setOnClickListener(v -> {
    if (reel == null) return;
    // Block self-repost
    String myUid = FirebaseAuth.getInstance().getUid();
    if (myUid != null && myUid.equals(reel.uid)) {
        Toast.makeText(getContext(), "You can't repost your own reel", Toast.LENGTH_SHORT).show();
        return;
    }
    RepostBottomSheetFragment sheet = RepostBottomSheetFragment.newInstance(
        reel.reelId, reel.uid, reel.userName,
        reel.thumbnailUrl, reel.videoUrl, hasReposted
    );
    sheet.setDoneListener((isNowReposted, count) -> {
        hasReposted = isNowReposted;
        btnRepost.setImageResource(isNowReposted
            ? R.drawable.ic_repost_filled
            : R.drawable.ic_repost_outline);
        // Refresh repost count
        repostManager.getRepostCount(reel.reelId, c -> {
            currentRepostCount = c;
            tvRepostCount.setText(formatCount(c) + " Reposts");
            // Check viral milestone
            ViralRepostBadgeHelper.checkAndAward(reel.reelId, reel.uid, c);
        });
    });
    sheet.show(getChildFragmentManager(), RepostBottomSheetFragment.TAG);
});
```

---

## 5. addViewRepostsChip() helper method

```java
private void addViewRepostsChip() {
    Chip chip = new Chip(requireContext());
    chip.setText("🔁 " + formatCount((long) reel.repostCount) + " Reposts ›");
    chip.setChipBackgroundColorResource(R.color.chip_teal_bg);
    chip.setOnClickListener(v -> {
        Intent i = new Intent(requireContext(), ViewRepostsActivity.class);
        i.putExtra(ViewRepostsActivity.EXTRA_REEL_ID, reel.reelId);
        startActivity(i);
    });
    containerHashtags.addView(chip, 0);
}
```

---

## 6. addCollabInviteChip() — for reel owner only

```java
private void addCollabInviteChip() {
    Chip chip = new Chip(requireContext());
    chip.setText("🤝 Invite Collab");
    chip.setChipBackgroundColorResource(R.color.chip_purple_bg);
    chip.setOnClickListener(v -> {
        Intent i = new Intent(requireContext(), CollabInviteActivity.class);
        i.putExtra(CollabInviteActivity.EXTRA_REEL_ID, reel.reelId);
        i.putExtra(CollabInviteActivity.EXTRA_REEL_THUMB, reel.thumbnailUrl);
        startActivity(i);
    });
    containerHashtags.addView(chip);
}
```

---

## 7. showCollabAuthors() — display co-author handles

```java
private void showCollabAuthors(Map<String, String> collaborators) {
    // tvAuthorLine is a TextView in your player layout for author display
    StringBuilder sb = new StringBuilder("@" + reel.userName);
    for (Map.Entry<String, String> e : collaborators.entrySet()) {
        sb.append(" + @").append(e.getValue());
    }
    tvAuthorLine.setText(sb.toString()); // existing author TextView
}
```

---

## 8. showViralBadge() — viral repost badge

```java
private void showViralBadge(String badge) {
    String emoji = ViralRepostBadgeHelper.getBadgeEmoji(badge);
    String label = ViralRepostBadgeHelper.getBadgeLabel(badge);
    // tvViralBadge is a small badge TextView overlaid on your reel card
    tvViralBadge.setVisibility(View.VISIBLE);
    tvViralBadge.setText(label);
}
```

---

## 9. addAnalyticsChip() — repost analytics for owner

```java
private void addAnalyticsChip() {
    Chip chip = new Chip(requireContext());
    chip.setText("📊 Repost Analytics");
    chip.setOnClickListener(v -> {
        Intent i = new Intent(requireContext(), RepostAnalyticsActivity.class);
        i.putExtra(RepostAnalyticsActivity.EXTRA_REEL_ID, reel.reelId);
        startActivity(i);
    });
    containerHashtags.addView(chip);
}
```

---

## 10. ReelModel.java — new fields to add

```java
// Add to existing ReelModel fields:
public long repostCount;
public String viralBadge;            // "hot" | "trending" | "viral" | "legend"
public Map<String, String> collaboratorUids;   // uid → displayName
public Map<String, String> collaboratorPhotos; // uid → photoUrl
public String seriesId;
public String allowRepostLevel;      // "everyone" | "followers" | "off"
public boolean allowRepost;
```

---

## 11. FCM Handler — add new cases in ReelFCMNotificationHandler.java

```java
// In your switch(type) block:

case "TYPE_REPOST":
    notifTitle = data.get("sender_name") + " reposted your reel 🔁";
    notifBody  = data.containsKey("caption") ? "\"" + data.get("caption") + "\""
                 : "Tap to see the repost";
    channelId  = "reel_social";
    break;

case "TYPE_QUOTE_REPOST":
    notifTitle = data.get("sender_name") + " quoted your reel 💬";
    notifBody  = data.containsKey("caption") ? "\"" + data.get("caption") + "\""
                 : "Tap to see the quote";
    channelId  = "reel_social";
    break;

case "TYPE_REPOST_MILESTONE":
    notifTitle = "Your reel is going viral! 🔥";
    notifBody  = data.get("sender_name") != null
                 ? data.get("sender_name") : "Your reel hit a new milestone";
    channelId  = "repost_milestone";
    break;

case "TYPE_COLLAB_INVITE":
    notifTitle = data.get("sender_name") + " invited you to collab 🤝";
    notifBody  = "Tap to accept or decline";
    channelId  = "collab_invite";
    // Deep-link to CollabPendingActivity
    pendingIntent = buildPendingIntent(ctx, CollabPendingActivity.class, null);
    break;

case "TYPE_COLLAB_ACCEPTED":
    notifTitle = data.get("sender_name") + " accepted your collab! 🎉";
    notifBody  = "The reel now shows on both profiles";
    channelId  = "reel_social";
    break;

case "TYPE_LIVE_COLLAB_INVITE":
    notifTitle = data.get("sender_name") + " wants to go LIVE with you! 🔴";
    notifBody  = "Tap to join the live collab";
    channelId  = "live_collab";
    // High priority — deep-link to LiveCollabActivity
    break;
```
