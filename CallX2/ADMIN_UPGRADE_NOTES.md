# CallX Admin Control Center — upgrade notes

The original admin module only reviewed verification-badge requests. This
upgrade adds a separate production operations surface and a privileged
`adminAction` callable backend.

## Added admin surfaces

- Dashboard: users, DAU/MAU from `users/*/lastSeen`, online users, active calls,
  pending moderation and verification, and optional `storageMetrics`.
- User lifecycle: UID lookup, account status, Firebase Auth disable/delete,
  refresh-token revocation, and global `blocked`/`permaBlocked` inspection.
- Unified moderation queue: `reports`, `reelReports`,
  `reelCommentReports`, `reelReplyReports`, `community_reports`,
  `channelReports`, `groupReports`, `sound_reports`, and `x/reports`.
- Group/channel/community global suspend/delete controls.
- Payment operations: transaction view and case records for disputes, refunds,
  fraud review and KYC review.
- FCM announcement composer with server-side token fan-out.
- Runtime `appConfig` editor and admin/crash audit view.
- Role management for `super_admin`, `moderator`, `support`, and `finance`.
- Optional per-admin permission grants on top of the role.

## Security model

The Android app calls `adminAction`; it does not carry a service-account key.
The function verifies `admins/{uid}` on every call. The old boolean allowlist
is accepted as `super_admin`; new entries can be policy objects:

```json
{
  "role": "moderator",
  "permissions": {}
}
```

Merge `firebase_rules/firebase_admin_rules.json` into the live RTDB rules.
Deploy the callable function from `functions/` before opening the new admin
screens.

## Important scope boundary

The existing payment implementation uses `MockPaymentService` and Room local
storage. The admin UI records and audits trust cases, but a real refund or KYC
decision must be connected to the payment provider before it can move real
money. Likewise, exact Firebase Storage usage requires a trusted scheduled
aggregate in `storageMetrics/totalBytes`.