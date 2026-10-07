# v470 — Reel comments: reply like no longer refetches the thread

toggleReplyLike() used to call loadRepliesInto() when the likesCount transaction finished: a full network
read of reelCommentReplies/{reel}/{parent} + removeAllViews() + re-inflate of every visible reply row, on every
single like. The optimistic flip already mutates the same ReelReply objects that repliesCache/rows hold, and
failures are rolled back by revert(), so the refresh was pure cost (and made the thread flicker/re-animate).
Now: nothing is refetched. If the transaction's server count differs from the optimistic one (other users liked
meanwhile) the count/heart is re-applied in place, without animation.
File: ReelCommentFragment.java (+ version.properties 128).
