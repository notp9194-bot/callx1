package com.callx.app.profile;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.callx.app.reels.R;
import com.callx.app.models.ReelModel;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ReelGridAdapter — Production-level 3-column grid adapter.
 *
 * View types:
 *  TYPE_SKELETON (0) — shimmer placeholder (Feature 3)
 *  TYPE_REEL     (1) — normal 1-col thumbnail cell
 *  TYPE_PINNED   (2) — full-width featured hero card spanning 3 cols (Feature 6)
 *
 * Features:
 *  ✅ Feature 3:  Shimmer skeleton loading
 *  ✅ Feature 4:  Long-press callback for preview dialog
 *  ✅ Feature 5:  Multi-select mode with selection overlay
 *  ✅ Feature 6:  Pinned/featured reel hero card
 *  ✅ Feature 15: Views count overlay on own-profile reels
 */
public class ReelGridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_SKELETON = 0;
    public static final int TYPE_REEL     = 1;
    public static final int TYPE_PINNED   = 2;

    private static final int SKELETON_COUNT = 12;

    // ── Callbacks ─────────────────────────────────────────────────────────

    public interface OnItemClickListener       { void onItemClick(int position); }
    public interface LongPressListener         { void onLongPress(int position); }
    public interface MultiSelectChangeListener { void onSelectionChanged(int count); }

    // ── Fields ────────────────────────────────────────────────────────────

    private final Context                    context;
    private List<ReelModel>                  reels;
    private final OnItemClickListener        clickListener;
    private final LongPressListener          longPressListener;
    private final MultiSelectChangeListener  multiSelectListener;

    private ReelModel                   pinnedReel        = null;
    private boolean                     skeletonMode      = false;
    private boolean                     multiSelectMode   = false;
    private boolean                     showViewsOverlay  = false;   // Feature 15
    private final Map<Integer, Boolean> selectedPositions = new HashMap<>();

    /** Convenience constructor — long-press and multi-select disabled. */
      public ReelGridAdapter(Context context,
                             List<ReelModel> reels,
                             OnItemClickListener clickListener) {
          this(context, reels, clickListener, null, null);
      }

      public ReelGridAdapter(Context context,
                           List<ReelModel> reels,
                           OnItemClickListener clickListener,
                           LongPressListener longPressListener,
                           MultiSelectChangeListener multiSelectListener) {
        this.context             = context;
        this.reels               = reels;
        this.clickListener       = clickListener;
        this.longPressListener   = longPressListener;
        this.multiSelectListener = multiSelectListener;
    }

    // ── Tab switch fix: swap underlying list reference ───────────────────
    /** Switches the adapter to point at a different tab's data list (Reels/Liked/Saved). */
    public void setReelsList(List<ReelModel> newList) {
        this.reels = newList;
        notifyDataSetChanged();
    }

    // ── Feature 6: Pinned reel ────────────────────────────────────────────

    public void setPinnedReel(ReelModel reel) {
        this.pinnedReel = reel;
        notifyDataSetChanged();
    }

    public boolean hasPinned() { return pinnedReel != null && !skeletonMode; }

    private int reelIndexFor(int adapterPos) {
        return hasPinned() ? adapterPos - 1 : adapterPos;
    }

    // ── Feature 3: Skeleton ───────────────────────────────────────────────

    public void setSkeletonMode(boolean skeleton) {
        this.skeletonMode = skeleton;
    }

    // ── Feature 15: Views overlay ─────────────────────────────────────────

    /**
     * Set true when displaying own profile — shows 👁 view count badge
     * on each reel thumbnail cell.
     */
    public void setShowViewsOverlay(boolean show) {
        this.showViewsOverlay = show;
    }

    // ── Feature 5: Multi-select ───────────────────────────────────────────

    public void setMultiSelectMode(boolean enabled) {
        this.multiSelectMode = enabled;
        if (!enabled) clearSelections();
        else notifyDataSetChanged();
    }

    public void setSelected(int adapterPos, boolean selected) {
        if (selected) selectedPositions.put(adapterPos, true);
        else selectedPositions.remove(adapterPos);
    }

    public void clearSelections() {
        selectedPositions.clear();
        notifyDataSetChanged();
    }

    public int getSelectedCount() { return selectedPositions.size(); }

    // ── RecyclerView overrides ────────────────────────────────────────────

    @Override
    public int getItemViewType(int position) {
        if (skeletonMode) return TYPE_SKELETON;
        if (hasPinned() && position == 0) return TYPE_PINNED;
        return TYPE_REEL;
    }

    @Override
    public int getItemCount() {
        if (skeletonMode) return SKELETON_COUNT;
        return reels.size() + (hasPinned() ? 1 : 0);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(context);
        if (viewType == TYPE_SKELETON)
            return new SkeletonVH(inf.inflate(R.layout.item_reel_skeleton, parent, false));
        if (viewType == TYPE_PINNED)
            return new PinnedVH(inf.inflate(R.layout.item_pinned_reel, parent, false));
        return new ReelVH(inf.inflate(R.layout.item_saved_reel, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof SkeletonVH) {
            ((SkeletonVH) holder).shimmer.startShimmer();
            return;
        }
        if (holder instanceof PinnedVH) { bindPinned((PinnedVH) holder); return; }
        if (!(holder instanceof ReelVH)) return;

        ReelVH h = (ReelVH) holder;
        int reelIdx = reelIndexFor(position);
        if (reelIdx < 0 || reelIdx >= reels.size()) return;
        ReelModel reel = reels.get(reelIdx);

        // Thumbnail
        if (reel.thumbUrl != null && !reel.thumbUrl.isEmpty())
            Glide.with(context).load(reel.thumbUrl).centerCrop()
                .placeholder(R.drawable.ic_reels).into(h.ivThumb);
        else
            h.ivThumb.setImageResource(R.drawable.ic_reels);

        // Duration badge (bottom-right)
        if (reel.duration > 0) {
            int s = (reel.duration / 1000) % 60, m = reel.duration / 60000;
            h.tvDuration.setText(String.format(Locale.getDefault(), "%d:%02d", m, s));
            h.tvDuration.setVisibility(View.VISIBLE);
        } else {
            h.tvDuration.setVisibility(View.GONE);
        }

        // Feature 15: Views count overlay (top-left, own profile only)
        if (h.tvViewsOverlay != null) {
            if (showViewsOverlay && reel.viewsCount >= 0) {
                h.tvViewsOverlay.setText("👁 " + formatCount(reel.viewsCount));
                h.tvViewsOverlay.setVisibility(View.VISIBLE);
            } else {
                h.tvViewsOverlay.setVisibility(View.GONE);
            }
        }

        // Multi-select overlay
        if (multiSelectMode) {
            boolean sel = Boolean.TRUE.equals(selectedPositions.get(position));
            if (h.viewSelectOverlay != null) h.viewSelectOverlay.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
            if (h.ivCheckmark      != null) h.ivCheckmark.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
            if (h.viewDimOverlay   != null) h.viewDimOverlay.setVisibility(View.VISIBLE);
        } else {
            if (h.viewSelectOverlay != null) h.viewSelectOverlay.setVisibility(View.GONE);
            if (h.ivCheckmark      != null) h.ivCheckmark.setVisibility(View.GONE);
            if (h.viewDimOverlay   != null) h.viewDimOverlay.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onItemClick(holder.getAdapterPosition());
        });
        h.itemView.setOnLongClickListener(v -> {
            if (longPressListener != null) longPressListener.onLongPress(holder.getAdapterPosition());
            return true;
        });
    }

    private void bindPinned(PinnedVH h) {
        if (pinnedReel == null) return;
        if (pinnedReel.thumbUrl != null && !pinnedReel.thumbUrl.isEmpty())
            Glide.with(context).load(pinnedReel.thumbUrl).centerCrop()
                .placeholder(R.drawable.ic_reels).into(h.ivThumb);
        else
            h.ivThumb.setImageResource(R.drawable.ic_reels);

        if (pinnedReel.duration > 0) {
            int s = (pinnedReel.duration / 1000) % 60, m = pinnedReel.duration / 60000;
            h.tvDuration.setText(String.format(Locale.getDefault(), "%d:%02d", m, s));
        }
        if (h.tvCaption != null) {
            boolean hasCaption = pinnedReel.caption != null && !pinnedReel.caption.isEmpty();
            h.tvCaption.setText(hasCaption ? pinnedReel.caption : "");
            h.tvCaption.setVisibility(hasCaption ? View.VISIBLE : View.GONE);
        }
        if (h.tvLikes    != null) h.tvLikes.setText(formatCount(pinnedReel.likesCount));
        if (h.tvComments != null) h.tvComments.setText(formatCount(pinnedReel.commentsCount));
        if (h.tvViews    != null) h.tvViews.setText(formatCount(pinnedReel.viewsCount));

        h.itemView.setOnClickListener(v -> { if (clickListener != null) clickListener.onItemClick(0); });
        h.itemView.setOnLongClickListener(v -> {
            if (longPressListener != null) longPressListener.onLongPress(0);
            return true;
        });
    }

    private String formatCount(int n) {
        if (n >= 1_000_000) return String.format(Locale.getDefault(), "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(Locale.getDefault(), "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    @Override
    public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        if (!(holder instanceof PinnedVH)) {
            holder.itemView.post(() -> {
                int w = holder.itemView.getWidth();
                if (w > 0) {
                    ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
                    lp.height = w;
                    holder.itemView.setLayoutParams(lp);
                }
            });
        }
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewDetachedFromWindow(holder);
        if (holder instanceof SkeletonVH) ((SkeletonVH) holder).shimmer.stopShimmer();
    }

    // ── ViewHolders ───────────────────────────────────────────────────────

    static class ReelVH extends RecyclerView.ViewHolder {
        ImageView ivThumb, ivCheckmark;
        TextView  tvDuration, tvViewsOverlay;
        View      viewSelectOverlay, viewDimOverlay;

        ReelVH(@NonNull View v) {
            super(v);
            ivThumb           = v.findViewById(R.id.iv_thumb);
            tvDuration        = v.findViewById(R.id.tv_duration);
            tvViewsOverlay    = v.findViewById(R.id.tv_views_overlay);   // Feature 15
            viewSelectOverlay = v.findViewById(R.id.view_select_overlay);
            viewDimOverlay    = v.findViewById(R.id.view_dim_overlay);
            ivCheckmark       = v.findViewById(R.id.iv_checkmark);
        }
    }

    static class PinnedVH extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        TextView  tvDuration, tvCaption, tvLikes, tvComments, tvViews;
        PinnedVH(@NonNull View v) {
            super(v);
            ivThumb    = v.findViewById(R.id.iv_pinned_thumb);
            tvDuration = v.findViewById(R.id.tv_pinned_duration);
            tvCaption  = v.findViewById(R.id.tv_pinned_caption);
            tvLikes    = v.findViewById(R.id.tv_pinned_likes);
            tvComments = v.findViewById(R.id.tv_pinned_comments);
            tvViews    = v.findViewById(R.id.tv_pinned_views);
        }
    }

    static class SkeletonVH extends RecyclerView.ViewHolder {
        ShimmerFrameLayout shimmer;
        SkeletonVH(@NonNull View v) {
            super(v);
            shimmer = v.findViewById(R.id.shimmer_layout);
        }
    }
}
