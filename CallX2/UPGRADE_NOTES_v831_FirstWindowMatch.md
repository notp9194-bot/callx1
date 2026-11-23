# v831 — chat-open wrong-then-right tail/avatar/name at the top boundary
Root cause: MessageKeysetPagingSource ignored PagingConfig.initialLoadSize on the no-anchor REFRESH (chat open) and loaded pageSize=15,
while the warm snapshot (LastMessagesCache / disk) is 20 rows. Warm(20) -> real(15) diff removed the 5 oldest rows; the new row 0 has no
predecessor, so isBubbleRunHead/isGroupNameRunHead default to true (tail + avatar + group name) until the PREPEND page arrived and
refreshBubbleTailRow/refreshGroupSenderRow flipped it back (name hide = real re-measure in groups).
Fix: first load = max(pageSize, params.getLoadSize()) (=20) so warm and real windows are identical -> no removal diff, one fewer flip.
Not changed: warm window size (hard-coded 20 in LastMessagesCache/DiskCache/ChatRepository/CacheManager/GroupChatActivity + memory/disk caps).
Not compiled offline — run CI.
