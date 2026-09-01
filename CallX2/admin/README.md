# CallX Admin

Standalone app (separate `applicationId`, separate installable APK) for
operating the CallX2 production backend. It talks to the **same Firebase
project** as CallX2. The signed-in account's `admins/{uid}` policy decides
which controls are available. Legacy `admins/{uid}: true` entries continue to
work as `super_admin`.

## What it does

1. **Login** (`AdminLoginActivity`) — Firebase Auth email/password sign-in,
   then checks `admins/{uid}`. Not on the list → signed back out immediately.
2. **Control center** (`AdminDashboardActivity`) — live user/call/report/
   verification metrics and links to every operational module.
3. **Users** (`AdminUsersActivity`) — UID lookup, activate/suspend/ban,
   Firebase Auth refresh-token revocation (force logout), Auth/profile
   deletion, and a global view of `blocked` + `permaBlocked`.
4. **Moderation** (`AdminReportsActivity`) — central queue for user, reel,
   comment/reply, X, sound, group, channel and community reports. Resolve,
   dismiss, and remove supported target content with an audit event.
5. **Organisation oversight** (`AdminOperationsActivity`) — groups, channels
   and communities can be suspended or deleted by a super-admin.
6. **Payments & trust** — transaction ledger view plus dispute, refund request,
   fraud review and KYC review cases. These are operational ledger controls;
   an actual money movement/refund still requires a real payment-gateway
   adapter. The current `feature-payments` implementation is explicitly a
   local/mock ledger.
7. **Communication** (`AdminCommunicationsActivity`) — preview and send a
   system-wide FCM announcement through the server-side fan-out function.
8. **Crashes & audit** — the main app now uploads a bounded crash envelope to
   `crash_reports`; the admin app combines it with `admin_audit`.
9. **Config** — writes runtime values below `appConfig` (maintenance mode,
   kill switches and feature thresholds) through the callable backend.
10. **Admin-of-admins** — add/update/remove role records for `super_admin`,
    `moderator`, `support` and `finance`, with optional permission grants.
11. **Verification** — the existing badge approval queue remains available.

Sensitive operations do not use direct client-side writes. They call the
`adminAction` Cloud Function, which checks the caller's admin policy, uses the
Firebase Admin SDK for Auth operations and appends an immutable audit record.

## Required deployment steps

This module ships with `admin/google-services.json` **copied from `:app`**
as a placeholder. It will **fail to build** as-is, because Google's
`google-services` Gradle plugin checks that the module's `applicationId`
(`com.callx.app.admin`) is actually registered inside that JSON file, and
right now it only lists `com.callx.app`.

To fix:

1. Firebase Console → your project (`sathix-97a76`) → ⚙ **Project settings**
   → **Your apps** → **Add app** → Android.
2. Package name: **`com.callx.app.admin`** (must match exactly).
3. Skip the SDK-setup steps shown after (already wired into this module) —
   just download the generated `google-services.json`.
4. Replace `admin/google-services.json` with the downloaded file.

### Deploy the backend

From the project root:

```text
cd functions
npm install
firebase deploy --only functions:adminAction
```

Merge `firebase_rules/firebase_admin_rules.json` into the live database rules
and deploy the merged rules. Do not deploy the fragment by itself.

### Adding the first admin (manual bootstrap)

`firebase_rules/firebase_verification_rules.json` makes `admins/{uid}`
**read-only from every client, always** — there's no "grant myself admin"
button anywhere, in either app, on purpose. Add the first admin by hand:

Firebase Console → Realtime Database → your data → `admins` → **+** →
key: `<that person's Firebase Auth uid>`, value: `true`.

(That person needs a Firebase Auth account first — either an existing
CallX2 user's uid, or create one via Console → Authentication → Add user.)

After bootstrap, use **Admin-of-admins** to manage the remaining roles. Keep at
least two `super_admin` accounts so a mistaken removal does not lock the team
out.

## Current production caveats

- Firebase Auth deletion, token revocation, FCM broadcast, moderation reads and
  audit writes are server-backed by `adminAction`.
- The payment feature in this archive uses `MockPaymentService` and Room
  local storage. The admin payment screen records disputes/fraud/KYC/refund
  cases and cannot refund real money until a gateway is wired.
- DAU/MAU are calculated from the existing `users/*/lastSeen` values, and
  storage usage is read from an optional trusted `storageMetrics/totalBytes`
  aggregate. Historical analytics and exact Firebase Storage byte totals need
  a scheduled aggregation job or Analytics/Storage export.
- Realtime Database reports written by the current app are aggregated by the
  callable function; report nodes remain write-only to normal users.
