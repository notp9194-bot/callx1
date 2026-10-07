# v463 — Reel comments: reply paging + colour resources

## 1. Reply paging
- loadRepliesInto() still fetches the whole reply node once, but now renders only
  the first 3 (REPLIES_INITIAL). "View N more replies" (item_reel_reply_more.xml,
  tag "reply_more_row") appends the next 8 (REPLIES_STEP) from an in-memory cache
  (repliesCache) - no network, no container rebuild.
- The more-row uses ReplyConnectorView as the LAST row, so the thread line and the
  arrow join it like any reply (updateReplyConnectors() needed no change).
- Refreshes (like / edit / delete) keep the pages already opened (repliesShown);
  collapse and fresh expand reset to the first page.
- Posting a reply while older ones are hidden reveals all of them first, then
  appends the new reply at the end (and updates the cache).
- Note: network cost is unchanged (full node still downloaded); the saving is view
  inflation / memory on long threads. A server-side limitToFirst(page) would be
  the next step if reply nodes get very large.

## 2. Hardcoded colours -> resources (values + values-night)
reel_accent_cyan #6BCFEF (search tints, @mention text), reel_pin_icon #00E5FF,
reel_pin_label #FF9800, reel_like_heart #FF416C (heart + "Liked by creator";
HEART_LIKED_COLOR constant removed), reel_error_red #FF3B5C,
reel_reaction_chip_text (#5B5BF6 light / #9A9AFF dark - only dark changes).
Layout "#FFFFFF" -> @android:color/white. No other visual change.
