package com.callx.app.comments;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;

/**
 * MentionSpanUtils — renders "@Name" tokens inside comment/reply text as
 * tappable spans that open the tagged user's profile, shared by
 * ReelCommentsAdapter (top-level comments) and ReelCommentFragment (replies)
 * so the behavior/styling is identical everywhere.
 */
final class MentionSpanUtils {

    private MentionSpanUtils() {}

    /**
     * @param tv       target TextView (its text + movement method get set)
     * @param body     raw comment/reply text
     * @param mentions uid → display name for every user tagged in this text
     */
    static void bind(TextView tv, String body, @androidx.annotation.Nullable Map<String, String> mentions) {
        if (body == null) body = "";
        if (mentions == null || mentions.isEmpty()) {
            tv.setText(body);
            return;
        }

        SpannableString spannable = new SpannableString(body);
        String lowerBody = body.toLowerCase(Locale.ROOT);

        for (Map.Entry<String, String> entry : mentions.entrySet()) {
            String uid  = entry.getKey();
            String name = entry.getValue();
            if (name == null || name.isEmpty()) continue;

            String token = "@" + name;
            String lowerToken = token.toLowerCase(Locale.ROOT);
            int start = lowerBody.indexOf(lowerToken);
            if (start < 0) continue;
            int end = start + token.length();

            spannable.setSpan(new ClickableSpan() {
                @Override public void onClick(@androidx.annotation.NonNull View widget) {
                    navigateToProfile(widget.getContext(), uid, name);
                }
                @Override public void updateDrawState(@androidx.annotation.NonNull TextPaint ds) {
                    ds.setColor(Color.parseColor("#6BCFEF"));
                    ds.setUnderlineText(false);
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        tv.setText(spannable);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setHighlightColor(Color.TRANSPARENT);
    }

    /** Single-mention convenience overload — used by reply rows, which tag
     *  at most one user (the reply they were composed in response to). */
    static void bindSingle(TextView tv, String body, @androidx.annotation.Nullable String uid,
                           @androidx.annotation.Nullable String name) {
        if (uid == null || uid.isEmpty() || name == null || name.isEmpty()) {
            tv.setText(body != null ? body : "");
            return;
        }
        java.util.HashMap<String, String> map = new java.util.HashMap<>();
        map.put(uid, name);
        bind(tv, body, map);
    }

    /** Navigates to UserProfileActivity by reflection — feature-reels has
     *  no compile-time dependency on the app module (same pattern already
     *  used by ReelCommentsAdapter.openCommentStatus for StatusViewerActivity). */
    static void navigateToProfile(Context ctx, String uid, String name) {
        if (uid == null || uid.isEmpty()) return;
        try {
            Intent i = new Intent();
            i.setClassName(ctx, "com.callx.app.activities.UserProfileActivity");
            i.putExtra("uid",  uid);
            i.putExtra("name", name != null ? name : "");
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }
}
