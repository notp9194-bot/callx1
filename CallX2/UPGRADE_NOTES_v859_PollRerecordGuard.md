# v859 - Poll re-record guards

File: feature-chat/.../canvas/MessageBubbleCanvasView.java

1. bindPoll(): same-state guard. Returns early (no re-record, no relayout, no a11y invalidate) when the view
   is already showing a poll AND question/options/counts/myVote/total/closed/multi/sent/read/delivered/time,
   text+tick colour and shared bubble drawable are all identical. Typical trigger: a voter's avatar changed
   (FLAG_POLL payload) -> poll rebind with unchanged votes.
   - Compares against PRIVATE snapshots (pollSigCounts / pollSigMyVote): the adapter passes per-holder scratch
     arrays that it mutates in place and bindPoll() keeps references to them, so comparing with the live
     arrays would always say "same".
   - Safe for recycle poll -> text -> same poll: every other bind resets isPoll=false, so the guard is not taken.
2. setPollFooterTappable(): no-op (no invalidate) when tappable flag and the footer arrow cue already match.

Deliberately untouched (analysed, gain too small / risk too high without a build):
- setPollVoterBitmap: several invalidate() in one frame already collapse into one record at draw time.
- setMediaDownloadGate(true,-1) compressing stage + refreshUploadGates: a few calls per upload; skipping would
  leave stale mediaUploadCancelable / upload extras.

Verify: Perfetto/trace `Bubble.record` count while a voter changes avatar on a visible poll -> should be 0.
