package com.callx.app.chat.bubblecolor;

import android.content.Context;

import com.callx.app.utils.BubbleColorStore;

/**
 * In-memory draft shared by the picker and preview screens (nothing is written until Apply).
 * 0 for a side = keep the theme default. Static on purpose: no Intent serialisation, no Parcel.
 *
 * v928: {@link #sInit} tells the screens whether the draft was ever loaded in THIS process, so a
 * screen restored after process death (or a rotation) never shows an all-zero "Default" draft.
 */
final class BubbleColorDraft {
    static int sent, recv;
    static boolean sInit;

    private BubbleColorDraft() {}

    static void loadFromStore(Context c) {
        BubbleColorStore.ensureLoaded(c);
        sent = BubbleColorStore.savedSent();
        recv = BubbleColorStore.savedRecv();
        sInit = true;
    }

    /** Load from the store only if nothing was loaded yet in this process. */
    static void ensureInit(Context c) {
        if (!sInit) loadFromStore(c);
    }
}
