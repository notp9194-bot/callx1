package com.callx.app.profile;

  import android.content.Context;
  import android.graphics.Rect;
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

  public class ReelGridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

      public static final int TYPE_SKELETON = 0;
      public static final int TYPE_REEL     = 1;
      public static final int TYPE_PINNED   = 2;
      private static final int SKELETON_COUNT = 12;

      public interface OnItemClickListener       { void onItemClick(int position); }
      public interface LongPressListener         { void onLongPress(int position); }
      public interface MultiSelectChangeListener { void onSelectionChanged(int count); }

      private final Context                    context;
      private final List<ReelModel>            reels;
      private final OnItemClickListener        clickListener;
      private final LongPressListener          longPressListener;
      private final MultiSelectChangeListener  multiSelectListener;

      private ReelModel                   pinnedReel        = null;
      private boolean                     skeletonMode      = false;
      private boolean                     showViewsOverlay  = false;
      private boolean                     multiSelectMode   = false;
      private final Map<Integer, Boolean> selectedPositions = new HashMap<>();

      /** 1dp white separator: RecyclerView bg=#FFFFFF, item bg=#000000, gap shows through. */
      public static class WhiteGridDecoration extends RecyclerView.ItemDecoration {
          private final int gap;
          public WhiteGridDecoration(Context ctx) {
              gap = Math.round(ctx.getResources().getDisplayMetrics().density);
          }
          @Override
          public void getItemOffsets(@NonNull Rect out, @NonNull View view,
                                     @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
              out.set(gap, gap, 0, 0);
          }
      }

      public ReelGridAdapter(Context context, List<ReelModel> reels, OnItemClickListener clickListener) {
          this(context, reels, clickListener, null, null);
      }
      public ReelGridAdapter(Context context, List<ReelModel> reels,
                             OnItemClickListener clickListener,
                             LongPressListener longPressListener,
                             MultiSelectChangeListener multiSelectListener) {
          this.context = context; this.reels = reels;
          this.clickListener = clickListener;
          this.longPressListener = longPressListener;
          this.multiSelectListener = multiSelectListener;
      }

      public void setPinnedReel(ReelModel reel)    { this.pinnedReel = reel; notifyDataSetChanged(); }
      public boolean hasPinned()                    { return pinnedReel != null && !skeletonMode; }
      private int reelIndexFor(int pos)             { return hasPinned() ? pos - 1 : pos; }
      public void setSkeletonMode(boolean s)        { this.skeletonMode = s; }
      public void setShowViewsOverlay(boolean show) { this.showViewsOverlay = show; }
      public void setMultiSelectMode(boolean e)     { this.multiSelectMode = e; if (!e) clearSelections(); else notifyDataSetChanged(); }
      public void setSelected(int pos, boolean sel) { if (sel) selectedPositions.put(pos, true); else selectedPositions.remove(pos); }
      public void clearSelections()                 { selectedPositions.clear(); notifyDataSetChanged(); }
      public int  getSelectedCount()                { return selectedPositions.size(); }

      @Override public int getItemViewType(int pos) {
          if (skeletonMode) return TYPE_SKELETON;
          if (hasPinned() && pos == 0) return TYPE_PINNED;
          return TYPE_REEL;
      }
      @Override public int getItemCount() {
          if (skeletonMode) return SKELETON_COUNT;
          return reels.size() + (hasPinned() ? 1 : 0);
      }

      @NonNull @Override
      public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int type) {
          LayoutInflater inf = LayoutInflater.from(context);
          if (type == TYPE_SKELETON) return new SkeletonVH(inf.inflate(R.layout.item_reel_skeleton, p, false));
          if (type == TYPE_PINNED)   return new PinnedVH(inf.inflate(R.layout.item_pinned_reel, p, false));
          return new ReelVH(inf.inflate(R.layout.item_saved_reel, p, false));
      }

      @Override
      public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
          if (holder instanceof SkeletonVH) { ((SkeletonVH) holder).shimmer.startShimmer(); return; }
          if (holder instanceof PinnedVH)   { bindPinned((PinnedVH) holder); return; }
          if (!(holder instanceof ReelVH))  return;
          ReelVH h = (ReelVH) holder;
          int idx = reelIndexFor(position);
          if (idx < 0 || idx >= reels.size()) return;
          ReelModel r = reels.get(idx);

          if (r.thumbUrl != null && !r.thumbUrl.isEmpty())
              Glide.with(context).load(r.thumbUrl).centerCrop().placeholder(R.drawable.ic_reels).into(h.ivThumb);
          else h.ivThumb.setImageResource(R.drawable.ic_reels);

          if (h.tvCaption != null) {
              boolean has = r.caption != null && !r.caption.trim().isEmpty();
              h.tvCaption.setText(has ? r.caption.trim() : "");
              h.tvCaption.setVisibility(has ? View.VISIBLE : View.GONE);
          }
          if (h.tvViewsOverlay != null) {
              h.tvViewsOverlay.setText(formatCount(Math.max(r.viewsCount, 0)));
              h.tvViewsOverlay.setVisibility(View.VISIBLE);
          }
          if (h.tvDuration != null) {
              if (r.duration > 0) {
                  int s=(r.duration/1000)%60, m=r.duration/60000;
                  h.tvDuration.setText(String.format(Locale.getDefault(),"%d:%02d",m,s));
                  h.tvDuration.setVisibility(View.VISIBLE);
              } else h.tvDuration.setVisibility(View.GONE);
          }

          boolean sel = multiSelectMode && Boolean.TRUE.equals(selectedPositions.get(position));
          if (h.viewSelectOverlay != null) h.viewSelectOverlay.setVisibility(multiSelectMode ? (sel ? View.VISIBLE : View.INVISIBLE) : View.GONE);
          if (h.ivCheckmark      != null) h.ivCheckmark.setVisibility(multiSelectMode ? (sel ? View.VISIBLE : View.INVISIBLE) : View.GONE);
          if (h.viewDimOverlay   != null) h.viewDimOverlay.setVisibility(multiSelectMode ? View.VISIBLE : View.GONE);

          h.itemView.setOnClickListener(v -> { if (clickListener != null) clickListener.onItemClick(holder.getAdapterPosition()); });
          h.itemView.setOnLongClickListener(v -> { if (longPressListener != null) longPressListener.onLongPress(holder.getAdapterPosition()); return true; });
      }

      private void bindPinned(PinnedVH h) {
          if (pinnedReel == null) return;
          if (pinnedReel.thumbUrl != null && !pinnedReel.thumbUrl.isEmpty())
              Glide.with(context).load(pinnedReel.thumbUrl).centerCrop().placeholder(R.drawable.ic_reels).into(h.ivThumb);
          else h.ivThumb.setImageResource(R.drawable.ic_reels);
          if (pinnedReel.duration > 0) { int s=(pinnedReel.duration/1000)%60,m=pinnedReel.duration/60000; h.tvDuration.setText(String.format(Locale.getDefault(),"%d:%02d",m,s)); }
          boolean has = pinnedReel.caption != null && !pinnedReel.caption.isEmpty();
          if (h.tvCaption  != null) { h.tvCaption.setText(has?pinnedReel.caption:""); h.tvCaption.setVisibility(has?View.VISIBLE:View.GONE); }
          if (h.tvLikes    != null) h.tvLikes.setText(formatCount(pinnedReel.likesCount));
          if (h.tvComments != null) h.tvComments.setText(formatCount(pinnedReel.commentsCount));
          if (h.tvViews    != null) h.tvViews.setText(formatCount(pinnedReel.viewsCount));
          h.itemView.setOnClickListener(v -> { if (clickListener != null) clickListener.onItemClick(0); });
          h.itemView.setOnLongClickListener(v -> { if (longPressListener != null) longPressListener.onLongPress(0); return true; });
      }

      private String formatCount(int n) {
          if (n>=1_000_000) return String.format(Locale.getDefault(),"%.1fM",n/1_000_000f);
          if (n>=1_000)     return String.format(Locale.getDefault(),"%.1fK",n/1_000f);
          return String.valueOf(n);
      }

      @Override public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
          super.onViewAttachedToWindow(holder);
          if (!(holder instanceof PinnedVH)) {
              holder.itemView.post(() -> {
                  int w = holder.itemView.getWidth();
                  if (w > 0) { ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams(); lp.height = (int)(w*16f/9f); holder.itemView.setLayoutParams(lp); }
              });
          }
      }
      @Override public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
          super.onViewDetachedFromWindow(holder);
          if (holder instanceof SkeletonVH) ((SkeletonVH)holder).shimmer.stopShimmer();
      }

      static class ReelVH extends RecyclerView.ViewHolder {
          ImageView ivThumb, ivCheckmark; TextView tvDuration, tvViewsOverlay, tvCaption; View viewSelectOverlay, viewDimOverlay;
          ReelVH(@NonNull View v) { super(v); ivThumb=v.findViewById(R.id.iv_thumb); tvDuration=v.findViewById(R.id.tv_duration); tvViewsOverlay=v.findViewById(R.id.tv_views_overlay); tvCaption=v.findViewById(R.id.tv_caption); viewSelectOverlay=v.findViewById(R.id.view_select_overlay); viewDimOverlay=v.findViewById(R.id.view_dim_overlay); ivCheckmark=v.findViewById(R.id.iv_checkmark); }
      }
      static class PinnedVH extends RecyclerView.ViewHolder {
          ImageView ivThumb; TextView tvDuration, tvCaption, tvLikes, tvComments, tvViews;
          PinnedVH(@NonNull View v) { super(v); ivThumb=v.findViewById(R.id.iv_pinned_thumb); tvDuration=v.findViewById(R.id.tv_pinned_duration); tvCaption=v.findViewById(R.id.tv_pinned_caption); tvLikes=v.findViewById(R.id.tv_pinned_likes); tvComments=v.findViewById(R.id.tv_pinned_comments); tvViews=v.findViewById(R.id.tv_pinned_views); }
      }
      static class SkeletonVH extends RecyclerView.ViewHolder {
          ShimmerFrameLayout shimmer;
          SkeletonVH(@NonNull View v) { super(v); shimmer=v.findViewById(R.id.shimmer_layout); }
      }
  }
  