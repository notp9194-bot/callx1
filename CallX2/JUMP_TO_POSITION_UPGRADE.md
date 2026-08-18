# "Jump to their position" — watching banner upgrade

Adds scroll-to-message on top of the existing watching banner / per-message
viewing dot system, for both 1:1 and group chats.

## 1:1 chat (`ChatPresenceController`)
- Tracks the partner's most recent `chatViewing/{chatId}/{partnerUid}`
  value in `lastPartnerViewingMessageId`.
- Tapping the banner now calls `delegate.navigateToOriginal(id)` — reuses
  the exact same scroll+highlight path as "reply jump to original"
  (`ChatActivity#navigateToOriginalMsg`), including the Room DB fallback
  for messages that aren't currently paged into the adapter.
- If the partner has the screen open but hasn't settled on a specific
  bubble yet, shows a toast instead of jumping nowhere.

## Group chat (`GroupWatchingController`)
- New `Delegate.navigateToMessage(String)` method, implemented in
  `GroupChatActivity#scrollToMessageId` (same adapter-scan + Room
  fallback approach, factored out of the existing reply-jump code so both
  features share one implementation).
- Tracks a raw `uid -> messageId` map (`lastViewingByUid`) alongside the
  existing aggregated dot-id set, so we know what EACH watcher is looking
  at, not just "someone is on bubble X".
- Tapping an individual avatar in the banner (1st or 2nd overlapping
  avatar) jumps straight to that person's position. The avatar consumes
  the tap before it reaches the parent banner, so:
  - Avatar tap → jump to that person
  - "+N" badge / rest of banner tap → opens the full watchers bottom sheet
    (unchanged)
- Each row in the watchers bottom sheet is now tappable too — shows a
  small subtitle ("Viewing a message · tap to jump" vs "Has the chat
  open") and jumps to that member's position on tap, dismissing the sheet
  first.
- Falls back to a toast naming the member if they don't have a specific
  message focused yet.

No new Firebase paths — this is purely a UI/controller layer on top of
the existing `chatPresence` and `chatViewing` nodes already shipped in
the per-message-viewing / active-ago / watchers-sheet build.
