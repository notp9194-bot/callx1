# v939 — BUG: call bubble showing on text/media bubbles
Root cause: MessageBubbleCanvasView.isCallEntry was set true only in bindCallEntry() and NEVER set back to false. call_entry rows share
TYPE_CANVAS_SENT/RECEIVED with every other canvas message, so a pooled view that had shown a call pill kept isCallEntry=true after a
later text/media/poll/... bind -> onMeasure + onDraw (and the card/footer/tap guards) still took the call-entry branch.
Fix: `isCallEntry = false` added to the flag-reset block of every bind path (bind, bindMedia(+Gif/Sticker via it), bindVideo, bindAudio,
bindMediaGroup, bindReelShare, bindContact, bindViewOnce, bindSeenBubble, bindCallEntry (before it sets true), bindLocation, bindFile, bindPoll).
Test: open a chat with call log rows, fling up/down repeatedly (also reopen chat); no text/media row should ever show the call pill.
