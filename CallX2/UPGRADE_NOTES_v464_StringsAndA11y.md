# v464 — Reel comments: strings.xml + accessibility

## 3. Strings -> res/values/strings_reel_comments.xml (prefix reel_c_)
- Layouts (activity_reel_comment, item_reel_comment, item_reel_reply, item_reel_reply_more):
  all user-facing text / hint / contentDescription -> @string. Pure design-time
  placeholders ("Username", "2h", "View 3 replies", "Replying to @user", "0/300")
  are now tools:text.
- ReelCommentFragment / ReelCommentsAdapter: toasts, dialog titles/buttons,
  context-menu labels, reply toggle texts, hints, "Comments (n)" -> getString /
  plurals (view replies, view more replies, new-comments pill, likes count).
- Report reasons: DISPLAY labels are localized (string-array reel_c_report_reasons);
  the value written to Firebase is still the English constant.
- Not touched: ReelPinnedCommentsActivity, ReelVideoReplyActivity, ReelLikesBottomSheet,
  MentionSuggestionAdapter, default display-name data ("User" written as ownerName).
  No translated locale files added yet - only the default strings.

## 4. Accessibility
- Like heart: description was already state-aware (applyHeartState); now also reads
  the count ("Unlike comment, 12 likes") via plurals.
- Roles: avatar, Reply, View/Hide replies, "View N more replies" announce as buttons
  (ReelCommentsAdapter.asButton).
- Avatar contentDescription: "Open profile of <name>".
- TalkBack custom action "Reply" on every comment row (swipe-to-reply is a gesture).
- "Comments" title is a heading; new-comments pill and char counter are polite live regions.
- Tap targets: Reply / View replies text ~35dp (was ~23dp), reply-row Reply ~30dp,
  done with padding + cancelling negative margins so row spacing is unchanged.
  Full 48dp would need taller rows.
- Failed-reply retry text now uses reel_error_red instead of holo_red_light.
