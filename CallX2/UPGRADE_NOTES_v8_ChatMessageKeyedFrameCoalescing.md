# v8 Chat Message-Keyed Frame Coalescing

## Included

`ChatUiEventBatcher` now supports `postForMessage(messageId, task)`.

- The first update for a message keeps its queue position.
- Later updates for that same message replace the pending task.
- Different message IDs retain their arrival order.
- Existing unkeyed `post(Runnable)` callers keep the original behavior.

The 1:1 and group chat realtime/decrypt update paths now use the keyed API.
New-row callbacks intentionally remain ordinary queued posts so their
structural-refresh and first-arrival side effects are preserved. Repeated
Firebase status, reaction, edit, or receipt callbacks for one existing message
therefore produce one latest adapter update per frame instead of replaying
every intermediate state.

No Gradle build or automated test was run, per request.