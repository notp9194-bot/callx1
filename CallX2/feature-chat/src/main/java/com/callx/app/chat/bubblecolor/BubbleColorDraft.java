package com.callx.app.chat.bubblecolor;

import android.content.Context;
import android.os.SystemClock;

import com.callx.app.utils.BubbleColorStore;

import java.util.ArrayList;

/**
 * In-memory draft shared by the picker and preview screens (nothing is written until Apply).
 * 0 for a side = keep the theme default. Static on purpose: no Intent serialisation, no Parcel.
 *
 * v928: {@link #sInit} tells the screens whether the draft was ever loaded in THIS process, so a
 * screen restored after process death (or a rotation) never shows an all-zero "Default" draft.
 *
 * v941: undo / redo. Every draft change is recorded with {@link #commit()} as a (sent, recv)
 * snapshot. Rapid changes (HSV drag) within {@link #COALESCE_MS} collapse into one step, so one
 * drag = one undo. History starts fresh on {@link #loadFromStore}. Memory: max 50 tiny int[2].
 */
final class BubbleColorDraft {
    static int sent, recv;
    static boolean sInit;

    private static final int MAX_HISTORY = 50;
    private static final long COALESCE_MS = 600L;
    private static final ArrayList<int[]> sHist = new ArrayList<>();
    private static int sPos = -1;
    private static long sLastCommitMs;

    private BubbleColorDraft() {}

    static void loadFromStore(Context c) {
        BubbleColorStore.ensureLoaded(c);
        sent = BubbleColorStore.savedSent();
        recv = BubbleColorStore.savedRecv();
        sInit = true;
        resetHistory();
    }

    /** Load from the store only if nothing was loaded yet in this process. */
    static void ensureInit(Context c) {
        if (!sInit) loadFromStore(c);
    }

    private static void resetHistory() {
        sHist.clear();
        sHist.add(new int[]{sent, recv});
        sPos = 0;
        sLastCommitMs = 0L;
    }

    /** Record the current (sent, recv) as a new undo step. Call right AFTER changing the draft. */
    static void commit() {
        if (sPos < 0 || sHist.isEmpty()) resetHistory();
        final int[] cur = sHist.get(sPos);
        if (cur[0] == sent && cur[1] == recv) return;        // nothing actually changed
        final long now = SystemClock.uptimeMillis();
        // drop the redo tail — a new change invalidates it
        while (sHist.size() > sPos + 1) sHist.remove(sHist.size() - 1);
        if (sPos > 0 && now - sLastCommitMs < COALESCE_MS) {
            sHist.set(sPos, new int[]{sent, recv});          // same gesture: replace top step
        } else {
            sHist.add(new int[]{sent, recv});
            sPos++;
            if (sHist.size() > MAX_HISTORY) { sHist.remove(0); sPos--; }
        }
        sLastCommitMs = now;
    }

    static boolean canUndo() { return sPos > 0; }

    static boolean canRedo() { return sPos >= 0 && sPos < sHist.size() - 1; }

    static void undo() {
        if (!canUndo()) return;
        sPos--;
        restore();
    }

    static void redo() {
        if (!canRedo()) return;
        sPos++;
        restore();
    }

    private static void restore() {
        final int[] s = sHist.get(sPos);
        sent = s[0];
        recv = s[1];
        sLastCommitMs = 0L;                                   // next change is always a new step
    }
}
