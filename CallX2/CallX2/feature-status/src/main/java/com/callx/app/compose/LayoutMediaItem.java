package com.callx.app.compose;

import android.net.Uri;

/**
 * One gallery thumbnail in the Status layout-picker flow (both the "Start
 * layout" selection screen and the "Choose layout" adjust screen share this
 * model so a single {@link LayoutMediaGridAdapter} works on both).
 */
final class LayoutMediaItem {
    final Uri uri;
    boolean selected;
    int     selectionOrder;

    LayoutMediaItem(Uri uri) { this.uri = uri; }
}
