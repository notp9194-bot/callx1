# v793 — Voice Rooms UI polish (still UI only, demo data)

Builds on v792 (feature-voicerooms module). No backend / audio yet.

## Polish done
1. Speakers: each cell = 1/4 of the row, so 4 speakers always fit (360dp phones too).
2. Room layout: CoordinatorLayout + AppBarLayout. Header (banner/topic/speakers/audience) collapses as chat scrolls; pinned bar stays sticky; chat is a real RecyclerView; "N new messages" pill; own message auto-scrolls; demo incoming chat.
3. VrMicView: Canvas-drawn premium studio-mic illustration (gradients, grille, blue band, orbit ring, sparkles, gentle float). Used in room banner, Home cards, Home empty state.
4. Avatars: gradient initials (InitialsDrawable) + Glide loading when Member.photoUrl is set.
5. Animations: speaking pulse ring, floating hearts on reactions, gift burst, mic bounce, hand wave, mini-player slide-in, skeleton pulse.
6. Consistency: vector raise-hand icon (vr_ic_hand), real emoji picker sheet (insert at cursor).
7. States: Home loading skeleton, pull-to-refresh, empty states (rooms, chat, audience, requests), mini player when you minimize a room (back / chevron); tap to return, mic toggle, X to leave.
8. Host controls (More -> "Preview as host"): speaker requests sheet (accept/decline), member action sheet (mute/unmute, move to audience, invite to speak, remove, profile, follow), raised-hand badges on audience, request count on the action bar.
9. Accessibility + strings: contentDescription on all interactive icons, decorative icons hidden from TalkBack, layout text + key toasts moved to strings_vr.xml (dynamic/concatenated strings still in code).

## Behaviour notes
- Back / top-left arrow in a room = minimize (mini player on Home). "Leave quietly" / "End room" (More sheet) = leave for good.
- Re-opening a room you started resets its demo state (no backend yet).
- Dependencies added to the module: swiperefreshlayout 1.1.0, coordinatorlayout 1.2.0 (swiperefresh already used by app).

## Backend hook points (TODO(backend) in code)
Glide photoUrl, chat listener, hand-raise writes, publish permission on promote, coin deduction (gifts), real deep link, rooms query/loading.
