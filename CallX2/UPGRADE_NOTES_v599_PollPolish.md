# v599 — Chat poll polish (create validation, instant vote UI, vote debounce)

1. CreatePollDialog: inline errors (no silent drops). Duplicate options (case/whitespace-insensitive) are
   rejected on the offending row; question max 200 / option max 100 chars; hints are "Option 1..N" and
   renumber on remove; new row takes focus; empty rows are ignored but >= 2 real options are required.
   New strings in strings_poll.xml.
2. Instant vote UI: new `MessagePagingAdapter.applyLocalPollVotes()` (PAYLOAD_POLL rebind of just the poll
   bars) is called right after a tap, so the bar/tick updates immediately, also offline. If Firebase rejects
   the write, UI + Room are rolled back and "Couldn't save your vote" is shown.
3. Debounce / redundant writes: `PollVoteHelper` ignores the same message+option tapped again within 300 ms
   and skips the Firebase write when a single-choice poll is re-tapped on the option that is already the only
   vote. 1:1 (ChatPollController) and group (GroupChatActivity.castPollVote) now share this one helper.
