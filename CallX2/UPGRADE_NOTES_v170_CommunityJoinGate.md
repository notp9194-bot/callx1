# v170 — Community Join Gate

## Problem
`CommunityActivity` had no membership check at all. Anyone who tapped
"View Community" from a chat profile card (`ChatProfileCardBinder`) landed
directly on Feed/Announcements/Events/Groups/Members — no Join button, no
gating. Firebase rules already require membership to read `posts`/`events`,
so non-members just hit silent permission-denied / empty screens with no
way to actually join. Only the invite deep-link flow could add a member.

## Fix
- **CommunityRepository**: added `checkMembership()`, `fetchCommunityPreview()`,
  `hasPendingJoinRequest()` for the gate.
- **CommunityJoinRequestDao**: added `countMyPendingSync()` to detect an
  already-sent pending request locally.
- **CommunityActivity**: now resolves membership *first*. Non-members see a
  join-gate screen (icon/name/description/member count) instead of the
  tabs:
  - Public community → **"Join Community"** button → instant `addMember()`.
  - Private community → **"Request to Join"** button → `sendJoinRequest()`,
    then **"Request Pending"** state.
  - Feed/Members/Groups/Events/overflow menu are unreachable until the user
    is a confirmed member.
- **activity_community.xml**: added the `group_join_gate` view group.
- **CommunityInviteLandingActivity**: fixed an `observeForever()` leak — the
  observer is now removed after the first real emission instead of living
  forever past `finish()`.

## Not changed
Firebase rules were already correct (membership-gated reads on posts/events).
No rule changes needed.
