package com.callx.app.chat.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.ChatThemeManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * ChatThemeBottomSheet
 *
 * 55% screen height, scrollable top-to-bottom inside, drag-down to close.
 */
public class ChatThemeBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ChatThemeBottomSheet";

    public interface OnThemeSelectedListener {
        void onThemeSelected(int themeIndex);
    }

    private OnThemeSelectedListener listener;

    public static ChatThemeBottomSheet newInstance() {
        return new ChatThemeBottomSheet();
    }

    public void setOnThemeSelectedListener(OnThemeSelectedListener l) {
        this.listener = l;
    }

    // ── Per-theme sent bubble [start, end] gradient + received colour ─────
    private static final int[][] THEME_SENT_COLORS = {
        {0xFFFF0080, 0xFFFF6B00},  //  0 Hybrid (Default) — magenta → orange
        {0xFF0EA5E9, 0xFF6366F1},  //  1 Ocean            — sky → indigo
        {0xFF16A34A, 0xFF0D9488},  //  2 Forest           — green → teal
        {0xFFF97316, 0xFFEF4444},  //  3 Sunset           — orange → red
        {0xFF7C3AED, 0xFFDB2777},  //  4 Lavender         — purple → pink
        {0xFF1E293B, 0xFF0F172A},  //  5 Midnight         — dark navy
        {0xFF25D366, 0xFF128C7E},  //  6 Classic          — WhatsApp green
        {0xFF374151, 0xFF6B7280},  //  7 Monochrome       — charcoal
        {0xFFE11D48, 0xFFFF6B9D},  //  8 Cherry Blossom   — crimson → hot-pink
        {0xFF0D9488, 0xFF7C3AED},  //  9 Aurora Borealis  — teal → purple
        {0xFF6F3F1F, 0xFFD97706},  // 10 Coffee           — espresso → amber
        {0xFF00C853, 0xFFFF0088},  // 11 Neon Glow        — green → neon-pink
        {0xFFB8860B, 0xFF7B1C3C},  // 12 Royal            — dark-gold → burgundy
        {0xFF7B2FBE, 0xFF4361EE},  // 13 Galaxy           — deep-purple → electric-blue
        {0xFFFF6EB4, 0xFFFF9FD8},  // 14 Candy            — hot-pink → bubblegum
        {0xFFFF3A00, 0xFFFFA500},  // 15 Fire             — flame-red → orange
        {0xFF48CAE4, 0xFF48CAE4},  // 16 Ice              — arctic-blue (flat)
        {0xFF2D6A4F, 0xFF40916C},  // 17 Jungle           — deep-green → mid-green
    };

    private static final int[] THEME_RECEIVED_COLORS = {
        0xFFFFD700,  //  0 Hybrid    — gold
        0xFF38BDF8,  //  1 Ocean     — light sky
        0xFF86EFAC,  //  2 Forest    — mint
        0xFFFBBF24,  //  3 Sunset    — amber
        0xFFA78BFA,  //  4 Lavender  — soft violet
        0xFF334155,  //  5 Midnight  — dark slate
        0xFFFFFFFF,  //  6 Classic   — white
        0xFFE5E7EB,  //  7 Mono      — light grey
        0xFFFFB3C6,  //  8 Cherry    — blush
        0xFF5EEAD4,  //  9 Aurora    — aqua
        0xFFD4A97A,  // 10 Coffee    — latte
        0xFF00BFA5,  // 11 Neon      — teal glow
        0xFFFFE066,  // 12 Royal     — champagne
        0xFFBB86FC,  // 13 Galaxy    — lavender
        0xFF98F5E1,  // 14 Candy     — mint
        0xFFFFCB69,  // 15 Fire      — light-amber
        0xFFCAF0F8,  // 16 Ice       — glacier-white
        0xFF95D5B2,  // 17 Jungle    — light-leaf
    };

    private static final String[] THEME_DESC = {
        "Vibrant Magenta-Orange / Gold-Green",
        "Blue to Cyan / Sky shimmer",
        "Green to Teal / Fresh mint",
        "Warm Orange-Red / Peach glow",
        "Purple-Pink / Violet lilac",
        "Deep Dark Navy / Midnight slate",
        "WhatsApp classic Green / White",
        "Clean Charcoal / Light Grey",
        "Deep Rose to Hot Pink / Blush petal",
        "Teal to Purple / Aqua shimmer",
        "Warm Espresso / Caramel latte",
        "Electric Green / Neon Pink glow",
        "Deep Gold to Burgundy / Champagne",
        "Deep Space Blue to Nebula Purple / Lavender glow",
        "Bubblegum Pink to Mint / Pastel shades",
        "Flame Red to Lava Orange / Ember glow",
        "Arctic Blue flat / Glacier White calm",
        "Deep Green to Mid-Green / Pale mint leaves",
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();

        // ── Root container ─────────────────────────────────────────────
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(buildSheetBackground());

        // ── Drag handle ────────────────────────────────────────────────
        FrameLayout handleBar = new FrameLayout(ctx);
        handleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
        View handle = new View(ctx);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(40), dp(4));
        hp.gravity = Gravity.CENTER;
        handle.setLayoutParams(hp);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(0x55FFFFFF);
        handleBg.setCornerRadius(dp(2));
        handle.setBackground(handleBg);
        handleBar.addView(handle);
        root.addView(handleBar);

        // ── Header ─────────────────────────────────────────────────────
        TextView header = new TextView(ctx);
        LinearLayout.LayoutParams hp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hp2.setMargins(dp(20), 0, dp(20), dp(4));
        header.setLayoutParams(hp2);
        header.setText("🎨  Bubble Theme");
        header.setTextSize(17f);
        header.setTextColor(0xFFFFFFFF);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(header);

        // ── Rainbow divider ─────────────────────────────────────────────
        View divider = new View(ctx);
        LinearLayout.LayoutParams divP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2));
        divP.setMargins(dp(20), dp(6), dp(20), dp(10));
        divider.setLayoutParams(divP);
        divider.setBackground(buildRainbowDivider());
        root.addView(divider);

        // ── Scrollable list (fills remaining 55% height) ────────────────
        ScrollView scroll = new ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));  // fills sheet height
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);

        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        listLp.setMargins(dp(12), 0, dp(12), dp(16));
        list.setLayoutParams(listLp);

        int currentTheme = ChatThemeManager.get(ctx).getCurrentTheme();
        int total = ChatThemeManager.THEME_NAMES.length;

        for (int i = 0; i < total; i++) {
            list.addView(buildRow(ctx, i, currentTheme));
        }

        scroll.addView(list);
        root.addView(scroll);

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog d = (BottomSheetDialog) getDialog();
            BottomSheetBehavior<FrameLayout> behavior = d.getBehavior();

            // 55% screen height
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int sheetHeight = (int) (dm.heightPixels * 0.55f);

            behavior.setPeekHeight(sheetHeight, false);
            behavior.setMaxHeight(sheetHeight);
            behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            behavior.setSkipCollapsed(false);
            behavior.setHideable(true);          // drag niche se close ho
            behavior.setDraggable(true);         // drag enable
            behavior.setFitToContents(true);
        }
    }

    private View buildRow(Context ctx, int index, int currentTheme) {
        boolean isSelected = (index == currentTheme);
        int[] sentClrs = THEME_SENT_COLORS[index % THEME_SENT_COLORS.length];
        int recvClr    = THEME_RECEIVED_COLORS[index % THEME_RECEIVED_COLORS.length];

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, dp(4), 0, dp(4));
        card.setLayoutParams(cp);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setClickable(true);
        card.setFocusable(true);
        card.setBackground(buildRowBackground(sentClrs[0], sentClrs[1], isSelected));
        card.setMinimumHeight(dp(66));

        // Mini bubble preview
        LinearLayout previewBox = new LinearLayout(ctx);
        previewBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT);
        pbLp.setMarginEnd(dp(12));
        previewBox.setLayoutParams(pbLp);
        previewBox.setGravity(Gravity.END);

        TextView sentPill = new TextView(ctx);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(dp(48), dp(18));
        spLp.bottomMargin = dp(4);
        sentPill.setLayoutParams(spLp);
        GradientDrawable sentBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, sentClrs);
        sentBg.setCornerRadius(dp(9));
        sentPill.setBackground(sentBg);

        TextView recvPill = new TextView(ctx);
        LinearLayout.LayoutParams rpLp = new LinearLayout.LayoutParams(dp(40), dp(18));
        recvPill.setLayoutParams(rpLp);
        GradientDrawable recvBg = new GradientDrawable();
        recvBg.setColor(recvClr);
        recvBg.setCornerRadius(dp(9));
        recvPill.setBackground(recvBg);

        previewBox.addView(sentPill);
        previewBox.addView(recvPill);

        // Text block
        LinearLayout textBlock = new LinearLayout(ctx);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameTv = new TextView(ctx);
        nameTv.setText(ChatThemeManager.THEME_NAMES[index]);
        nameTv.setTextSize(14.5f);
        nameTv.setTextColor(isSelected ? sentClrs[0] : 0xFFFFFFFF);
        nameTv.setTypeface(Typeface.create("sans-serif-medium",
                isSelected ? Typeface.BOLD : Typeface.NORMAL));

        TextView descTv = new TextView(ctx);
        descTv.setText(THEME_DESC[index < THEME_DESC.length ? index : 0]);
        descTv.setTextSize(11.5f);
        descTv.setTextColor(isSelected ? sentClrs[1] : 0x99FFFFFF);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(2);
        descTv.setLayoutParams(descLp);

        textBlock.addView(nameTv);
        textBlock.addView(descTv);

        // Check indicator
        TextView check = new TextView(ctx);
        check.setText("✓");
        check.setTextSize(18f);
        check.setTextColor(sentClrs[0]);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        check.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams chkLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        chkLp.setMarginStart(dp(8));
        check.setLayoutParams(chkLp);

        card.addView(previewBox);
        card.addView(textBlock);
        card.addView(check);

        card.setOnClickListener(v -> {
            ChatThemeManager.get(ctx).setTheme(index);
            if (listener != null) listener.onThemeSelected(index);
            dismiss();
            Toast.makeText(ctx,
                    ChatThemeManager.THEME_NAMES[index] + " applied!",
                    Toast.LENGTH_SHORT).show();
        });

        return card;
    }

    private GradientDrawable buildSheetBackground() {
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A1A2E, 0xFF0F0F1A});
        gd.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        return gd;
    }

    private GradientDrawable buildRowBackground(int clrStart, int clrEnd, boolean selected) {
        GradientDrawable gd;
        if (selected) {
            int s = (clrStart & 0x00FFFFFF) | 0x30000000;
            int e = (clrEnd   & 0x00FFFFFF) | 0x15000000;
            gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{s, e, 0x05FFFFFF});
            gd.setStroke(dp(1), (clrStart & 0x00FFFFFF) | 0x66000000);
        } else {
            gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0x14FFFFFF, 0x08FFFFFF});
        }
        gd.setCornerRadius(dp(14));
        return gd;
    }

    private GradientDrawable buildRainbowDivider() {
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFEC4899, 0xFFF97316, 0xFFEAB308,
                           0xFF22C55E, 0xFF06B6D4, 0xFF6366F1, 0xFFA855F7});
        gd.setCornerRadius(dp(1));
        return gd;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
