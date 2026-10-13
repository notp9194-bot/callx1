package com.callx.app.conversation.canvas;

import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.NonNull;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import com.callx.app.chat.R;

import java.util.List;

/**
 * TalkBack support for the canvas poll card. The options are painted, not views, so each option row
 * is exposed as a virtual node: "Pizza, 40 percent, 4 votes, selected" with a click action that
 * votes. Only active while the bubble is bound as a poll; for every other bubble type it reports
 * no virtual views, so it is inert. Installed lazily from bindPoll().
 */
final class PollAccessibilityHelper extends ExploreByTouchHelper {

    private final MessageBubbleCanvasView host;
    private final Rect tmp = new Rect();

    PollAccessibilityHelper(MessageBubbleCanvasView host) {
        super(host);
        this.host = host;
    }

    private int optionCount() {
        return host.isPoll ? Math.min(host.pollOptions.length, host.pollOptionRects.size()) : 0;
    }

    @Override protected int getVirtualViewAt(float x, float y) {
        int n = optionCount();
        for (int i = 0; i < n; i++) {
            if (host.pollOptionRects.get(i).contains(x, y)) return i;
        }
        return INVALID_ID;
    }

    @Override protected void getVisibleVirtualViews(List<Integer> ids) {
        int n = optionCount();
        for (int i = 0; i < n; i++) ids.add(i);
    }

    @Override protected void onPopulateNodeForVirtualView(int id, @NonNull AccessibilityNodeInfoCompat node) {
        if (id < 0 || id >= optionCount()) {
            node.setContentDescription("");
            node.setBoundsInParent(new Rect(0, 0, 1, 1));
            return;
        }
        RectF r = host.pollOptionRects.get(id);
        r.roundOut(tmp);
        node.setBoundsInParent(tmp);
        node.setContentDescription(describeOption(id));
        node.setClassName(host.pollMultiChoice ? "android.widget.CheckBox" : "android.widget.RadioButton");
        node.setCheckable(true);
        node.setChecked(id < host.pollMyVote.length && host.pollMyVote[id]);
        if (!host.pollClosed) {
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK);
            node.setClickable(true);
        } else {
            node.setEnabled(false);
        }
    }

    @Override protected boolean onPerformActionForVirtualView(int id, int action, Bundle args) {
        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK && id >= 0 && id < optionCount()
                && !host.pollClosed && host.clickListener != null) {
            host.clickListener.onPollOptionClick(id);
            invalidateVirtualView(id);
            sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED);
            return true;
        }
        return false;
    }

    /** Whole-card summary read when the bubble itself is focused. */
    String summary() {
        Resources res = host.getResources();
        StringBuilder sb = new StringBuilder(res.getString(R.string.poll_a11y_poll, host.pollQuestion));
        sb.append(". ").append(host.pollSubtitleText);
        sb.append(". ").append(host.pollFooterText.replace(" ›", ""));
        return sb.toString();
    }

    String describeOption(int i) {
        Resources res = host.getResources();
        int votes = i < host.pollCounts.length ? host.pollCounts[i] : 0;
        int pct = host.pollTotal > 0 ? Math.min(100, Math.round(votes * 100f / host.pollTotal)) : 0;
        StringBuilder sb = new StringBuilder(host.pollOptions[i]);
        sb.append(", ").append(res.getQuantityString(R.plurals.poll_a11y_percent, pct, pct));
        sb.append(", ").append(res.getQuantityString(R.plurals.poll_a11y_votes, votes, votes));
        if (i < host.pollMyVote.length && host.pollMyVote[i]) sb.append(", ").append(res.getString(R.string.poll_a11y_selected));
        if (host.pollIsLeader.length > i && host.pollIsLeader[i]) sb.append(", ").append(res.getString(R.string.poll_a11y_leading));
        return sb.toString();
    }

    @Override public void onInitializeAccessibilityNodeInfo(@NonNull android.view.View v,
                                                             @NonNull AccessibilityNodeInfoCompat info) {
        super.onInitializeAccessibilityNodeInfo(v, info);
        if (host.isPoll) info.setContentDescription(summary());
    }
}
