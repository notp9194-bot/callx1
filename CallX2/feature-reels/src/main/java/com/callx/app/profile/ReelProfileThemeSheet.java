package com.callx.app.profile;

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
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.database.DatabaseReference;

/**
 * ReelProfileThemeSheet
 *
 * Reuses ChatThemeManager's 18 themes for reel profile header styling.
 * Selected theme is saved to Firebase → reels/users/{uid}/profileTheme
 * so other users see it too.
 */
public class ReelProfileThemeSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ReelProfileThemeSheet";

    public interface OnThemeAppliedListener {
        void onThemeApplied(int themeIndex);
    }

    private OnThemeAppliedListener listener;
    private int currentTheme = 0;

    // Same colors as ChatThemeBottomSheet
    private static final int[][] THEME_SENT_COLORS = {
        {0xFFFF0080, 0xFFFF6B00},
        {0xFF0EA5E9, 0xFF6366F1},
        {0xFF16A34A, 0xFF0D9488},
        {0xFFF97316, 0xFFEF4444},
        {0xFF7C3AED, 0xFFDB2777},
        {0xFF1E293B, 0xFF0F172A},
        {0xFF25D366, 0xFF128C7E},
        {0xFF374151, 0xFF6B7280},
        {0xFFE11D48, 0xFFFF6B9D},
        {0xFF0D9488, 0xFF7C3AED},
        {0xFF6F3F1F, 0xFFD97706},
        {0xFF00C853, 0xFFFF0088},
        {0xFFB8860B, 0xFF7B1C3C},
        {0xFF7B2FBE, 0xFF4361EE},
        {0xFFFF6EB4, 0xFFFF9FD8},
        {0xFFFF3A00, 0xFFFFA500},
        {0xFF48CAE4, 0xFF48CAE4},
        {0xFF2D6A4F, 0xFF40916C},
    };

    private static final int[] THEME_RECEIVED_COLORS = {
        0xFFFFD700, 0xFF38BDF8, 0xFF86EFAC, 0xFFFBBF24,
        0xFFA78BFA, 0xFF334155, 0xFFFFFFFF, 0xFFE5E7EB,
        0xFFFFB3C6, 0xFF5EEAD4, 0xFFD4A97A, 0xFF00BFA5,
        0xFFFFE066, 0xFFBB86FC, 0xFF98F5E1, 0xFFFFCB69,
        0xFFCAF0F8, 0xFF95D5B2,
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
        "Deep Space Blue to Nebula Purple",
        "Bubblegum Pink to Mint / Pastel",
        "Flame Red to Lava Orange / Ember",
        "Arctic Blue flat / Glacier calm",
        "Deep Green to Mid-Green / Mint",
    };

    public static ReelProfileThemeSheet newInstance(int currentTheme) {
        ReelProfileThemeSheet sheet = new ReelProfileThemeSheet();
        Bundle args = new Bundle();
        args.putInt("current_theme", currentTheme);
        sheet.setArguments(args);
        return sheet;
    }

    public void setOnThemeAppliedListener(OnThemeAppliedListener l) {
        this.listener = l;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);
        if (getArguments() != null) {
            currentTheme = getArguments().getInt("current_theme", 0);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(buildSheetBg());

        // Drag handle
        FrameLayout handleBar = new FrameLayout(ctx);
        handleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
        View handle = new View(ctx);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(40), dp(4));
        hp.gravity = Gravity.CENTER;
        handle.setLayoutParams(hp);
        GradientDrawable hBg = new GradientDrawable();
        hBg.setColor(0x55FFFFFF);
        hBg.setCornerRadius(dp(2));
        handle.setBackground(hBg);
        handleBar.addView(handle);
        root.addView(handleBar);

        // Header
        TextView header = new TextView(ctx);
        LinearLayout.LayoutParams hp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hp2.setMargins(dp(20), 0, dp(20), dp(4));
        header.setLayoutParams(hp2);
        header.setText("🎨  Profile Theme");
        header.setTextSize(17f);
        header.setTextColor(0xFFFFFFFF);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(header);

        // Subtitle
        TextView sub = new TextView(ctx);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(dp(20), dp(2), dp(20), dp(6));
        sub.setLayoutParams(subLp);
        sub.setText("Visible to everyone who visits your profile");
        sub.setTextSize(12f);
        sub.setTextColor(0x99FFFFFF);
        root.addView(sub);

        // Rainbow divider
        View divider = new View(ctx);
        LinearLayout.LayoutParams divP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2));
        divP.setMargins(dp(20), dp(4), dp(20), dp(10));
        divider.setLayoutParams(divP);
        divider.setBackground(buildRainbowDivider());
        root.addView(divider);

        // Scrollable theme list
        ScrollView scroll = new ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);

        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        listLp.setMargins(dp(12), 0, dp(12), dp(16));
        list.setLayoutParams(listLp);

        int total = ChatThemeManager.THEME_NAMES.length;
        for (int i = 0; i < total; i++) {
            list.addView(buildRow(ctx, i));
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
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int sheetHeight = (int) (dm.heightPixels * 0.60f);
            behavior.setPeekHeight(sheetHeight, false);
            behavior.setMaxHeight(sheetHeight);
            behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            behavior.setSkipCollapsed(false);
            behavior.setHideable(true);
            behavior.setDraggable(true);
            behavior.setFitToContents(true);
        }
    }

    private View buildRow(Context ctx, int index) {
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
        card.setBackground(buildRowBg(sentClrs[0], sentClrs[1], isSelected));
        card.setMinimumHeight(dp(66));

        // Mini gradient preview bar (like a profile header strip)
        View previewBar = new View(ctx);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(56), dp(36));
        pbLp.setMarginEnd(dp(14));
        previewBar.setLayoutParams(pbLp);
        GradientDrawable pbBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, sentClrs);
        pbBg.setCornerRadius(dp(8));
        previewBar.setBackground(pbBg);

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
        descTv.setText(index < THEME_DESC.length ? THEME_DESC[index] : "");
        descTv.setTextSize(11.5f);
        descTv.setTextColor(isSelected ? sentClrs[1] : 0x99FFFFFF);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(2);
        descTv.setLayoutParams(descLp);

        textBlock.addView(nameTv);
        textBlock.addView(descTv);

        // Check mark
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

        card.addView(previewBar);
        card.addView(textBlock);
        card.addView(check);

        card.setOnClickListener(v -> {
            // Save to Firebase so other users see it
            String myUid = FirebaseUtils.getCurrentUid();
            if (myUid != null) {
                DatabaseReference themeRef = com.google.firebase.database.FirebaseDatabase
                        .getInstance(com.callx.app.utils.Constants.DB_URL)
                        .getReference("reels/users").child(myUid).child("profileTheme");
                themeRef.setValue(index);
            }
            currentTheme = index;
            if (listener != null) listener.onThemeApplied(index);
            dismiss();
            Toast.makeText(ctx,
                    ChatThemeManager.THEME_NAMES[index] + " applied to profile!",
                    Toast.LENGTH_SHORT).show();
        });

        return card;
    }

    private GradientDrawable buildSheetBg() {
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A1A2E, 0xFF0F0F1A});
        gd.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        return gd;
    }

    private GradientDrawable buildRowBg(int clrStart, int clrEnd, boolean selected) {
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
