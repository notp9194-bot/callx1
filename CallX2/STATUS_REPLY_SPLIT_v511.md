# v511 — Status reply: private vs public split (Instagram-style)

| | Private | Public |
|---|---|---|
| UI | "Send message" pill (inline typing) + paper plane | Comment icon (next to ❤️) → sheet "Public comment", button "Post" |
| Writes to | `messages/{chatId}` (chat DM, quotes the status) | `status/{owner}/{statusId}/replies` only |
| Visible to | Status owner only (in chat) | Everyone viewing the status |
| Push to owner | Yes | Yes ("💬 Commented: …") |
| Code | `StatusReplyBottomSheet.sendPrivateReply()` | `StatusReplyBottomSheet.showPublicComment()` / `postPublicReply()` |

Removed: `StatusViewerActivity.sendReplyToChat()` (duplicate, lacked push + thumbnail embed),
`StatusReplyBottomSheet.sendReply()` (mixed DM + public). Pill no longer opens the sheet.
Added: `ic_status_comment.xml`, `btn_public_comment` in `activity_status_viewer.xml`.

Also: StatusRepliesBottomSheet comment avatar 44dp -> 32dp (matches reels item_reel_comment iv_avatar), text gap 12dp -> 10dp.

Public comments sheet polish: header "Replies" -> "Comments (N)" (+ delete texts), time = reels format
(DateUtils relative abbrev) instead of hh:mm a, full comment text (no 2-line cut), theme-aware colors
(textColorPrimary/Secondary, colorAccent) in StatusRepliesBottomSheet + StatusReplyBottomSheet for dark mode.

Comments sheet polish #2: reels-style row (name 13sp + time 11sp on one line, body 13sp, avatar top-aligned,
"Reply" action muted/medium), empty state in the sheet ("No comments yet") instead of a Toast (also shown after the
owner deletes the last comment), public-comment "Post" button disabled/dimmed until text is entered.

Public comment sheet #3: 300-char hard cap (typing + paste, toast "Max 300 characters"), counter shown from 250 and red from 270
(same limits as reels ReelCommentFragment), own 32dp avatar next to the input (loaded from users/{myUid}).

Comments sheet #4 (behavior):
- LIVE list (listener on status/{owner}/{id}/replies while the sheet is open); open inline reply + draft survive refreshes.
- "Reply" on a comment = PUBLIC "@name ..." comment (postPublicReply); no private DM. Commenter being replied to gets a push too.
  Removed StatusRepliesBottomSheet.sendInlineReply (private DM).
- Row tap -> private chat with commenter is OWNER-only now.
- Delete: owner can delete any comment, commenter can delete their OWN (long-press + visible "Delete"); matches firebase rules.
- postPublicReply now takes a PostCallback (RESULT_POSTED / RESULT_QUEUED / RESULT_FAILED). Compose sheet shows "Posting…", closes only
  on success/queued (8s no-ack = queued/offline toast), keeps the text and offers retry on failure. Own profile cached so posting
  doesn't depend on a network read.

Comments sheet #5/#6:
- Keyboard: both sheets use SOFT_INPUT_ADJUST_RESIZE; comments list opens fully expanded (skipCollapsed) and the inline reply box is
  scrolled above the keyboard when opened.
- Avatar/name tap -> UserProfileActivity (by class name, same as reels MentionSpanUtils; extras uid/name/photo).
- Long-press menu: Copy (everyone) + Delete (owner / own comment). Avatar has a contentDescription.
