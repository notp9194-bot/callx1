# v460 — Reel comments: input bar polish

1. Send button state: dim (alpha 0.4) + disabled when text is blank and no photo
   picked; full alpha + small pop (0.85 -> 1, 140ms) when it becomes available.
   Driven by updateSendState() from a TextWatcher and the picked/cleared-photo sites.
2. Hard 300-char cap: InputFilter.LengthFilter on et_comment (typing + paste),
   throttled "Max 300 characters" toast when text is cut. Existing filters kept.
4. "Replying to" bar: background #F5F5F5 -> ?attr/colorSurfaceVariant (dark mode),
   150ms height+fade open/close (setReplyBarVisible), bar 36dp -> 44dp, cancel
   button 32dp -> 40dp (padding 10dp).

Files: ReelCommentFragment.java, res/layout/activity_reel_comment.xml

## Follow-up (#3, #5)
3. Char counter shows only from 250/300 (COUNTER_SHOW_AT), red from 270
   (COUNTER_WARN_AT). Uses ContextCompat + new `comment_counter_warn` colour
   (light #E53935, dark #FF6B6B) instead of deprecated getResources().getColor /
   holo_red_light. No text/colour work per keystroke below the threshold.
5. et_comment imeOptions actionSend -> flagNoExtractUi. Multiline field: Enter =
   newline, send via the button (nothing handled the action before).

## Follow-up (#6)
- Attach icon: tint text_muted -> new `comment_input_icon` (light #64748B, dark #A8A8B3).
- Send icon: explicit android:tint="@color/comment_send_icon" (alias of brand_primary).
