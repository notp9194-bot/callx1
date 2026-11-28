package com.callx.app.chat.bubblecolor;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.callx.app.conversation.canvas.BubblePreviewCanvasView;
import com.callx.app.utils.BubbleColorStore;

/**
 * Screen 2 — live preview + Apply. The whole sample chat is ONE canvas view
 * ({@link BubblePreviewCanvasView}) painted with the real bubble drawable, so it is both faithful
 * and nearly free. Apply writes through {@link BubbleColorStore} (one async prefs write + one
 * version bump); open chats repaint only their visible rows on resume.
 */
public class BubblePreviewActivity extends AppCompatActivity {

    private BubbleUi ui;
    private BubblePreviewCanvasView preview;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new BubbleUi(this);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(ui.bg);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(!ui.dark);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets s = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, s.top, 0, s.bottom);
            return insets;
        });

        root.addView(buildTopBar());

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(12));
        body.addView(buildInfoCard());

        preview = new BubblePreviewCanvasView(this);
        preview.setAvatarColors(BubbleUi.ACCENT, ui.dark ? 0xFF5A5470 : 0xFFB9B1D6);
        preview.setColors(BubbleColorDraft.sent, BubbleColorDraft.recv);
        body.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(buildInputBarMock());
        scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView apply = ui.pillButton(this, "Apply", true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(54));
        ap.setMargins(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(14));
        apply.setOnClickListener(v -> {
            BubbleColorStore.apply(getApplicationContext(), BubbleColorDraft.sent, BubbleColorDraft.recv);
            Toast.makeText(this, "Bubble colour applied", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
        root.addView(apply, ap);

        setContentView(root);
    }

    private View buildTopBar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(56)));
        ImageView back = new ImageView(this);
        back.setImageResource(com.callx.app.core.R.drawable.ic_back);
        back.setColorFilter(ui.text);
        back.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        back.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        back.setBackground(ui.round(ui.card, 20f, ui.stroke));
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ui.dp(40), ui.dp(40));
        bp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        bp.leftMargin = ui.dp(16);
        bar.addView(back, bp);
        bar.addView(ui.label(this, "Preview", 20f, ui.text, true), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        // reset-to-default (theme colours) for the draft only
        TextView reset = ui.label(this, "↺", 20f, BubbleUi.ACCENT, true);
        reset.setGravity(Gravity.CENTER);
        reset.setBackground(ui.round(ui.card, 20f, ui.stroke));
        reset.setContentDescription("Reset to default");
        reset.setOnClickListener(v -> {
            BubbleColorDraft.sent = 0;
            BubbleColorDraft.recv = 0;
            preview.setColors(0, 0);
        });
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(ui.dp(40), ui.dp(40));
        rp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        rp.rightMargin = ui.dp(16);
        bar.addView(reset, rp);
        return bar;
    }

    private View buildInfoCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        card.setBackground(ui.round(ui.card, 22f, ui.stroke));
        ImageView eye = new ImageView(this);
        eye.setImageResource(com.callx.app.chat.R.drawable.ic_eye);
        eye.setColorFilter(BubbleUi.ACCENT);
        card.addView(eye, new LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(14), 0, 0, 0);
        col.addView(ui.label(this, "This is how your chat will look", 15f, ui.text, true));
        TextView sub = ui.label(this, "Both send and receive bubbles", 13f, ui.sub, false);
        sub.setPadding(0, ui.dp(3), 0, 0);
        col.addView(sub);
        card.addView(col);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(4);
        card.setLayoutParams(lp);
        return card;
    }

    /** Static, non-interactive input-bar mock at the end of the sample chat. */
    private View buildInputBarMock() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(14), ui.dp(6), ui.dp(6), ui.dp(6));
        bar.setBackground(ui.round(ui.card, 28f, ui.stroke));
        bar.addView(ui.label(this, "😊", 20f, ui.text, false));
        TextView hint = ui.label(this, "Type a message…", 15f, ui.sub, false);
        hint.setPadding(ui.dp(10), 0, 0, 0);
        bar.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(icon(com.callx.app.core.R.drawable.ic_attach, ui.sub, 22, 6));
        bar.addView(icon(com.callx.app.core.R.drawable.ic_camera, ui.sub, 22, 6));
        ImageView send = icon(com.callx.app.core.R.drawable.ic_send, 0xFFFFFFFF, 44, 11);
        send.setBackground(ui.round(BubbleUi.ACCENT, 22f, 0));
        bar.addView(send);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(4);
        bar.setLayoutParams(lp);
        return bar;
    }

    private ImageView icon(int res, int tint, int sizeDp, int padDp) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(ui.dp(padDp), ui.dp(padDp), ui.dp(padDp), ui.dp(padDp));
        v.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(sizeDp + padDp), ui.dp(sizeDp + padDp)));
        return v;
    }
}
