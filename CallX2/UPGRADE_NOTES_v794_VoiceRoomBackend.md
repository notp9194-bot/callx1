# v794 — Voice Rooms backend (Steps 1–5)

## Deploy order (zaroori)
1. **Firebase rules**: `firebase_rules_FINAL_v794.json` publish karo (naye nodes: voiceRooms, voiceRoomList, wallets; baaki rules unchanged).
2. **Render server**: `server_index.js` -> `index.js` (+ `functions_index.js` -> `functions/index.js` ya server ke bagal me).
   - `npm install livekit-server-sdk@^2.6.1` (Render ke package.json me dependency add karo).
   - Render -> Environment: `LIVEKIT_URL` (wss://callx2-....livekit.cloud), `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`.
   - Check: server log me `[OK] Voice rooms routes attached (LiveKit ready)` aana chahiye.
3. **App**: is zip se build. Gradle sync zaroori (LiveKit + Kotlin add hua).

## Secret safety
LiveKit secret chat me aa gaya tha — LiveKit dashboard me wo key delete karke nayi banao aur sirf Render env me daalo. Zip/code me koi secret nahi hai.

## Kya bana
- Step 1: `voiceRoomList/{id}` (Home list) + `voiceRooms/{id}/{meta,speakers,audience,handRaises,chat,pinned,polls}`; VrDemoData hata (VrCatalog sirf static catalog).
- Step 2: `/voiceroom/token` (role ke hisaab se canPublish), LiveKit audio (`VrAudio.kt`), speaking ring = LiveKit ActiveSpeakers, mic mute/unmute.
- Step 3: `/voiceroom/action` promote/demote/mute/unmute/kick/end/leave — sab server pe, LiveKit permission server badlata hai.
- Step 4: `/voiceroom/gift` -> `voiceRoomSendGift` (functions_index.js): server price, coins `wallets/{uid}/coins` se transaction me kat'te hain, refund on failure.
- Step 5: share link `https://callx-server.onrender.com/room/{id}` + `callx://room/{id}`, follower push (`vr_notif_type=room_live`), host heartbeat + stale/ended cleanup (server, har 30s), foreground service (`VoiceRoomService`) + real mini player (`VrSession`).

## WebRTC conflict
`stream-webrtc-android` app + feature-calls se hata diya; org.webrtc ab LiveKit ke WebRTC se aata hai. Calls ka code (CallActivity, GroupCallActivity) unchanged hai — build me agar koi org.webrtc API mismatch aaye to wahi file fix karni hogi.

## Known limits
- Coins: app me abhi coins kharidne ka flow nahi hai. Test ke liye Firebase console me `wallets/{uid}/coins` = number daalo.
- Audience list: preview ke liye pehle 60 members; total count server se (`meta/audienceCount`).
- "Mute room audio" aur "Preview as host" (demo rows) hata diye; member sheet se demo "Follow" hata (View profile hai).
- Music sheet abhi UI-only.
- Host listener count job: har 30s recount (chhote/medium scale ke liye).
