# v214 — Group Chat E2E Encryption (Sender Keys protocol)

Extends the existing 1:1 X3DH/Double-Ratchet E2EE (see
`UPGRADE_NOTES_v181_E2EEDoubleRatchet.md`) to **group chat text messages**,
using the same Sender Keys design WhatsApp/Signal use for groups.

## What's new

- **`core/.../utils/GroupE2EManager.java`** (new) — the whole protocol:
  - Each member generates their own random Sender Key (32-byte symmetric
    chain key + short keyId).
  - That key is handed to every other member individually, sealed with the
    **existing 1:1 Double-Ratchet session** for that pair (reuses
    `E2EEncryptionManager#ensureSession` / `#encrypt` — no new handshake
    machinery needed). The sealed blob lands at
    `groupSenderKeys/{groupId}/{recipientUid}/{fromUid}`.
  - Sending a group message = ONE encryption with the sender's own chain
    (HMAC ratchet, same `KDF_CK` construction as 1:1) — every member
    decrypts the same ciphertext, so it's O(1) per message, not
    O(members).
  - **Member removed/left** → every remaining member's device notices
    (membership diff run in `ensureGroupCrypto`) and rotates: throws away
    the old chain key, generates a new one, redistributes to the smaller
    member list. `GroupInfoActivity` also triggers this immediately on
    remove, instead of waiting for the next chat open.
  - **Member added** → existing members' devices notice the new uid and
    distribute their *current* key to them — no rotation needed.
- **`FirebaseUtils#getGroupSenderKeysRef`** (new) — mailbox path helper.
- **`GroupChatActivity`**:
  - `ensureGroupCrypto()` called once when the group chat opens.
  - `sendText()` encrypts a wire-only copy (`m.e2eWireText`) exactly like
    1:1's `doSendTextMessage()` — `m.text` stays plaintext for our own
    bubble/Room.
  - `firebasePushGroup()` swaps `m.text` → `m.e2eWireText` for the instant
    of the Firebase write only (mirrors `ChatMessageSender#firebasePushMessage`).
  - New `decryptIncomingGroupTextIfNeeded()` decrypts incoming messages
    before Room/adapter ever see them; own outgoing messages restore from
    a local plaintext cache instead of trying to decrypt their own
    send-only chain in reverse.
- **`GroupInfoActivity`** — remove-member handler now calls
  `rotateSenderKey()` immediately.
- **Server (`index.js`)** — new `POST /notify/group_key_rotate`: a
  data-only push (no visible notification, no key material) telling
  remaining members' apps to re-sync Sender Keys right away instead of
  waiting for their next chat open. Purely a speed optimization — the
  chat-open path is the correctness fallback either way.
- **`firebase_repost_rules.json`** — added rules for `groupSenderKeys`:
  a recipient can only read their own mailbox; a sender can only write
  under their own uid, and only if they're currently a group member.

## Wire format

`groupMessages/{groupId}/{msgId}/text` = `"ge2r1:" + {kid, n, iv, ct}` when
encrypted (same idea as 1:1's `"e2r1:"` prefix). Falls back to plaintext
if encryption fails for any reason (e.g. Sender Key not ready yet) —
never silently drops a message.

## Known trade-offs (documented, not bugs)

- Sender Key distribution/rotation is **lazy** — it completes next time
  each member's device opens the group chat (or immediately, if the
  `/notify/group_key_rotate` push is delivered and handled). A member who
  never reopens the chat between a rotation and a new message being sent
  will see `"🔒 Waiting for encryption key…"` for that message until they
  do.
- `CallxMessagingService` does not yet act on the `group_key_resync` push
  type — wiring that up is a follow-up if you want the fast path to
  actually short-circuit reopening the chat. Until then it's a no-op nudge
  and everything still self-heals on next open.
- Distributing a Sender Key rides over the 1:1 pairwise session, so it
  consumes one ratchet step of that pair's regular 1:1 chat — invisible to
  the user, same as how Signal's actual SKDM works.
