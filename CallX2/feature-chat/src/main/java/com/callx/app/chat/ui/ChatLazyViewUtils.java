package com.callx.app.chat.ui;

import android.view.View;
import android.view.ViewStub;

/**
 * Small shared helper for chat-only ViewStub content.
 *
 * The main chat layout keeps reply, link-preview, and mention-card trees out
 * of the initial measure pass. Callers ask for a concrete child only when the
 * corresponding feature is actually used.
 */
public final class ChatLazyViewUtils {

    private ChatLazyViewUtils() { }

    public static View ensureInflated(View root, int stubId, int contentId) {
        if (root == null) return null;

        View content = root.findViewById(contentId);
        if (content != null) return content;

        View stubView = root.findViewById(stubId);
        if (stubView instanceof ViewStub && stubView.getParent() != null) {
            ((ViewStub) stubView).inflate();
        }
        return root.findViewById(contentId);
    }
}