package com.callx.app.chat.bubblecolor;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
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
    private boolean customTabShown;
    private EditText hexField;
    private boolean updatingHex;
    private View recentTitle;
    private BubbleSwatchGridView recentGrid;
    private int themeSent, themeRecv;           // day/night resource colours (what "Default" looks like)

    private final ActivityResultLauncher<Intent> previewLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == RESULT_OK) finish();
            });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new BubbleUi(this);
        // v941: load emoji/tick fallback fonts off-thread now, so the Preview screen's first frame has no font hitch
        com.callx.app.conversation.canvas.BubblePreviewCanvasView.prewarmFonts(this);
        // v928: a fresh open starts from the saved colours; a rotation / process restore keeps the draft.
        if (b == null) BubbleColorDraft.loadFromStore(this); else BubbleColorDraft.ensureInit(this);
        if (b != null) { customSide = b.getBoolean("side", true); customTabShown = b.getBoolean("custom", false); }
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
            androidx.core.graphics.Insets s = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
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
        // v941: Custom pane (HSV view + EditText + recents) is built lazily on the first Custom-tab show;
        // most visits only use the preset swatches, so onCreate no longer pays for the heaviest widgets.
        customPane = new LinearLayout(this);
        ((LinearLayout) customPane).setOrientation(LinearLayout.VERTICAL);
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
        // v928: a non-preset (custom) colour in the draft -> open straight on the Custom tab
        if (customTabShown || isCustomDraft()) {
            if (!customTabShown && !isCustomSentDraft()) customSide = false;
            showTab(true);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("side", customSide);
        out.putBoolean("custom", customPane.getVisibility() == View.VISIBLE);
    }

    private boolean isCustomSentDraft() {
        return BubbleColorDraft.sent != 0 && indexOf(BubbleColorStore.SEND_PRESETS, BubbleColorDraft.sent) < 0;
    }

    private boolean isCustomDraft() {
        return isCustomSentDraft()
                || (BubbleColorDraft.recv != 0 && indexOf(BubbleColorStore.RECV_PRESETS, BubbleColorDraft.recv) < 0);
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

    private boolean customBuilt;

    private void ensureCustomPane() {
        if (customBuilt) return;
        customBuilt = true;
        ((LinearLayout) customPane).addView(buildCustomPane());
    }

    private void showTab(boolean custom) {
        if (custom) ensureCustomPane();
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
        sendGrid.setDescription("Send bubble colour", BubbleColorStore.SEND_NAMES);
        sendGrid.setOnPick(i -> { BubbleColorDraft.sent = BubbleColorStore.SEND_PRESETS[i]; BubbleColorDraft.commit(); refreshDefaultSelection(); });
        pane.addView(sendGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pane.addView(sectionTitle("Receive Bubble Colours"));
        recvGrid = new BubbleSwatchGridView(this, BubbleUi.ACCENT);
        recvGrid.setColors(resolved(BubbleColorStore.RECV_PRESETS, themeRecv));
        recvGrid.setDescription("Receive bubble colour", BubbleColorStore.RECV_NAMES);
        recvGrid.setOnPick(i -> { BubbleColorDraft.recv = BubbleColorStore.RECV_PRESETS[i]; BubbleColorDraft.commit(); refreshDefaultSelection(); });
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
        hsv.setContentDescription("Colour picker. Drag to choose a colour, or type a hex code below.");
        hsv.setOnColor(color -> {
            if (customSide) BubbleColorDraft.sent = color; else BubbleColorDraft.recv = color;
            BubbleColorDraft.commit();
            setHexText(color);
        });
        pane.addView(hsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pane.addView(buildHexRow());
        recentTitle = sectionTitle("Recent colours");
        pane.addView(recentTitle);
        recentGrid = new BubbleSwatchGridView(this, BubbleUi.ACCENT, 1);
        recentGrid.setDescription("Recent colour", null);
        recentGrid.setOnPick(i -> {
            final int[] r = BubbleColorStore.recents();
            if (i < 0 || i >= r.length) return;
            if (customSide) BubbleColorDraft.sent = r[i]; else BubbleColorDraft.recv = r[i];
            BubbleColorDraft.commit();
            hsv.setColor(r[i]);
            setHexText(r[i]);
            refreshRecentSelection();
        });
        pane.addView(recentGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshRecents();
        return pane;
    }

    /** "#RRGGBB" entry: an accessible, exact alternative to dragging on the picker. */
    private View buildHexRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        hexField = new EditText(this);
        hexField.setHint("#RRGGBB");
        hexField.setHintTextColor(ui.sub);
        hexField.setTextColor(ui.text);
        hexField.setTextSize(16f);
        hexField.setSingleLine(true);
        hexField.setContentDescription("Hex colour code");
        hexField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hexField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        hexField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        hexField.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        hexField.setBackground(ui.round(ui.card, 24f, ui.stroke));
        hexField.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { applyHex(); return true; }
            return false;
        });
        TextView set = ui.pillButton(this, "Set", true);
        set.setOnClickListener(v -> applyHex());
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, ui.dp(46), 1f);
        hp.rightMargin = ui.dp(10);
        row.addView(hexField, hp);
        row.addView(set, new LinearLayout.LayoutParams(ui.dp(84), ui.dp(46)));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = ui.dp(14);
        row.setLayoutParams(rp);
        return row;
    }

    // v932: no String.format / String allocation per drag callback; setText only when the text really changed.
    private final char[] hexBuf = {'#', '0', '0', '0', '0', '0', '0'};
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private void setHexText(int color) {
        if (hexField == null || hexField.hasFocus()) return;   // never fight the user's typing
        for (int i = 0; i < 6; i++) hexBuf[6 - i] = HEX[(color >> (i * 4)) & 0xF];
        final CharSequence cur = hexField.getText();
        boolean same = cur != null && cur.length() == 7;
        for (int i = 0; same && i < 7; i++) if (cur.charAt(i) != hexBuf[i]) same = false;
        if (same) return;
        updatingHex = true;
        hexField.setText(hexBuf, 0, 7);
        updatingHex = false;
    }

    private void applyHex() {
        if (updatingHex) return;
        String t = hexField.getText().toString().trim();
        if (!t.startsWith("#")) t = "#" + t;
        if (t.length() != 7) { hexField.setError("Use 6 digits, e.g. #6C2BD9"); return; }
        final int c;
        try { c = Color.parseColor(t) | 0xFF000000; }
        catch (IllegalArgumentException e) { hexField.setError("Not a valid hex colour"); return; }
        if (customSide) BubbleColorDraft.sent = c; else BubbleColorDraft.recv = c;
        BubbleColorDraft.commit();
        hsv.setColor(c);
        hexField.setError(null);
        hexField.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(hexField.getWindowToken(), 0);
        setHexText(c);
    }

    private void refreshRecents() {
        final int[] r = BubbleColorStore.recents();
        recentTitle.setVisibility(r.length == 0 ? View.GONE : View.VISIBLE);
        recentGrid.setVisibility(r.length == 0 ? View.GONE : View.VISIBLE);
        recentGrid.setColors(r);
        refreshRecentSelection();
    }

    private void refreshRecentSelection() {
        final int[] r = BubbleColorStore.recents();
        final int cur = customSide ? BubbleColorDraft.sent : BubbleColorDraft.recv;
        int idx = -1;
        for (int i = 0; i < r.length; i++) if (r[i] == cur) { idx = i; break; }
        recentGrid.setSelected(idx);
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

    @Override
    protected void onResume() {
        super.onResume();
        // v941: preview screen may have undone / redone / reset the draft — repaint the picker from it
        if (sendGrid != null && recvGrid != null && hsv != null && chipSend != null) {
            refreshDefaultSelection();
            syncPickerToSide();
        }
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
        final int shown = v != 0 ? v : (customSide ? themeSent : themeRecv);
        hsv.setColor(shown);
        setHexText(shown);
        refreshRecentSelection();
    }
}
