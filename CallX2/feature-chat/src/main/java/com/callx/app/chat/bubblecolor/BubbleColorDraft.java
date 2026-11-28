package com.callx.app.chat.bubblecolor;

import android.content.Context;

import com.callx.app.utils.BubbleColorStore;

/**
 * In-memory draft shared by the picker and preview screens (nothing is written until Apply).
 * 0 for a side = keep the theme default. Static on purpose: no Intent serialisation, no Parcel.
 */
final class BubbleColorDraft {
    static int sent, recv;

    private BubbleColorDraft() {}

    static void loadFromStore(Context c) {
        BubbleColorStore.ensureLoaded(c);
        sent = BubbleColorStore.savedSent();
        recv = BubbleColorStore.savedRecv();
    }
}
