package com.callx.app.feed;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;

/**
 * ReelPhotoTemplatePickerSheet ── Story Template Picker Bottom Sheet v6
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Bottom sheet dialog that shows all 12 story templates in a 3-column grid.
 * Tapping a template card immediately applies it to the reel model and
 * notifies the host via {@link OnTemplateSelectedListener}.
 *
 * Usage:
 *   ReelPhotoTemplatePickerSheet sheet =
 *       ReelPhotoTemplatePickerSheet.newInstance(reel, (templateId, reel) -> {
 *           // refresh adapter
 *       });
 *   sheet.show(getChildFragmentManager(), "template_picker");
 */
public class ReelPhotoTemplatePickerSheet extends BottomSheetDialogFragment {

    public interface OnTemplateSelectedListener {
        /** Called after the template has been applied to the reel model. */
        void onTemplateSelected(@NonNull String templateId, @NonNull ReelModel reel);
        /** Called when user taps "Reset" — template cleared. */
        void onTemplateCleared(@NonNull ReelModel reel);
    }

    private ReelModel                  reel;
    private OnTemplateSelectedListener listener;

    public static ReelPhotoTemplatePickerSheet newInstance(
            @NonNull ReelModel reel,
            @Nullable OnTemplateSelectedListener listener) {
        ReelPhotoTemplatePickerSheet sheet = new ReelPhotoTemplatePickerSheet();
        sheet.reel     = reel;
        sheet.listener = listener;
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_template_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (reel == null) { dismiss(); return; }

        GridLayout grid       = view.findViewById(R.id.grid_templates);
        TextView   tvActive   = view.findViewById(R.id.tv_active_template_name);
        TextView   btnClear   = view.findViewById(R.id.btn_clear_template);

        ReelPhotoStoryTemplateManager.StoryTemplate[] templates =
                ReelPhotoStoryTemplateManager.getAllTemplates();

        grid.setColumnCount(3);
        grid.removeAllViews();

        for (ReelPhotoStoryTemplateManager.StoryTemplate t : templates) {
            View card = buildCard(t, reel.slideshowTemplateName != null
                    && reel.slideshowTemplateName.equals(t.id));
            card.setOnClickListener(v -> {
                ReelPhotoStoryTemplateManager.applyTemplate(reel, t);
                // Refresh active rings
                for (int i = 0; i < grid.getChildCount(); i++) {
                    View child = grid.getChildAt(i);
                    View ring  = child.findViewById(R.id.v_active_ring);
                    if (ring != null) ring.setVisibility(View.GONE);
                    // Mark the clicked card active
                    Object tag = child.getTag();
                    if (t.id.equals(tag)) {
                        if (ring != null) ring.setVisibility(View.VISIBLE);
                    }
                }
                // Update banner
                tvActive.setText(t.emoji + "  " + t.displayName + " applied");
                tvActive.setVisibility(View.VISIBLE);
                // Notify host
                if (listener != null) listener.onTemplateSelected(t.id, reel);
            });
            card.setTag(t.id);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width         = 0;
            lp.columnSpec    = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            lp.rowSpec       = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            card.setLayoutParams(lp);
            grid.addView(card);
        }

        // Current template label
        if (reel.slideshowTemplateName != null) {
            ReelPhotoStoryTemplateManager.StoryTemplate cur =
                    ReelPhotoStoryTemplateManager.getById(reel.slideshowTemplateName);
            if (cur != null) {
                tvActive.setText(cur.emoji + "  " + cur.displayName + " is active");
                tvActive.setVisibility(View.VISIBLE);
            }
        }

        // Reset button
        btnClear.setOnClickListener(v -> {
            ReelPhotoStoryTemplateManager.clearTemplate(reel);
            tvActive.setText("Style reset to default");
            tvActive.setVisibility(View.VISIBLE);
            // Clear all rings
            for (int i = 0; i < grid.getChildCount(); i++) {
                View ring = grid.getChildAt(i).findViewById(R.id.v_active_ring);
                if (ring != null) ring.setVisibility(View.GONE);
            }
            if (listener != null) listener.onTemplateCleared(reel);
        });
    }

    // ── Card builder ──────────────────────────────────────────────────────────

    private View buildCard(ReelPhotoStoryTemplateManager.StoryTemplate t, boolean isActive) {
        FrameLayout root = new FrameLayout(requireContext());

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(android.view.Gravity.CENTER);
        body.setPadding(dp(8), dp(14), dp(8), dp(12));
        body.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Background — darkened accent
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(14));
        int accent    = t.accentColor;
        int darkened  = blendWithBlack(accent, 0.72f);
        bg.setColors(new int[]{darkened, blendWithBlack(accent, 0.55f)});
        bg.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        bg.setOrientation(GradientDrawable.Orientation.TL_BR);
        body.setBackground(bg);

        // Emoji
        TextView tvEmoji = new TextView(requireContext());
        tvEmoji.setText(t.emoji);
        tvEmoji.setTextSize(26f);
        tvEmoji.setGravity(android.view.Gravity.CENTER);
        body.addView(tvEmoji);

        // Name
        TextView tvName = new TextView(requireContext());
        tvName.setText(t.displayName);
        tvName.setTextColor(Color.WHITE);
        tvName.setTextSize(10.5f);
        tvName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvName.setGravity(android.view.Gravity.CENTER);
        tvName.setMaxLines(1);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(4);
        body.addView(tvName, nlp);

        root.addView(body);

        // Active selection ring
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.RECTANGLE);
        ring.setCornerRadius(dp(16));
        ring.setStroke(dp(2), t.accentColor);
        ring.setColor(Color.TRANSPARENT);
        View ringView = new View(requireContext());
        ringView.setId(R.id.v_active_ring);
        ringView.setBackground(ring);
        ringView.setVisibility(isActive ? View.VISIBLE : View.GONE);
        root.addView(ringView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Root margin
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(dp(5), dp(5), dp(5), dp(5));
        root.setLayoutParams(rlp);
        root.setClickable(true);
        root.setFocusable(true);

        return root;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** Blend a color towards black by `amount` (0 = original, 1 = black). */
    private static int blendWithBlack(int color, float amount) {
        float inv = 1f - amount;
        int r = (int)(Color.red(color)   * inv);
        int g = (int)(Color.green(color) * inv);
        int b = (int)(Color.blue(color)  * inv);
        return Color.rgb(r, g, b);
    }
}
