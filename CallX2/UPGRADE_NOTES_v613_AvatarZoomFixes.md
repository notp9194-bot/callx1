# v613 — Avatar zoom dialog: fixes 1–4
Files: core/.../utils/DialogFullscreenHelper.java (shared by 7 call sites),
feature-reels/.../profile/UserReelsActivity.java. Not compiled / not run on a device.

1. Double-tap / dead tap (UserReelsActivity.openStatusOrAvatarZoom)
   - Ring decision (same 24h status query as checkActiveStory) is reused → no extra Firebase read.
   - Else 45s cache of the last answer; else one in-flight read at a time (extra taps ignored).
   - DialogFullscreenHelper: re-entry guard (WeakReference to the live dialog) protects all callers.
2. Stale close target: liveTarget() re-measures the source view when closing; falls back to the
   open-time rect if the view is detached / not laid out.
3. Double avatar: source view alpha → 0 on the dialog's first drawn frame, restored on ANY dismiss.
4. Lifecycle: ON_DESTROY observer dismisses the dialog; returns null if the host Activity is
   already finishing; observer removed on dismiss.
Behaviour notes: if the source view is recycled to another item while the dialog is open, close
docks into that view. Callers ignoring the returned Dialog are unaffected (can now be null).
