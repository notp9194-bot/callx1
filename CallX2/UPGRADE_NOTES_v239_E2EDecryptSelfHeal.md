# v239 — "🔒 Unable to decrypt message" (persistent) fix

## Symptom reported
Incoming text messages show as a lock-icon placeholder — in BOTH the
notification and the open chat — every time, not just once. Matches
`E2EEncryptionManager.DECRYPT_FAILED_MARKER` (`"🔒 Unable to decrypt
message"`) exactly.

## Root cause
The Double Ratchet session with a partner lives ONLY in this device's
local `EncryptedSharedPreferences` (`e2e_sessions_v2`). If that gets
wiped — app reinstall, "Clear data," or logging into a new device — the
local session is gone, but the **partner's session with us is untouched**.

`encrypt()` only attaches X3DH handshake material (`"ik"`) to an
outgoing envelope while `!session.handshakeAcked`. Once any message from
us has ever been successfully decrypted by the partner, their side's
`handshakeAcked` flips to `true` — permanently, from their point of view
— so their client stops attaching `"ik"` on every future send. That's
correct behavior for a healthy, established conversation.

The problem: if *we're* the side that lost our session, an incoming
envelope with no `"ik"` and no local session is structurally
undecryptable — `decryptEnvelope()` correctly detects this
(`s == null && !header.has("ik")`) and returns the failure marker — but
there was no recovery path. The partner keeps sending ordinary ratchet
messages, we keep failing to decrypt them, forever, because from the
partner's side nothing ever signals that we need a fresh handshake.

## Fix — self-healing re-key request
When decryption hits exactly that "no session, no handshake data" case,
we now fire a lightweight request over Firebase Realtime DB (no E2E
server change needed) asking the partner's device to drop its session
with us and re-key:

- **New Firebase node:** `e2e_rekey_requests/{targetUid}/{requesterUid} =
  ServerValue.TIMESTAMP`. Carries no plaintext or key material — just
  "please re-key with me." Rate-limited to one request per partner per
  5 minutes so a burst of undecryptable messages doesn't spam it.
- **New listener** (`E2EEncryptionManager#listenForReKeyRequests`,
  started once per process in `CallxApp#onCreate`'s background-init
  thread, same lifetime pattern as `PresenceManager`'s listeners): on
  seeing a request addressed to us, clears our session with the
  requester and calls `ensureSession()` again — which, since the session
  is now gone, re-fetches the requester's current key bundle and starts
  a brand-new X3DH handshake, attaching `"ik"` on the very next message
  we send them. That message (and everything after it) decrypts fine on
  the requester's side.
- **New security rules:** `firebase_e2e_rekey_rules.json` — a requester
  can only write a request as themselves; only the addressed partner can
  read/clear their own inbox. Needs to be merged into the deployed RTDB
  rules (same pattern as the other `firebase_*_rules.json` files in this
  repo).

## Known limitation (expected, matches Signal/WhatsApp behavior)
Messages the partner sent **before** the re-key completes are gone for
good — their ratchet keys were derived under a session we no longer
have, and forward secrecy means that's by design, not a bug. Everything
sent **after** the re-key decrypts normally. This is the same tradeoff
Signal/WhatsApp make when a device loses its keys.

## Files changed
- `core/src/main/java/com/callx/app/utils/E2EEncryptionManager.java` —
  `clearSession()`, `requestReKey()`, `listenForReKeyRequests()` added;
  the "no session, no ik" branch in `decryptEnvelope()` now triggers
  `requestReKey()` instead of just failing silently.
- `app/src/main/java/com/callx/app/CallxApp.java` — starts
  `listenForReKeyRequests()` once at app init (background thread) for
  the logged-in user.
- `firebase_e2e_rekey_rules.json` — new RTDB rules for the
  `e2e_rekey_requests` node (needs manual merge into deployed rules).

## If it's still happening after this update
This fix requires **both** devices to be on the updated build — the
side that lost its session needs the new `decryptEnvelope()` to send the
request, and the healthy side needs the new listener to receive and act
on it. If only one side has updated, wait for the other to update, or as
a manual workaround the still-broken side can log out and log back in
(which re-runs `ensureSession()` for every open chat) to force a fresh
handshake.
