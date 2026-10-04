package com.callx.app.chatlist;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

/**
 * ChatListItemAnimator — move-only item animator for the Chats tab.
 *
 * Why not simply setItemAnimator(null) (the old v85 setup)? With no animator,
 * pinning a chat or a new message bumping a row to the top makes every row
 * between the old and new position JUMP — it reads as a glitch. A full
 * DefaultItemAnimator would fix that but adds fade/cross-fade work on every
 * add/remove/change, which is exactly the overhead v85 removed.
 *
 * This is the middle path — only the thing that actually needs animating:
 *   • MOVE   → animated (short, ViewPropertyAnimator translation — runs on the
 *              RenderThread, no per-frame main-thread work, no allocations in
 *              our code).
 *   • ADD / REMOVE → instant (no alpha fade, no pending-animation bookkeeping).
 *   • CHANGE → instant. setSupportsChangeAnimations(false) makes RecyclerView
 *              reuse the same ViewHolder for payload/in-place updates (tick
 *              flips, unread count, typing, selection) so nothing cross-fades
 *              and the existing partial-bind payload path is untouched.
 *
 * Moves are suppressed until {@link #setArmed(boolean)} is called: during the
 * cold-start load the list gets re-sorted a few times (instant snapshot → Room
 * → Firebase replay) and sliding rows on screen open would look like the list
 * is shuffling. ChatsFragment arms it once that settles.
 */
public final class ChatListItemAnimator extends DefaultItemAnimator {

    private static final long MOVE_DURATION_MS = 180L;

    private boolean armed = false;

    public ChatListItemAnimator() {
        setSupportsChangeAnimations(false);
        setMoveDuration(MOVE_DURATION_MS);
        setAddDuration(0L);
        setRemoveDuration(0L);
        setChangeDuration(0L);
    }

    /** Main-thread only. While false, moves are applied instantly (no animation). */
    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    @Override
    public boolean animateAdd(RecyclerView.ViewHolder holder) {
        endAnimation(holder);
        dispatchAddFinished(holder);
        return false;
    }

    @Override
    public boolean animateRemove(RecyclerView.ViewHolder holder) {
        endAnimation(holder);
        dispatchRemoveFinished(holder);
        return false;
    }

    @Override
    public boolean animateMove(RecyclerView.ViewHolder holder,
                               int fromX, int fromY, int toX, int toY) {
        if (!armed) {
            endAnimation(holder);
            dispatchMoveFinished(holder);
            return false;
        }
        return super.animateMove(holder, fromX, fromY, toX, toY);
    }

    @Override
    public boolean animateChange(@NonNull RecyclerView.ViewHolder oldHolder,
                                 @NonNull RecyclerView.ViewHolder newHolder,
                                 int fromLeft, int fromTop, int toLeft, int toTop) {
        // Same-holder case is routed to animateMove() by DefaultItemAnimator.
        // Different-holder case shouldn't occur (change animations are off), but
        // if it ever does, finish instantly instead of cross-fading.
        if (oldHolder == newHolder) {
            return super.animateChange(oldHolder, newHolder, fromLeft, fromTop, toLeft, toTop);
        }
        endAnimation(oldHolder);
        endAnimation(newHolder);
        dispatchChangeFinished(oldHolder, true);
        dispatchChangeFinished(newHolder, false);
        return false;
    }
}
