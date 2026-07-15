package com.callx.app.profile;

  import android.content.Context;
  import android.graphics.Bitmap;
  import android.graphics.Rect;
  import android.graphics.drawable.BitmapDrawable;
  import android.graphics.drawable.Drawable;
  import android.net.ConnectivityManager;
  import android.net.NetworkCapabilities;
  import android.util.LruCache;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.ImageView;
  import android.widget.TextView;
  import androidx.annotation.NonNull;
  import androidx.fragment.app.Fragment;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.bumptech.glide.RequestManager;
  import com.bumptech.glide.load.engine.DiskCacheStrategy;
  import com.facebook.shimmer.ShimmerFrameLayout;
  import com.callx.app.reels.R;
  import com.callx.app.models.ReelModel;
  import com.callx.app.utils.CloudinaryUploader;
  import com.callx.app.utils.BlurHash;
  import java.util.ArrayList;
  import java.util.HashMap;
  import java.util.List;
  import java.util.Locale;
  import java.util.Map;

  public class ReelGridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

      public static final int TYPE_SKELETON = 0;
      public static final int TYPE_REEL     = 1;
      public static final int TYPE_PINNED   = 2;
      private static final int SKELETON_COUNT = 12;

      // Grid cells are ~1/3 screen width; pinned cell spans full width (3x wider).
      // Load only what the cell can actually show — Cloudinary derives+caches
      // these small variants on the fly from the same full-res thumbUrl.
      // Advance #4 — adaptive by network type: these are the two caps; the
      // actual value used per-bind comes from resolveGridThumbSize()/
      // resolvePinnedThumbSize(), which check ConnectivityManager once and
      // cache the result until the adapter is recreated (e.g. new screen).
      private static final int GRID_THUMB_SIZE_WIFI     = 300;
      private static final int GRID_THUMB_SIZE_CELLULAR = 200;
      private static final int PINNED_THUMB_SIZE_WIFI     = 720;
      private static final int PINNED_THUMB_SIZE_CELLULAR = 480;
      // Tiny + heavily compressed variant of the same image, shown scaled-up
      // via Glide's thumbnail() request while the real thumb loads — gives
      // an Instagram-style blur-up instead of a blank/flash placeholder.
      private static final int BLUR_THUMB_SIZE    = 20;
      // How many upcoming grid cells to warm into Glide's disk cache while
      // the user is still looking at earlier cells — by the time they scroll
      // to them the thumb is already cached, so no fetch pause / jank.
      private static final int PRELOAD_AHEAD      = 6;
      // Small square decode size — enough to look like a soft blur once
      // stretched to the cell; bigger buys no visible detail for a BlurHash
      // source (it only has a handful of cosine components to begin with).
      private static final int BLURHASH_DECODE_SIZE = 24;
      // Decoding is cheap per-call but re-runs on every rebind while
      // scrolling; cache by hash string so a re-bound cell reuses the bitmap
      // instead of re-running the cosine reconstruction each time.
      private static final LruCache<String, Bitmap> blurHashCache = new LruCache<>(64);

      public interface OnItemClickListener       { void onItemClick(int position); }
      public interface LongPressListener         { void onLongPress(int position); }
      public interface MultiSelectChangeListener { void onSelectionChanged(int count); }

      private final Context                    context;
      // Advance #5 — Fragment-scoped Glide lifecycle: when constructed with a
      // Fragment, requests are tied to the Fragment's lifecycle (auto-cancel
      // on Fragment destroy, e.g. inside a ViewPager tab) instead of the
      // hosting Activity's — fewer leaked/zombie background image fetches
      // when the user fast-scrolls or navigates away. Falls back to
      // Activity-scoped Glide.with(context) when no Fragment is available.
      // Built ONCE and reused for every bind instead of calling
      // Glide.with(...) fresh per row, which was the bigger cost.
      private final RequestManager             glideRequests;
      private final List<ReelModel>            reels;        // full source list
      private List<ReelModel>                  displayList;  // filtered view
      private final OnItemClickListener        clickListener;
      private final LongPressListener          longPressListener;
      private final MultiSelectChangeListener  multiSelectListener;

      private ReelModel                   pinnedReel        = null;
      private boolean                     skeletonMode      = false;
      private boolean                     showViewsOverlay  = false;
      private boolean                     multiSelectMode   = false;
      private final Map<Integer, Boolean> selectedPositions = new HashMap<>();

      // Advance #4 — resolved once per adapter instance (network type rarely
      // flips mid-scroll; re-create the adapter/screen to re-resolve).
      private final int gridThumbSize;
      private final int pinnedThumbSize;

      /** White 1dp separator: RecyclerView bg=#FFFFFF, item bg=#000000 → gap shows as white lines. */
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
          this.context = context;
          this.glideRequests = Glide.with(context);
          this.reels = reels;
          this.displayList = reels;
          this.clickListener = clickListener;
          this.longPressListener = longPressListener;
          this.multiSelectListener = multiSelectListener;
          this.gridThumbSize   = resolveThumbSize(context, GRID_THUMB_SIZE_WIFI, GRID_THUMB_SIZE_CELLULAR);
          this.pinnedThumbSize = resolveThumbSize(context, PINNED_THUMB_SIZE_WIFI, PINNED_THUMB_SIZE_CELLULAR);
      }

      /**
       * Advance #5 — Fragment-scoped constructor. Prefer this overload when the
       * grid lives inside a Fragment (e.g. a ViewPager2 tab) so Glide requests
       * are cancelled with the Fragment's view lifecycle rather than outliving
       * it until the whole host Activity is destroyed.
       */
      public ReelGridAdapter(Fragment fragment, List<ReelModel> reels, OnItemClickListener clickListener) {
          this(fragment, reels, clickListener, null, null);
      }
      public ReelGridAdapter(Fragment fragment, List<ReelModel> reels,
                             OnItemClickListener clickListener,
                             LongPressListener longPressListener,
                             MultiSelectChangeListener multiSelectListener) {
          this.context = fragment.requireContext();
          this.glideRequests = Glide.with(fragment);
          this.reels = reels;
          this.displayList = reels;
          this.clickListener = clickListener;
          this.longPressListener = longPressListener;
          this.multiSelectListener = multiSelectListener;
          this.gridThumbSize   = resolveThumbSize(context, GRID_THUMB_SIZE_WIFI, GRID_THUMB_SIZE_CELLULAR);
          this.pinnedThumbSize = resolveThumbSize(context, PINNED_THUMB_SIZE_WIFI, PINNED_THUMB_SIZE_CELLULAR);
      }

      /** Advance #4 — WiFi/unmetered gets the larger crisp size; metered mobile data gets the smaller one. */
      private static int resolveThumbSize(Context ctx, int wifiSize, int cellularSize) {
          try {
              ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext()
                      .getSystemService(Context.CONNECTIVITY_SERVICE);
              if (cm == null) return wifiSize;
              android.net.Network net = cm.getActiveNetwork();
              if (net == null) return cellularSize;
              NetworkCapabilities nc = cm.getNetworkCapabilities(net);
              if (nc == null) return cellularSize;
              boolean unmetered = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                      || nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                      || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
              return unmetered ? wifiSize : cellularSize;
          } catch (Exception e) {
              return wifiSize; // safe default — don't break the grid over a connectivity check failure
          }
      }

      /** Called by filter chips to show a subset. Pass null to show all. */
      public void setFilteredData(List<ReelModel> filtered) {
          this.displayList = (filtered != null) ? filtered : reels;
          notifyDataSetChanged();
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
          return displayList.size() + (hasPinned() ? 1 : 0);
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
          if (idx < 0 || idx >= displayList.size()) return;
          ReelModel r = displayList.get(idx);

          if (r.thumbUrl != null && !r.thumbUrl.isEmpty()) {
              String gridUrl = CloudinaryUploader.deriveThumbUrl(r.thumbUrl, gridThumbSize, "webp");
              String blurUrl = CloudinaryUploader.deriveThumbUrl(r.thumbUrl, BLUR_THUMB_SIZE, "webp");
              Drawable blurPlaceholder = blurHashPlaceholder(r.blurHash);
              glideRequests
                      .load(gridUrl)
                      .thumbnail(glideRequests.load(blurUrl)
                              .diskCacheStrategy(DiskCacheStrategy.ALL))
                      .diskCacheStrategy(DiskCacheStrategy.ALL)
                      .centerCrop()
                      .placeholder(blurPlaceholder != null ? blurPlaceholder : context.getDrawable(R.drawable.ic_reels))
                      .into(h.ivThumb);
          } else h.ivThumb.setImageResource(R.drawable.ic_reels);

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
          if (pinnedReel.thumbUrl != null && !pinnedReel.thumbUrl.isEmpty()) {
              String pinnedUrl = CloudinaryUploader.deriveThumbUrl(pinnedReel.thumbUrl, pinnedThumbSize, "webp");
              String blurUrl   = CloudinaryUploader.deriveThumbUrl(pinnedReel.thumbUrl, BLUR_THUMB_SIZE, "webp");
              Drawable blurPlaceholder = blurHashPlaceholder(pinnedReel.blurHash);
              glideRequests
                      .load(pinnedUrl)
                      .thumbnail(glideRequests.load(blurUrl)
                              .diskCacheStrategy(DiskCacheStrategy.ALL))
                      .diskCacheStrategy(DiskCacheStrategy.ALL)
                      .centerCrop()
                      .placeholder(blurPlaceholder != null ? blurPlaceholder : context.getDrawable(R.drawable.ic_reels))
                      .into(h.ivThumb);
          } else h.ivThumb.setImageResource(R.drawable.ic_reels);
          if (pinnedReel.duration > 0) {
              int s=(pinnedReel.duration/1000)%60, m=pinnedReel.duration/60000;
              h.tvDuration.setText(String.format(Locale.getDefault(),"%d:%02d",m,s));
          }
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

      /**
       * Returns a Drawable decoded from the reel's BlurHash, or null if the
       * reel has none (older post) or the hash is malformed — callers must
       * fall back to the plain icon placeholder in that case.
       */
      private Drawable blurHashPlaceholder(String blurHash) {
          if (blurHash == null || blurHash.isEmpty()) return null;
          Bitmap cached = blurHashCache.get(blurHash);
          if (cached == null) {
              cached = BlurHash.decode(blurHash, BLURHASH_DECODE_SIZE, BLURHASH_DECODE_SIZE, 1.0f);
              if (cached == null) return null;
              blurHashCache.put(blurHash, cached);
          }
          return new BitmapDrawable(context.getResources(), cached);
      }

      @Override public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
          super.onViewAttachedToWindow(holder);
          if (!(holder instanceof PinnedVH)) {
              holder.itemView.post(() -> {
                  int w = holder.itemView.getWidth();
                  if (w > 0) {
                      ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
                      lp.height = (int)(w * 16f / 9f);
                      holder.itemView.setLayoutParams(lp);
                  }
              });
          }
          if (holder instanceof ReelVH) {
              preloadAhead(holder.getAdapterPosition());
          }
      }

      /** Warms Glide's disk cache for the next few grid cells past fromPosition. */
      private void preloadAhead(int fromPosition) {
          if (fromPosition < 0 || skeletonMode) return;
          int lastAdapterPos = displayList.size() - 1 + (hasPinned() ? 1 : 0);
          int end = Math.min(fromPosition + PRELOAD_AHEAD, lastAdapterPos);
          for (int pos = fromPosition + 1; pos <= end; pos++) {
              int idx = reelIndexFor(pos);
              if (idx < 0 || idx >= displayList.size()) continue;
              String thumb = displayList.get(idx).thumbUrl;
              if (thumb == null || thumb.isEmpty()) continue;
              String preloadUrl = CloudinaryUploader.deriveThumbUrl(thumb, gridThumbSize, "webp");
              glideRequests
                      .load(preloadUrl)
                      .diskCacheStrategy(DiskCacheStrategy.ALL)
                      .preload();
          }
      }
      @Override public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
          super.onViewDetachedFromWindow(holder);
          if (holder instanceof SkeletonVH) ((SkeletonVH)holder).shimmer.stopShimmer();
      }

      static class ReelVH extends RecyclerView.ViewHolder {
          ImageView ivThumb, ivCheckmark;
          TextView tvDuration, tvViewsOverlay, tvCaption;
          View viewSelectOverlay, viewDimOverlay;
          ReelVH(@NonNull View v) {
              super(v);
              ivThumb=v.findViewById(R.id.iv_thumb); tvDuration=v.findViewById(R.id.tv_duration);
              tvViewsOverlay=v.findViewById(R.id.tv_views_overlay); tvCaption=v.findViewById(R.id.tv_caption);
              viewSelectOverlay=v.findViewById(R.id.view_select_overlay);
              viewDimOverlay=v.findViewById(R.id.view_dim_overlay);
              ivCheckmark=v.findViewById(R.id.iv_checkmark);
          }
      }
      static class PinnedVH extends RecyclerView.ViewHolder {
          ImageView ivThumb; TextView tvDuration, tvCaption, tvLikes, tvComments, tvViews;
          PinnedVH(@NonNull View v) {
              super(v);
              ivThumb=v.findViewById(R.id.iv_pinned_thumb); tvDuration=v.findViewById(R.id.tv_pinned_duration);
              tvCaption=v.findViewById(R.id.tv_pinned_caption); tvLikes=v.findViewById(R.id.tv_pinned_likes);
              tvComments=v.findViewById(R.id.tv_pinned_comments); tvViews=v.findViewById(R.id.tv_pinned_views);
          }
      }
      static class SkeletonVH extends RecyclerView.ViewHolder {
          ShimmerFrameLayout shimmer;
          SkeletonVH(@NonNull View v) { super(v); shimmer=v.findViewById(R.id.shimmer_layout); }
      }
  }
  