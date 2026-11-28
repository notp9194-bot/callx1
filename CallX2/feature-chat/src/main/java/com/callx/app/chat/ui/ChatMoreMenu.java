package com.callx.app.chat.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v942 — the chat "⋮" (3-dot) menu, built ONCE and re-shown instantly.
 *
 * Why not android.widget.PopupMenu: every tap used to inflate chat_menu.xml (or add 12 items by hand),
 * build a MenuBuilder + MenuPopupHelper + a ListView, and MenuPopup then measures EVERY row's view just to
 * find the widest one — all on the main thread, all again on each tap, with a first-use emoji-font load on
 * top. This menu is a fixed-width {@link PopupWindow} around a plain ScrollView/LinearLayout of TextViews:
 *   • built once (idle-time, see the host activity) and reused for the life of the screen;
 *   • measured once (fixed width => no per-row width pass); re-measured only when a row's visibility changes;
 *   • show() = one showAsDropDown() call, zero allocation, zero inflation;
 *   • row/ripple/colour objects created once; theme colours come from the same sheet_* tokens as the sheets.
 *
 * Rows are plain (id, title) pairs, so the host keeps ONE action handler keyed by the same R.id values the
 * old menu used.
 */
public final class ChatMoreMenu {

    public interface Listener { void onItem(int id); }

    private static final class Row {
        final int id;
        final TextView tv;
        Row(int id, TextView tv) { this.id = id; this.tv = tv; }
    }

    private final View anchor;
    private final Listener listener;
    private final PopupWindow popup;
    private final ScrollView scroll;
    private final ArrayList<Row> rows = new ArrayList<>(16);
    private final int widthPx, maxHeightPx, yOffPx;
    private boolean dirty = true;

    public ChatMoreMenu(@NonNull Context ctx, @NonNull View anchor,
                        @NonNull int[] ids, @NonNull String[] titles, @NonNull Listener listener) {
        this.anchor = anchor;
        this.listener = listener;
        final float d = ctx.getResources().getDisplayMetrics().density;
        final int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        final int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        widthPx = Math.min(Math.round(236f * d), screenW - Math.round(24f * d));
        maxHeightPx = Math.round(screenH * 0.72f);
        yOffPx = -Math.round(4f * d);

        final int text = ContextCompat.getColor(ctx, com.callx.app.core.R.color.sheet_text_primary);
        final int bg = ContextCompat.getColor(ctx, com.callx.app.core.R.color.sheet_grad_end);

        final LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Math.round(8f * d), 0, Math.round(8f * d));   // keeps ripples off the rounded corners

        final int padH = Math.round(18f * d), padV = Math.round(13f * d);
        final ColorStateList rippleColor = ColorStateList.valueOf(0x22808080);
        for (int i = 0; i < ids.length; i++) {
            final int id = ids[i];
            final TextView tv = new TextView(ctx);
            tv.setText(titles[i]);
            tv.setTextSize(15f);
            tv.setTextColor(text);
            tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setPadding(padH, padV, padH, padV);
            tv.setMinHeight(Math.round(46f * d));
            tv.setClickable(true);
            tv.setFocusable(true);
            tv.setBackground(new RippleDrawable(rippleColor, null, new ColorDrawable(Color.WHITE)));
            tv.setOnClickListener(v -> {
                this.popup.dismiss();          // `this.` — popup is a blank final assigned below the loop
                this.listener.onItem(id);
            });
            list.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rows.add(new Row(id, tv));
        }

        scroll = new ScrollView(ctx);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final GradientDrawable bgDrawable = new GradientDrawable();
        bgDrawable.setColor(bg);
        bgDrawable.setCornerRadius(14f * d);
        bgDrawable.setStroke(Math.max(1, Math.round(d)), 0x22808080);

        popup = new PopupWindow(scroll, widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(bgDrawable);
        popup.setOutsideTouchable(true);
        popup.setElevation(10f * d);
        popup.setAnimationStyle(android.R.style.Animation_Dialog);
    }

    /** Dynamic title (e.g. Mute / Unmute). No re-measure: titles are single-line in a fixed-width row. */
    public void setTitle(int id, @NonNull CharSequence title) {
        for (int i = 0; i < rows.size(); i++) {
            final Row r = rows.get(i);
            if (r.id == id) {
                if (!TextUtils.equals(r.tv.getText(), title)) r.tv.setText(title);
                return;
            }
        }
    }

    /** Show/hide a row (admin-only items etc.). Only triggers a re-measure when something really changed. */
    public void setVisible(int id, boolean visible) {
        for (int i = 0; i < rows.size(); i++) {
            final Row r = rows.get(i);
            if (r.id == id) {
                final int want = visible ? View.VISIBLE : View.GONE;
                if (r.tv.getVisibility() != want) { r.tv.setVisibility(want); dirty = true; }
                return;
            }
        }
    }

    private void remeasure() {
        scroll.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST));
        popup.setHeight(Math.min(scroll.getMeasuredHeight(), maxHeightPx));
        dirty = false;
    }

    public boolean isShowing() { return popup.isShowing(); }

    public void show() {
        if (popup.isShowing() || !anchor.isAttachedToWindow()) return;
        if (dirty) remeasure();
        scroll.scrollTo(0, 0);
        popup.showAsDropDown(anchor, 0, yOffPx, Gravity.END);
    }

    /** Call from the host's onPause/onDestroy so a visible popup never leaks its window. */
    public void dismiss() {
        if (popup.isShowing()) popup.dismiss();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Warm-up (off main thread, once per process + once per chat)
    // ─────────────────────────────────────────────────────────────────────

    private static final AtomicBoolean WARMED = new AtomicBoolean(false);

    /** Every emoji glyph the menu + its sheets draw — measuring them loads the colour-emoji fallback font. */
    private static final String EMOJI_SAMPLE =
            "👤✏️🔍⭐🖼️🔔🔕🚫🗑📁🛡🔒📤💾🗔🖥️🐞📊ℹ⚙🔗🎨👑🤖⏳⏱🎨❌💬";

    /**
     * Pre-pays the main-thread costs of the menu and its screens on a low-priority thread:
     * emoji font, the encrypted SecurityManager singleton (Security sheet), the avatar / display-mode prefs
     * files, and this chat's privacy prefs file. Idempotent and best-effort; safe to call on every chat open.
     */
    public static void warm(@NonNull Context ctx, @Nullable String chatId) {
        final Context app = ctx.getApplicationContext();
        final float sd = app.getResources().getDisplayMetrics().scaledDensity;
        final Thread t = new Thread(() -> {
            try {
                if (WARMED.compareAndSet(false, true)) {
                    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setTextSize(15f * sd);
                    p.measureText(EMOJI_SAMPLE);
                    com.callx.app.conversation.ChatAvatarPrefs.ensureLoaded(app);
                    com.callx.app.utils.ChatDisplayModePrefs.getMode(app);
                    // EncryptedSharedPreferences build (~100-300 ms) — the Security sheet used to pay this on main.
                    com.callx.app.utils.SecurityManager.get(app);
                }
                if (chatId != null && !chatId.isEmpty()) {
                    new com.callx.app.utils.ChatPrivacyManager(app, chatId, false).getDisappearingMs();
                }
            } catch (Throwable ignored) { /* best-effort warm-up */ }
        }, "chat-menu-warm");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }
}
