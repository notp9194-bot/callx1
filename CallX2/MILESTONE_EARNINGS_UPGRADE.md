# Reels Milestone Earnings

This upgrade adds the creator-facing Level 1–8 milestone journey and the admin
review/configuration flow.

## User flow

- Settings → Creator Tools → Milestone Earnings opens the full progress screen.
- Progress is based on unique reels liked, accounts followed, and explicit
  WhatsApp shares.
- Generic share, copy link, in-app share, Story, Status, Instagram, Facebook,
  and X shares are not counted.
- Completing a level credits its configured reward exactly once.
- Level 3 completion unlocks withdrawal requests.
- Rewards and withdrawal history are visible in the History action.

## Admin flow

- Admin Dashboard → Reels monetization now includes milestone earnings.
- Admin can review/approve/mark-paid/reject milestone withdrawals.
- Rejecting a withdrawal restores the creator balance.
- Admin can edit the level rules using:
  `level:likes:following:shares:rupees`
  (one level per line).

## Backend deployment

Deploy the updated `functions/index.js` and merge the updated rules from
`firebase_rules/firebase_monetization_rules.json`. If the project deploys the
combined rules file, the milestone entries are also present in
`firebase_rules_deployed_updated.json` and
`firebase_rules_deployed_updated_v2.json`.

The Android app calls `milestoneEarningsAction`; balances, completed levels,
and payouts are never writable by the client.