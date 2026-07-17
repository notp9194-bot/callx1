package com.callx.app.library;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * ReelDraftAdapter — 2-column grid of local reel drafts.
 *
 * ✅ Local thumbnail loaded asynchronously via MediaMetadataRetriever
 * ✅ Duration badge (top-right)
 * ✅ Age label + caption overlay (bottom)
 * ✅ "DRAFT" pill badge (top-left)
 * ✅ Tap + long-press callbacks
 * ✅ Empty thumbnail placeholder (gradient with 🎬 icon)
 */
public class ReelDraftAdapter
        extends RecyclerView.Adapter<ReelDraftAdapter.DraftVH> {

    public interface DraftActionListener {
        void onDraftClick(LocalDraftsManager.LocalDraft draft);
        void onDraftLongClick(LocalDraftsManager.LocalDraft draft, int position);
    }

    private List<LocalDraftsManager.LocalDraft> drafts;
    private final DraftActionListener            listener;

    public ReelDraftAdapter(List<LocalDraftsManager.LocalDraft> drafts,
                            DraftActionListener listener) {
        this.drafts   = drafts;
        this.listener = listener;
    }

    public void setData(List<LocalDraftsManager.LocalDraft> newDrafts) {
        this.drafts = newDrafts;
        notifyDataSetChanged();
    }

    @NonNull @Override
    public DraftVH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
        // Build card programmatically — no extra layout file needed
        float density = parent.getContext().getResources().getDisplayMetrics().density;
        FrameLayout card = new FrameLayout(parent.getContext());

        // 9:16 aspect: width = (screen / 2) - margins; height = width * 16/9
        int screenW  = parent.getContext().getResources().getDisplayMetrics().widthPixels;
        int cardW    = (screenW / 2) - (int)(6 * density);
        int cardH    = (int)(cardW * 16f / 9f);

        RecyclerView.LayoutParams lp =
            new RecyclerView.LayoutParams(cardW, cardH);
        lp.setMargins((int)(3*density),(int)(3*density),(int)(3*density),(int)(3*density));
        card.setLayoutParams(lp);

        // Rounded corners via background
        android.graphics.drawable.GradientDrawable bg =
            new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF1A1A1A);
        bg.setCornerRadius(density * 10);
        card.setBackground(bg);
        card.setClipToOutline(true);

        return new DraftVH(card, density);
    }

    @Override
    public void onBindViewHolder(@NonNull DraftVH h, int pos) {
        LocalDraftsManager.LocalDraft draft = drafts.get(pos);

        // Caption
        h.tvCaption.setText(
            (draft.caption != null && !draft.caption.isEmpty())
                ? draft.caption : "No caption");

        // Age
        h.tvAge.setText(draft.ageLabel());

        // Duration badge
        String dur = draft.durationLabel();
        h.tvDuration.setVisibility(dur.isEmpty() ? View.GONE : View.VISIBLE);
        h.tvDuration.setText(dur);

        // Filter label
        if (draft.filterName != null && !draft.filterName.isEmpty()
                && !draft.filterName.equals("Normal")) {
            h.tvFilter.setText(draft.filterName);
            h.tvFilter.setVisibility(View.VISIBLE);
        } else {
            h.tvFilter.setVisibility(View.GONE);
        }

        // Load thumbnail async
        h.ivThumb.setImageBitmap(null);
        h.ivThumb.setBackgroundColor(0xFF111111);

        final String thumbPath = draft.thumbPath;
        final String videoPath = draft.videoPath;
        final ImageView iv     = h.ivThumb;

        new Thread(() -> {
            Bitmap bmp = null;
            // Try cached thumbnail first
            if (thumbPath != null && !thumbPath.isEmpty()) {
                try { bmp = android.graphics.BitmapFactory.decodeFile(thumbPath); }
                catch (Exception ignored) {}
            }
            // Fall back to extracting from video
            if (bmp == null && videoPath != null && !videoPath.isEmpty()) {
                try {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(videoPath);
                    bmp = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    mmr.release();
                } catch (Exception ignored) {}
            }
            final Bitmap finalBmp = bmp;
            iv.post(() -> {
                if (finalBmp != null) {
                    iv.setImageBitmap(finalBmp);
                    iv.setBackgroundColor(Color.BLACK);
                } else {
                    iv.setBackgroundColor(0xFF1A1A1A);
                    h.tvNoThumb.setVisibility(View.VISIBLE);
                }
            });
        }).start();

        // Listeners
        h.itemView.setOnClickListener(v -> listener.onDraftClick(draft));
        h.itemView.setOnLongClickListener(v -> {
            listener.onDraftLongClick(draft, h.getAdapterPosition());
            return true;
        });
    }

    @Override public int getItemCount() {
        return drafts == null ? 0 : drafts.size();
    }

    // ─────────────────────────────────────────────────────────────────────
    static class DraftVH extends RecyclerView.ViewHolder {
        final ImageView ivThumb;
        final TextView  tvCaption, tvAge, tvDuration, tvFilter, tvNoThumb;

        DraftVH(FrameLayout card, float density) {
            super(card);

            // Thumbnail fills card
            ivThumb = new ImageView(card.getContext());
            ivThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(ivThumb, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

            // No-thumb placeholder emoji
            tvNoThumb = new TextView(card.getContext());
            tvNoThumb.setText("🎬");
            tvNoThumb.setTextSize(32);
            tvNoThumb.setGravity(Gravity.CENTER);
            tvNoThumb.setVisibility(View.GONE);
            card.addView(tvNoThumb, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

            // Bottom gradient overlay
            android.graphics.drawable.GradientDrawable grad =
                new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xCC000000, 0x00000000});
            View gradView = new View(card.getContext());
            gradView.setBackground(grad);
            FrameLayout.LayoutParams gradLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (int)(120 * density));
            gradLp.gravity = Gravity.BOTTOM;
            card.addView(gradView, gradLp);

            // Caption + age column (bottom)
            android.widget.LinearLayout bottomBar = new android.widget.LinearLayout(card.getContext());
            bottomBar.setOrientation(android.widget.LinearLayout.VERTICAL);
            bottomBar.setPadding((int)(8*density),(int)(6*density),(int)(8*density),(int)(8*density));
            FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            card.addView(bottomBar, bottomLp);

            tvCaption = new TextView(card.getContext());
            tvCaption.setTextColor(0xFFFFFFFF);
            tvCaption.setTextSize(11);
            tvCaption.setMaxLines(2);
            tvCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvCaption.setShadowLayer(density * 2, 0, density, 0x99000000);
            bottomBar.addView(tvCaption,
                new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

            tvAge = new TextView(card.getContext());
            tvAge.setTextColor(0xAAFFFFFF);
            tvAge.setTextSize(9);
            tvAge.setShadowLayer(density * 2, 0, density, 0x99000000);
            android.widget.LinearLayout.LayoutParams ageLp =
                new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            ageLp.topMargin = (int)(2 * density);
            bottomBar.addView(tvAge, ageLp);

            // "DRAFT" pill badge (top-left)
            TextView draftBadge = new TextView(card.getContext());
            draftBadge.setText("DRAFT");
            draftBadge.setTextColor(0xFFFFFFFF);
            draftBadge.setTextSize(8);
            draftBadge.setTypeface(null, Typeface.BOLD);
            draftBadge.setPadding((int)(5*density),(int)(2*density),(int)(5*density),(int)(2*density));
            android.graphics.drawable.GradientDrawable draftBg =
                new android.graphics.drawable.GradientDrawable();
            draftBg.setColor(0xFFFF3B5C);
            draftBg.setCornerRadius(density * 4);
            draftBadge.setBackground(draftBg);
            FrameLayout.LayoutParams draftLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
            draftLp.setMargins((int)(6*density),(int)(6*density),0,0);
            card.addView(draftBadge, draftLp);

            // Duration badge (top-right)
            tvDuration = new TextView(card.getContext());
            tvDuration.setTextColor(0xFFFFFFFF);
            tvDuration.setTextSize(9);
            tvDuration.setTypeface(null, Typeface.BOLD);
            tvDuration.setPadding((int)(5*density),(int)(2*density),(int)(5*density),(int)(2*density));
            android.graphics.drawable.GradientDrawable durBg =
                new android.graphics.drawable.GradientDrawable();
            durBg.setColor(0x99000000);
            durBg.setCornerRadius(density * 4);
            tvDuration.setBackground(durBg);
            FrameLayout.LayoutParams durLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
            durLp.setMargins(0,(int)(6*density),(int)(6*density),0);
            card.addView(tvDuration, durLp);

            // Filter badge (below duration, top-right)
            tvFilter = new TextView(card.getContext());
            tvFilter.setTextColor(0xFFFFD60A);
            tvFilter.setTextSize(8);
            tvFilter.setTypeface(null, Typeface.BOLD);
            tvFilter.setPadding((int)(4*density),(int)(2*density),(int)(4*density),(int)(2*density));
            android.graphics.drawable.GradientDrawable filtBg =
                new android.graphics.drawable.GradientDrawable();
            filtBg.setColor(0x88000000);
            filtBg.setCornerRadius(density * 4);
            tvFilter.setBackground(filtBg);
            tvFilter.setVisibility(View.GONE);
            FrameLayout.LayoutParams filtLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
            filtLp.setMargins(0,(int)(28*density),(int)(6*density),0);
            card.addView(tvFilter, filtLp);
        }
    }
}
