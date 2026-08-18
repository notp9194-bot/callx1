# Linked Devices — Setup Guide

Full WhatsApp-Web-style companion sync: pair a browser via QR code from
**Chat → ⋮ → Security → Linked Devices → Link a Device**, manage/revoke
sessions, and mirror chats live in `callx2-web/callx2-web.html`.

## What's included

| Piece | Where |
|---|---|
| Pairing + device-list logic | `core/src/main/java/com/callx/app/linkeddevice/` |
| "Linked Devices" screen, QR scanner, approval sheet | `feature-chat/.../chat/linkeddevice/` |
| Entry point | Chat → ⋮ → Security → **Linked Devices** row |
| Custom-token minting backend | `functions/index.js` |
| Web companion (single file) | `callx2-web/callx2-web.html` |
| Database rules | `firebase_rules/firebase_linked_devices_rules.json` |

## Why a Cloud Function is required

The web client can't just "log in" as the account — that would mean shipping
real credentials to the browser. Instead:

1. Phone approves the scanned QR → writes `status: approved` to
   `pairingSessions/{code}`.
2. **Only** the Cloud Function (Admin SDK) can then mint a Firebase Auth
   *custom token* for that exact `uid` and write it back.
3. The web page signs in with that one-time token, then deletes the pairing
   node so it can never be reused.

This is the same pattern WhatsApp/Telegram Web effectively use — the phone
is the authenticator, the browser session is a scoped, revocable delegate.

## Deploy steps

```bash
# 1. Merge firebase_rules/firebase_linked_devices_rules.json's "rules" block
#    into your existing Realtime Database rules (Console → Realtime Database → Rules,
#    or your merged rules file), then publish/deploy them.

# 2. Deploy the Cloud Function
cd functions
npm install
firebase deploy --only functions

# 3. Configure the web client
# Open callx2-web/callx2-web.html and replace YOUR_FIREBASE_CONFIG with the
# same project's Web app config (Firebase Console → Project settings → Your apps → Web).

# 4. Host callx2-web.html anywhere static (Firebase Hosting, S3, Netlify...).
#    It's a single self-contained file — no build step.
firebase deploy --only hosting   # if using Firebase Hosting
```

## Testing locally

- Open `callx2-web.html` directly in a browser (or `firebase serve`).
- On your phone: Chat → ⋮ → Security → Linked Devices → Link a Device → scan.
- Approve on the phone → the browser signs in and shows your chat list within
  a second or two.
- Remove the device from the phone's Linked Devices list → the browser tab
  gets force-logged-out immediately (`revokedOverlay`).

## Known scope / next steps

- The web client mirrors **1:1 chats and group chats** (`contacts/{uid}` +
  `userGroups/{uid}` → `groups/{gid}`, `messages/{chatId}` and
  `groupMessages/{groupId}`), with full previews for text, images, video,
  audio, documents, GIFs, stickers, location, contact shares, and reel
  shares.
- **Sending** now covers text, file attachments (📎 — image/video/document,
  auto-detected by MIME type), and in-browser voice messages (🎤,
  `MediaRecorder` → uploaded as webm). Uploads go through the exact same
  `${SERVER_URL}/cloudinary/sign` endpoint `ChatMediaController` already
  uses on the phone (see `functions` note below) — no new backend needed,
  **but that endpoint's CORS policy must allow the origin you host
  `callx2-web.html` on**, or the browser will block the sign request.
- Web-side cap is a flat 25MB per file; the phone's own per-type limits
  still apply independently server-side.
- 1:1 messages sent through the E2E media path (`mediaKeyEnc` present) show
  as "🔒 Encrypted photo — open on your phone" rather than a real preview —
  intentional, since the Double Ratchet session only lives in the phone's
  keystore.
- View-once messages show a placeholder and aren't viewable from the web,
  matching the phone's own intent for that feature. Sending view-once
  from the web isn't wired up (low value for a desktop companion).
