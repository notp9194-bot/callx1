# UPGRADE NOTES v186 — Chat Message Translation

## What's new

Long-press any text message (1-on-1 chat or group chat) → new
**"Translate"** option in the action sheet. Shows a dialog with the
original text and the translation into your phone's current
language, with a "Copy translation" button.

## How it works

- New backend endpoint `POST /translate` (index.js) — proxies to
  Google's free translate endpoint using Node's built-in `https`
  module (no new npm dependency, no API key/billing).
- New `MessageTranslator.java` (core module) — calls that endpoint
  from the app.
- `MessagePagingAdapter.ActionListener` gets a new default method
  `onTranslate(Message m)`; wired up in both `ChatActivity` and
  `GroupChatActivity`.

## Why not the Cloudinary "Google Translation" add-on

That add-on only translates auto-generated Cloudinary asset TAGS
(from Imagga/Rekognition auto-tagging), not arbitrary free-text —
it has no endpoint for translating a chat message string. This
feature uses Google's translate engine directly instead, through
your own backend.

## Notes

- Target language = the phone's current locale, not a per-chat
  setting. If you want a language picker instead, that's a small
  follow-up.
- The free endpoint is unofficial — fine for normal usage volume,
  but if it ever gets rate-limited, swap `/translate` in index.js
  to use the paid Google Cloud Translation API (just needs an API
  key added to the same handler).
