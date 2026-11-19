# v797 — Status reply / reaction bubble polish
- Thumbnail slot reserved (44dp placeholder) while bitmap decodes → no bubble resize/jump; bitmap fades in (160ms).
- Label resolved at display time: "Your status" (partner replied to my status), type icon 📷/🎬/📝; reactions: "Reacted to your status" / "You reacted to X's Status".
- Expired status: one cached Firebase existence check per status (replies older than 55 min); thumbnail dimmed + "Expired" (or " · Expired" suffix when no thumbnail).
- Pressed overlay on the quote box.
- ▶ play chip on video status thumbnails.
- Text statuses: gradient card (bg colors + first lines of text) is rendered as the reply thumbnail at send time (replyToThumbBase64).
- Big reaction emoji pops in (overshoot) once for fresh messages.
- Strings: status_reply_* in feature-chat strings_chat_groups.xml. Reaction emoji badge background (#2) intentionally unchanged.
