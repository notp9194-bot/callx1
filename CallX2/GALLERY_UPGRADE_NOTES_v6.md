# Gallery / Multi-Media Upgrade — v6

Sab MessagePagingAdapter ke through hi route hota hai (jaisa bola tha).

## ✅ Done

1. **Multi-select + forward from gallery** — Long-press kisi bhi photo/video pe
   gallery (swipe view) ke andar select-mode kholta hai (checkboxes + top
   toolbar: count, Star, Delete, Forward). "Forward" se select kiye gaye
   items (ya pura group agar kuch select nahi kiya) ek hi multi_media message
   ke roop me forward hote hain — pehle sirf 1 item ka mediaUrl forward hota
   tha, ab puri mediaItems list jaati hai (GalleryForwardBridge → ChatActivity
   /GroupChatActivity → ContactsActivity → vapas ChatActivity pushMessage).

2. **Per-item caption** — Har mediaItem map me ab `"caption"` key store ho
   sakta hai (Room/Firebase schema change ki zaroorat nahi padi — mediaItems
   already generic `Map<String,Object>` hai). Gallery viewer me long-press →
   "Edit caption for this item" se set/edit hota hai, aur viewer me overlay
   text ke roop me dikhta hai. Group-grid thumbnail me abhi bhi sirf group
   caption dikhta hai (per-item caption sirf full-viewer me) — ye scoped
   trade-off hai taaki grid layout na tootéye.

3. **Save to gallery** — Top bar me naya Save button (image/video dono ke
   liye), MediaStore (`Pictures/CallX2`, `Movies/CallX2`) me save karta hai,
   cached file ho to wahi use karta hai warna download karta hai.

4. **Per-item delete/star** — Long-press menu / select-mode toolbar se ek
   single image/video group se nikal (delete) ya star kar sakte ho.
   Firebase me `mediaItems` array update hota hai (GalleryItemActionBridge).
   Agar group ka last item delete ho jaye to pura message delete ho jaata hai
   (jaisa normal single-media delete hota hai).

5. **Upload progress in grid** — ⚠️ **Not done in this pass.** Ye send
   pipeline (ChatMediaController upload flow) ke andar deep integration
   maangta hai jo is upgrade ke scope se bahar tha (time/effort constraint).
   Agar chaho to next round me kar sakte hain.

6. **Reply preview item-specific** — Swipe-up-to-reply ab us specific
   tapped image/video ko hi quote karta hai (uska thumb + uski caption),
   poore group ko nahi. (GalleryReplyBridge me itemIndex add kiya.)

7. **Pinch-zoom in gallery pages** — Verify kiya: PhotoView pages already
   sahi se kaam kar rahe the (click listener parent FrameLayout pe hai, na
   ki PhotoView pe, isliye PhotoView ka internal pinch/double-tap-zoom
   gesture detector block nahi hota). Koi change nahi chahiye tha yahan,
   sirf confirm kiya.

8. **Accessibility / contentDescription** — Gallery cells, +N overlay, play
   icon, gallery pages, close/share/save/more buttons — sab pe ab
   contentDescription set hai.

9. **View-once support for grouped media** — Pehle multi_media view-once
   message khulne pe khaali dialog dikhta tha (sirf single image/video
   handle hota tha). Ab ek swipeable mini-gallery dikhta hai dialog ke
   andar. Saath hi ek security bug bhi fix kiya: delete/expire/revoke pe
   pehle sirf mediaUrl/text wipe hote the, `mediaItems` array Firebase me
   reh jaata tha — ab teeno paths (hardDelete, expire, revoke) mediaItems
   bhi null karte hain.

## Files touched

- `app/.../MediaViewerActivity.java` — select mode, save, item menu, item-specific reply
- `app/.../GalleryPagerAdapter.java` — selection checkboxes, captions, contentDescription
- `app/src/main/res/layout/activity_media_viewer.xml` — Save button + selection toolbar
- `feature-chat/.../MediaGroupLayoutHelper.java` — contentDescription
- `feature-chat/.../GalleryReplyBridge.java` — item-index support
- `feature-chat/.../GalleryForwardBridge.java` — **new**
- `feature-chat/.../GalleryItemActionBridge.java` — **new**
- `feature-chat/.../controllers/ChatViewOnceController.java` — wipe mediaItems on delete/expire/revoke
- `feature-chat/.../ChatActivity.java` — bridge consumption, multi_media forward send/receive, view-once multi_media dialog case
- `feature-chat/.../group/GroupChatActivity.java` — same bridge consumption for group chat gallery (forward + item-action only; group chat doesn't have a view-once dialog flow at all, so #9 wasn't extended there)
- `app/.../ContactsActivity.java` — passes `forwardMediaItemsJson` / `forwardCaption` through

## Known gaps / assumptions

- Upload-progress-in-grid (#5) is not implemented — needs ChatMediaController send-pipeline work.
- `saveCurrentToGallery()` assumes minSdk ≥ 29 (uses MediaStore RELATIVE_PATH,
  no WRITE_EXTERNAL_STORAGE permission needed). Agar minSdk kam hai to legacy
  permission path add karna padega.
- Per-item caption sirf full-screen viewer me edit/dikhta hai, grid thumbnail
  me nahi (WhatsApp bhi grid me caption nahi dikhata, sirf bubble ke neeche).
