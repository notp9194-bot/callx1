# v314 — Group "🔒 Waiting for encryption key…" (persistent) fix

## Symptom reported
Incoming group text messages permanently show the
`GroupE2EManager.WAITING_FOR_KEY_MARKER` placeholder for one or more
senders in a group — every message from that sender, not just the first
one, and it never clears no matter how many times the group chat is
reopened. (Same report also included 1:1 chats stuck on
`E2EEncryptionManager.DECRYPT_FAILED_MARKER` — that side already
self-heals as of v239; see `UPGRADE_NOTES_v239_E2EDecryptSelfHeal.md`.
Both devices still need to be on this build for either fix to complete
the handshake — see that file's "If it's still happening" section.)

## Root cause (group side — the part v239 didn't cover)
Group text uses Sender Keys (`GroupE2EManager`), not the 1:1 Double
Ratchet directly. Each member generates their OWN Sender Key and seals a
copy for every other member individually
(`distributeOwnSenderKey()`) — but only ONCE per member per keyId:
`loadDistributedTo(groupId, keyId)` / `markDistributedTo()` remembers
"who have I already sent this key to" **on the sender's own device**,
and that record is untouched by anything happening on the recipient's
device.

If a member reinstalls the app (or clears data, or logs into a new
device), their locally-received copy of every other member's Sender Key
is gone — but from every OTHER member's point of view, that person
already has their key, so `distributeOwnSenderKey()` keeps skipping them
forever. This is exactly the same shape of bug v239 fixed for 1:1
(`handshakeAcked` staying permanently true on the healthy side) — the
group version just runs one layer higher, per Sender Key instead of per
ratchet session.

`decryptEnvelope()`'s "we don't have this sender's key" branch already
called `importIncomingSenderKeys()` to opportunistically re-check the
stuck member's own mailbox — but that only helps if the sender's key is
already sitting there. If every other member believes it was already
delivered, nothing new ever lands in that mailbox, and the marker
persists on every message from that sender, forever, even across
repeated chat re-opens.

## Fix — self-healing resend request (mirrors v239's 1:1 fix exactly)
When `decryptEnvelope()` hits the "no Sender Key for this sender" case,
it now ALSO fires a lightweight request over Firebase Realtime DB (no
E2E server change needed) asking that specific sender's device to
forget it already delivered its key to us and redistribute it:

- **New Firebase node:**
  `groupSenderKeyRequests/{targetUid}/{groupId}/{requesterUid} =
  ServerValue.TIMESTAMP`. Carries no plaintext or key material — just
  "please resend your group key to me." Rate-limited to one request per
  (group, sender) per 5 minutes.
- **New listener**
  (`GroupE2EManager#listenForGroupKeyRequests`, started once per process
  in `CallxApp#onCreate`'s background-init thread, right next to
  `E2EEncryptionManager#listenForReKeyRequests`): on seeing a request,
  removes the requester from that group's "already sent" record for our
  CURRENT Sender Key (`removeDistributedTo`) and calls
  `distributeOwnSenderKey()` again — which now re-sends because the
  requester is no longer in the skip list. Only resends to someone still
  on our known-members list for that group, so a member who genuinely
  left/was removed can't use a forged request to claw back a key they
  were deliberately cut off from by rotation.
- **New security rules:** `firebase_group_senderkey_request_rules.json`
  — same shape as `firebase_e2e_rekey_rules.json`, one level deeper for
  the groupId. Needs to be merged into the deployed RTDB rules.

Once the fresh Sender Key lands back in the stuck member's mailbox, the
EXISTING self-heal signal (`notifySenderKeyHealed` inside
`importIncomingSenderKeys`, already wired to `GroupChatActivity` before
this fix) fires automatically and retries every message from that
sender that was stuck on `WAITING_FOR_KEY_MARKER` — no changes needed
there.

## Known limitation (expected, matches WhatsApp/Signal group behavior)
Messages sent **before** the resend completes stay unreadable — they
were encrypted with a Sender Key chain state the stuck member's device
never received, and the hash chain can't be rewound. Everything sent
**after** the resend decrypts normally.

## Files changed
- `core/src/main/java/com/callx/app/utils/GroupE2EManager.java` —
  `requestSenderKeyResend()`, `listenForGroupKeyRequests()`,
  `forceRedistributeSenderKeyTo()`, `removeDistributedTo()` added; the
  "no Sender Key for this sender" branch in `decryptEnvelope()` now also
  calls `requestSenderKeyResend()`.
- `core/src/main/java/com/callx/app/utils/FirebaseUtils.java` —
  `getGroupSenderKeyRequestsRef()` added.
- `app/src/main/java/com/callx/app/CallxApp.java` — starts
  `listenForGroupKeyRequests()` once at app init (background thread) for
  the logged-in user, next to the 1:1 re-key listener.
- `firebase_group_senderkey_request_rules.json` — new RTDB rules for the
  `groupSenderKeyRequests` node (needs manual merge into deployed
  rules).

## If it's still happening after this update
Requires **every** device in the group to be on this build — the stuck
member's device needs the new `decryptEnvelope()` branch to send the
request, and the sender whose key is missing needs the new listener to
receive and act on it. As a manual workaround, the sender can trigger an
immediate redistribution by leaving and re-adding the stuck member (or
anyone) to the group, which forces `rotateSenderKey()` on every
remaining member's device the next time they open the group.
