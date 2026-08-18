# Poll Upgrade — Multi-Select ("Tick Every Option") + Advanced Polls

## What changed

**1. Multi-choice voting — tick more than one option**
Polls now support a "Allow multiple answers" toggle when creating a poll
(`CreatePollDialog`). When enabled, voters can tap *any number* of options —
each tap ticks/un-ticks just that option (checkbox-style), instead of the
old radio behaviour where picking a new option replaced your previous vote.

**2. Advanced poll upgrades**
- New checkbox tick icons (`ic_poll_checkbox_filled` / `ic_poll_checkbox_unselected`)
  shown for multi-choice polls; the original radio icons are kept for
  single-choice polls.
- A subtitle line under the poll question now tells voters what kind of
  poll it is: "Select one answer" vs "Select one or more answers".
- Vote percentages and the leading-option highlight are now computed from
  *all* ticks across all voters (a multi-select voter contributes to every
  option they ticked).
- "X people voted" label now reflects the number of distinct voters, not
  the number of ticks.
- Per-voter votes are now stored as a list of option indices
  (`uid -> [idx, idx, ...]`) instead of a single index, both in Room
  (`pollVotesJson`) and Firebase (`messages/{id}/pollVotes/{uid}`).
- New `pollMultiChoice` flag on `Message` / `MessageEntity` records whether
  a given poll allows multiple answers. Set once at creation time and
  immutable afterwards (same lifecycle as `pollAnonymous`).

## Data model

- `PollJsonUtil` reworked: `votesToJson` / `votesFromJson` now serialize
  `Map<String, List<Integer>>` instead of `Map<String, Integer>`.
  Old single-int Firebase/Room data (`{"uid1":0}`) is still read correctly
  and normalized into a one-element list on load (Room path only).
- Room: `AppDatabase` bumped to **v12**, new migration
  `MIGRATION_11_12` adds the `pollMultiChoice` column to `messages`.
- ⚠️ Firebase POJO mapping (`DataSnapshot.getValue(Message.class)`) maps
  `pollVotes` directly by reflection. Any *pre-existing* poll in your
  Firebase database that still has the old `{"uid1": 0}` integer format
  will fail to deserialize into the new `List<Integer>` shape. This only
  affects in-flight/legacy poll messages created before this upgrade —
  new polls created after upgrading are unaffected. If you have live
  polls you care about, run a one-time migration script over
  `messages/*/pollVotes` to wrap each integer value in a single-element
  array before rolling this out, or simply let old polls close out
  naturally.

## Files touched

- `core/.../utils/PollJsonUtil.java` — vote (de)serialization + counting
- `core/.../models/Message.java`, `core/.../db/entity/MessageEntity.java` — `pollVotes` type, new `pollMultiChoice` field
- `core/.../db/AppDatabase.java` — v11 → v12 migration
- `core/.../res/drawable/ic_poll_checkbox_filled.xml`, `ic_poll_checkbox_unselected.xml` — new tick icons
- `feature-chat/.../chat/ui/CreatePollDialog.java`, `res/layout/dialog_create_poll.xml` — "Allow multiple answers" toggle
- `feature-chat/.../conversation/MessagePagingAdapter.java` — multi-select rendering + tap-to-toggle
- `feature-chat/.../conversation/ChatActivity.java`, `feature-chat/.../group/GroupChatActivity.java` — vote casting logic, entity↔model mapping
- `feature-chat/.../conversation/controllers/ChatMessageSender.java` — entity↔model mapping
- `feature-chat/res/layout/item_message_received.xml`, `item_message_sent.xml` — poll subtitle line
