# v187 — E2EE: raw "e2r1:{...}" JSON showing in chat bubbles (FIXED)

## Bug
1:1 chat mein E2E-encrypted messages kabhi kabhi decrypt hokar plaintext
dikhte, phir thodi der baad wapas raw `e2r1:{"dh":...,"n":...,"ct":...}`
JSON blob mein badal jaate — jaisa reported screenshot mein tha.

## Root cause
`ChatActivity.attachFirebaseListener()` mein DO alag `ChildEventListener`
lage hote hain:

- `messageListener` — naye messages ke liye. Iska `onChildChanged` sahi
  se `decryptIncomingIfNeeded(m)` call karta hai `saveToRoom()` se pehle.
- `statusSyncListener` — sirf delivery/read tick updates track karne ke
  liye, last `STATUS_SYNC_WINDOW` messages pe (see TICK FIX comment,
  v19_TickDeltaSyncFix). Iska `onChildChanged` **`decryptIncomingIfNeeded()`
  call hi nahi karta tha** — seedha `saveToRoom(m, true)` bula deta tha
  with the raw `Message` snapshot.

Firebase pe stored `text` field E2E message ke liye HAMESHA ciphertext
hota hai (yehi E2EE ka poora point hai) — sirf `status`/`deliveredAt`/
`readAt` change hote hain, text nahi. Toh jab bhi koi tick update aata
(sent → delivered → read — jo bohot frequent hota hai), yeh listener
fire hota, aur `saveToRoom()`'s Room `REPLACE` us row ki pehle se
decrypt ki hui plaintext ko wapas raw ciphertext se overwrite kar deta.
Chat abhi khula hone ki wajah se ticks turant flip hote hain, isliye
issue bohot jaldi aur reliably reproduce hota tha.

## Fix
`statusSyncListener.onChildChanged` mein bhi `decryptIncomingIfNeeded(m)`
call kiya `saveToRoom()` se pehle — exactly `messageListener` jaisa.

File: `feature-chat/src/main/java/com/callx/app/conversation/ChatActivity.java`

Verified `ChatRepository` aur `StarredMessagesActivity` ke E2E decrypt
paths already sahi the — sirf yeh ek missed call thi.
