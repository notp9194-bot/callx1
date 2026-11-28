package com.callx.app.chat.bubblecolor;

import android.content.Intent;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.callx.app.utils.BubbleColorStore;

/**
 * Screen 1 — pick bubble colours (Default presets / Custom HSV). Built in code: no XML inflation, no
 * RecyclerView, no images. Swatches and the colour picker are single canvas views. Nothing is saved
 * here — the draft lives in {@link BubbleColorDraft} until Apply on the preview screen.
 */
public class BubbleColourActivity extends AppCompatActivity {

    private BubbleUi ui;
    private BubbleSwatchGridView sendGrid, recvGrid;
    private BubbleHsvPickerView hsv;
    private View defaultPane, customPane;
    private TextView tabDefault, tabCustom, chipSend, chipRecv;
    private boolean customSide = true;          // true = editing SEND in the custom tab
    private int themeSent, themeRecv;           // day/night resource colours (what "Default" looks like)

    private final ActivityResultLauncher<Intent> previewLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == RESULT_OK) finish();
            });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new BubbleUi(this);
        BubbleColorDraft.loadFromStore(this);
        themeSent = ContextCompat.getColor(this, com.callx.app.core.R.color.bubble_sent);
        themeRecv = ContextCompat.getColor(this, com.callx.app.core.R.color.bubble_received);

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

        root.addView(buildTopBar("Bubble Colour"));

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(16));
        scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(buildHeaderCard());
        body.addView(buildTabs());

        defaultPane = buildDefaultPane();
        customPane = buildCustomPane();
        body.addView(defaultPane);
        body.addView(customPane);
        customPane.setVisibility(View.GONE);

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView go = ui.pillButton(this, "Preview  →", true);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(54));
        gp.setMargins(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(14));
        go.setOnClickListener(v -> previewLauncher.launch(new Intent(this, BubblePreviewActivity.class)));
        root.addView(go, gp);

        setContentView(root);
        refreshDefaultSelection();
    }

    // ── pieces ──────────────────────────────────────────────────────────────

    private View buildTopBar(String title) {
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
        TextView t = ui.label(this, title, 20f, ui.text, true);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        bar.addView(t, tp);
        return bar;
    }

    private View buildHeaderCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
        card.setBackground(ui.round(ui.card, 22f, ui.stroke));
        TextView icon = ui.label(this, "🎨", 34f, ui.text, false);
        card.addView(icon);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(16), 0, 0, 0);
        col.addView(ui.label(this, "Choose your favourite\nchat bubble colour", 18f, ui.text, true));
        TextView sub = ui.label(this, "Make your chat more stylish 💜", 13.5f, ui.sub, false);
        sub.setPadding(0, ui.dp(6), 0, 0);
        col.addView(sub);
        card.addView(col);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(16);
        card.setLayoutParams(lp);
        return card;
    }

    private View buildTabs() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        tabDefault = ui.pillButton(this, "Default", true);
        tabCustom = ui.pillButton(this, "Custom", false);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ui.dp(48), 1f);
        a.rightMargin = ui.dp(8);
        LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(0, ui.dp(48), 1f);
        c.leftMargin = ui.dp(8);
        row.addView(tabDefault, a);
        row.addView(tabCustom, c);
        tabDefault.setOnClickListener(v -> showTab(false));
        tabCustom.setOnClickListener(v -> showTab(true));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = ui.dp(14);
        row.setLayoutParams(rp);
        return row;
    }

    private void showTab(boolean custom) {
        defaultPane.setVisibility(custom ? View.GONE : View.VISIBLE);
        customPane.setVisibility(custom ? View.VISIBLE : View.GONE);
        styleTab(tabDefault, !custom);
        styleTab(tabCustom, custom);
        if (custom) syncPickerToSide(); else refreshDefaultSelection();
    }

    private void styleTab(TextView t, boolean on) {
        t.setTextColor(on ? 0xFFFFFFFF : ui.sub);
        t.setBackground(ui.round(on ? BubbleUi.ACCENT : ui.card, 28f, on ? 0 : ui.stroke));
    }

    private View buildDefaultPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.addView(sectionTitle("Send Bubble Colours"));
        sendGrid = new BubbleSwatchGridView(this, BubbleUi.ACCENT);
        sendGrid.setColors(resolved(BubbleColorStore.SEND_PRESETS, themeSent));
        sendGrid.setOnPick(i -> { BubbleColorDraft.sent = BubbleColorStore.SEND_PRESETS[i]; refreshDefaultSelection(); });
        pane.addView(sendGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pane.addView(sectionTitle("Receive Bubble Colours"));
        recvGrid = new BubbleSwatchGridView(this, BubbleUi.ACCENT);
        recvGrid.setColors(resolved(BubbleColorStore.RECV_PRESETS, themeRecv));
        recvGrid.setOnPick(i -> { BubbleColorDraft.recv = BubbleColorStore.RECV_PRESETS[i]; refreshDefaultSelection(); });
        pane.addView(recvGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return pane;
    }

    private View buildCustomPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipSend = ui.pillButton(this, "Send", true);
        chipRecv = ui.pillButton(this, "Receive", false);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ui.dp(42), 1f);
        a.rightMargin = ui.dp(6);
        LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(0, ui.dp(42), 1f);
        c.leftMargin = ui.dp(6);
        chips.addView(chipSend, a);
        chips.addView(chipRecv, c);
        chipSend.setOnClickListener(v -> { customSide = true; syncPickerToSide(); });
        chipRecv.setOnClickListener(v -> { customSide = false; syncPickerToSide(); });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.bottomMargin = ui.dp(14);
        pane.addView(chips, cp);
        hsv = new BubbleHsvPickerView(this);
        hsv.setOnColor(color -> {
            if (customSide) BubbleColorDraft.sent = color; else BubbleColorDraft.recv = color;
        });
        pane.addView(hsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return pane;
    }

    private TextView sectionTitle(String s) {
        TextView t = ui.label(this, s, 16f, ui.text, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(10);
        lp.bottomMargin = ui.dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    // ── state sync ──────────────────────────────────────────────────────────

    private static int[] resolved(int[] presets, int themeColor) {
        final int[] out = new int[presets.length];
        for (int i = 0; i < out.length; i++) out[i] = presets[i] != 0 ? presets[i] : themeColor;
        return out;
    }

    private static int indexOf(int[] presets, int value) {
        for (int i = 0; i < presets.length; i++) if (presets[i] == value) return i;
        return -1;   // a custom (non-preset) colour
    }

    private void refreshDefaultSelection() {
        sendGrid.setSelected(indexOf(BubbleColorStore.SEND_PRESETS, BubbleColorDraft.sent));
        recvGrid.setSelected(indexOf(BubbleColorStore.RECV_PRESETS, BubbleColorDraft.recv));
    }

    private void syncPickerToSide() {
        chipSend.setTextColor(customSide ? 0xFFFFFFFF : ui.sub);
        chipSend.setBackground(ui.round(customSide ? BubbleUi.ACCENT : ui.card, 28f, customSide ? 0 : ui.stroke));
        chipRecv.setTextColor(!customSide ? 0xFFFFFFFF : ui.sub);
        chipRecv.setBackground(ui.round(!customSide ? BubbleUi.ACCENT : ui.card, 28f, !customSide ? 0 : ui.stroke));
        final int v = customSide ? BubbleColorDraft.sent : BubbleColorDraft.recv;
        hsv.setColor(v != 0 ? v : (customSide ? themeSent : themeRecv));
    }
}
