# CallX Admin

Standalone app (separate `applicationId`, separate installable APK) for
reviewing verification-badge requests submitted from the main CallX2 app's
Profile screen. Talks to the **same Firebase project** as CallX2 — same
Realtime Database, same users — only the signed-in account's `admins/{uid}`
allowlist entry decides who can act.

## What it does

1. **Login** (`AdminLoginActivity`) — Firebase Auth email/password sign-in,
   then checks `admins/{uid}`. Not on the list → signed back out immediately.
2. **Review** (`AdminVerificationListActivity`) — lists every
   `verification_requests/{uid}` with `status == "pending"`. Each row:
   name, photo, submitted reason, **Approve** / **Reject**.
   - Approve → `users/{uid}/isVerified = true` (this is exactly the flag
     `SoundDetailFragment` and other screens already check to show the
     `ic_verified_pink` badge, now shared from `:core-lite`).
   - Reject → request `status` flips to `"rejected"` (user can re-request).

## One manual setup step (can't be scripted — needs Firebase Console access)

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

## Adding the first admin (also manual, by design)

`firebase_rules/firebase_verification_rules.json` makes `admins/{uid}`
**read-only from every client, always** — there's no "grant myself admin"
button anywhere, in either app, on purpose. Add the first admin by hand:

Firebase Console → Realtime Database → your data → `admins` → **+** →
key: `<that person's Firebase Auth uid>`, value: `true`.

(That person needs a Firebase Auth account first — either an existing
CallX2 user's uid, or create one via Console → Authentication → Add user.)

## Deploying the rules

The verification rules aren't live until deployed/merged — see the
`_instructions` block inside `firebase_rules/firebase_verification_rules.json`
itself for the exact steps (merge into your existing DB rules, then
`firebase deploy --only database`).
