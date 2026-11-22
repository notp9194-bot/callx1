package com.callx.app.chat.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.chat.R;

/**
 * v814: reply-preview container that merges with the input capsule (WhatsApp look).
 * While VISIBLE it sits flush on top of {@code cv_input_capsule}: its own top corners are
 * rounded (bg_chat_reply_container) and the capsule switches to a bottom-rounded background
 * with no top margin, so both read as ONE rounded container. When hidden the capsule is
 * restored. Doing it from onVisibilityChanged means no call site (1:1, group, reply controller)
 * needs to know about it.
 */
public class ReplyBarLayout extends FrameLayout {

    private int capsuleTopMargin = -1;

    public ReplyBarLayout(@NonNull Context c) { super(c); }
    public ReplyBarLayout(@NonNull Context c, @Nullable AttributeSet a) { super(c, a); }
    public ReplyBarLayout(@NonNull Context c, @Nullable AttributeSet a, int s) { super(c, a, s); }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this) mergeCapsule(visibility == VISIBLE);
    }

    private void mergeCapsule(boolean merged) {
        if (!(getParent() instanceof View)) return;
        View capsule = ((View) getParent()).findViewById(R.id.cv_input_capsule);
        if (capsule == null) return;
        ViewGroup.LayoutParams lp = capsule.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        if (capsuleTopMargin < 0) capsuleTopMargin = mlp.topMargin; // first call = original 6dp
        capsule.setBackgroundResource(merged
                ? R.drawable.bg_chat_input_capsule_reply
                : R.drawable.bg_chat_input_capsule);
        int want = merged ? 0 : capsuleTopMargin;
        if (mlp.topMargin != want) {
            mlp.topMargin = want;
            capsule.setLayoutParams(mlp);
        }
    }
}
