package com.callx.app.profile;

  import android.animation.Animator;
  import android.animation.AnimatorSet;
  import android.animation.ObjectAnimator;
  import android.content.Context;
  import android.graphics.Bitmap;
  import android.graphics.Rect;
  import android.graphics.drawable.BitmapDrawable;
  import android.graphics.drawable.Drawable;
  import android.net.ConnectivityManager;
  import android.net.NetworkCapabilities;
  import android.os.Handler;
  import android.util.LruCache;
  import android.view.HapticFeedbackConstants;
  import android.view.LayoutInflater;
  import android.view.MotionEvent;
  import android.view.View;
  import android.view.ViewConfiguration;
  import android.view.ViewGroup;
  import android.widget.ImageView;
  import android.widget.TextView;
  import androidx.annotation.NonNull;
  import androidx.fragment.app.Fragment;
  import androidx.recyclerview.widget.GridLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.bumptech.glide.RequestManager;
  import com.bumptech.glide.load.DecodeFormat;
  import com.bumptech.glide.load.engine.DiskCacheStrategy;
  import com.bumptech.glide.request.RequestOptions;
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

      // ── ULTRA: shared Glide RequestOptions ─────────────────────────────
      // Built once and reused for every grid/pinned bind instead of chaining
      // .centerCrop()/.diskCacheStrategy() fresh each time. Two concrete wins
      // beyond avoiding repeated builder calls:
      //   1) PREFER_RGB_565 — these thumbs are opaque rectangles (centerCrop,
      //      no transparency), so the 16-bit-per-pixel RGB_565 decode format
      //      is visually identical here but HALVES the bitmap's in-memory
      //      footprint vs. the default ARGB_8888. Less bitmap memory means
      //      fewer/lighter GCs while fast-scrolling a media-heavy grid.
      //   2) dontAnimate() — skips Glide's default crossfade TransitionDrawable
      //      per image load, cutting a per-bind allocation + a few frames of
      //      extra compositing that add up across a 3-column grid.
      private static final RequestOptions GRID_OPTIONS = new RequestOptions()
              .diskCacheStrategy(DiskCacheStrategy.ALL)
              .format(DecodeFormat.PREFER_RGB_565)
              .centerCrop()
              .dontAnimate();

      public interface OnItemClickListener       { void onItemClick(int position); }
      // NOTE: onLongPress() now fires the instant a press-and-hold crosses the
      // long-press timeout — i.e. it's the "peek" START signal (Instagram/iOS
      // style mini preview popup), not an end-of-gesture callback anymore.
      public interface LongPressListener         { void onLongPress(int position); }
      // Peek END signal — fired on ACTION_UP/ACTION_CANCEL *only* if a peek
      // was actually showing (finger released after crossing the long-press
      // timeout). A plain quick tap never calls this; it calls the normal
      // OnItemClickListener instead via View#performClick().
      public interface LongPressReleaseListener  { void onLongPressRelease(int position); }
      public interface MultiSelectChangeListener { void onSelectionChanged(int count); }
      // Instagram-style quick-like: fired when a cell is double-tapped
      // (second tap lands within ViewConfiguration.getDoubleTapTimeout() of
      // the first, at roughly the same spot). The adapter only reports the
      // gesture + plays the heart burst; the actual like write/toggle is
      // owned by the host Activity (see UserReelsActivity#likeReelFromGrid).
      public interface OnDoubleTapLikeListener    { void onDoubleTapLike(int position); }

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
      private List<ReelModel>                  reels;        // current tab's source list
      private List<ReelModel>                  displayList;  // filtered view
      private final OnItemClickListener        clickListener;
      private final LongPressListener          longPressListener;
      private final MultiSelectChangeListener  multiSelectListener;
      private LongPressReleaseListener         longPressReleaseListener; // optional, see setter
      private OnDoubleTapLikeListener          doubleTapLikeListener;   // optional, see setter

      // Single Handler shared by every cell's peek-timeout Runnable so
      // onViewRecycled() can reliably cancel a pending one by reference.
      private final Handler peekHandler = new Handler(android.os.Looper.getMainLooper());
      private final int     touchSlopPx;

      private ReelModel                   pinnedReel        = null;
      private boolean                     skeletonMode      = false;
      private boolean                     showViewsOverlay  = false;
      private boolean                     multiSelectMode   = false;
      private final Map<Integer, Boolean> selectedPositions = new HashMap<>();

      // Advance #4 — resolved once per adapter instance (network type rarely
      // flips mid-scroll; re-create the adapter/screen to re-resolve).
      private final int gridThumbSize;
      private final int pinnedThumbSize;

      /**
       * Symmetric grid spacing (Instagram-style): equal gap between every
       * cell AND on the outer left/right edges — replaces the old
       * top+left-only 1dp offset, which left no gap on the right/bottom of
       * each cell and gave the last row no bottom margin at all.
       *
       * Uses GridLayoutManager's own SpanSizeLookup so it stays correct
       * even for the full-width pinned hero cell (spanSize == spanCount),
       * which gets edge-to-edge sides and just a bottom gap like a normal
       * row separator.
       */
      public static class WhiteGridDecoration extends RecyclerView.ItemDecoration {
          private final int spacing;
          public WhiteGridDecoration(Context ctx) {
              spacing = Math.round(2 * ctx.getResources().getDisplayMetrics().density);
          }
          @Override
          public void getItemOffsets(@NonNull Rect out, @NonNull View view,
                                     @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
              RecyclerView.LayoutManager lm = parent.getLayoutManager();
              int position = parent.getChildAdapterPosition(view);
              if (position == RecyclerView.NO_POSITION || !(lm instanceof GridLayoutManager)) {
                  out.set(spacing, spacing, spacing, spacing);
                  return;
              }
              GridLayoutManager glm = (GridLayoutManager) lm;
              int spanCount = glm.getSpanCount();
              GridLayoutManager.SpanSizeLookup lookup = glm.getSpanSizeLookup();
              int spanSize = lookup.getSpanSize(position);

              if (spanSize >= spanCount) {
                  // Full-width cell (pinned hero card): flush sides, same
                  // vertical rhythm as every other row.
                  out.set(0, 0, 0, spacing);
                  return;
              }

              int spanIndex = lookup.getSpanIndex(position, spanCount);
              out.left   = spacing - (spanIndex * spacing / spanCount);
              out.right  = ((spanIndex + 1) * spacing / spanCount);
              out.top    = 0;
              out.bottom = spacing; // every row — including the last — gets a bottom gap
          }
      }

      /**
       * Unified touch handler for a grid/pinned cell:
       *   - Instagram-style press feedback (subtle scale-down while held).
       *   - Quick tap  → View#performClick() (drives the normal OnClickListener).
       *   - Press-and-hold past the system long-press timeout → treated as a
       *     "peek" gesture (Instagram/iOS-style mini video preview popup):
       *     fires LongPressListener#onLongPress() the moment the hold is
       *     recognized, and LongPressReleaseListener#onLongPressRelease()
       *     when the finger lifts — the popup itself is entirely owned by
       *     the host Activity, this adapter only reports the two edges.
       *   - Finger drags past touch-slop before the timeout (i.e. the user
       *     is actually scrolling the grid) cancels the pending peek so it
       *     never fires mid-fling.
       *
       * Replaces the old pairing of a plain OnTouchListener (press-scale
       * only) + OnLongClickListener (fired a single discrete "long click"
       * event with no way to know when the finger was released).
       */
      /** Bookkeeping for one cell's in-flight peek gesture — stashed on the
       *  itemView's tag so onViewRecycled() can reach it if the cell gets
       *  recycled mid-touch (e.g. a data reload lands while the user is
       *  still holding a cell), and cleanly cancel/close it instead of
       *  leaving a stuck popup or a runnable firing against a reused view. */
      private static class PeekState {
          Runnable runnable;
          boolean  peeking;
          int      position = RecyclerView.NO_POSITION;
          // Double-tap-to-like bookkeeping: a single tap's click is HELD
          // (not fired) for one double-tap window in case a second tap
          // follows — see ACTION_UP below. Null == no tap currently pending.
          Runnable pendingClickRunnable;
      }

      private void wireItemInteractions(RecyclerView.ViewHolder holder, ImageView heartOverlay) {
          final View itemView = holder.itemView;
          final PeekState state = new PeekState();
          itemView.setTag(R.id.tag_peek_runnable, state);
          final int doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();
          itemView.setOnTouchListener(new View.OnTouchListener() {
              private float downX, downY;

              @Override public boolean onTouch(View v, MotionEvent event) {
                  switch (event.getActionMasked()) {
                      case MotionEvent.ACTION_DOWN: {
                          downX = event.getRawX();
                          downY = event.getRawY();
                          state.peeking = false;
                          v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100).start();
                          state.runnable = () -> {
                              int pos = holder.getAdapterPosition();
                              if (pos == RecyclerView.NO_POSITION) return;
                              state.peeking  = true;
                              state.position = pos;
                              v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                              if (longPressListener != null) longPressListener.onLongPress(pos);
                          };
                          peekHandler.postDelayed(state.runnable, ViewConfiguration.getLongPressTimeout());
                          return true;
                      }
                      case MotionEvent.ACTION_MOVE: {
                          if (!state.peeking && state.runnable != null) {
                              float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                              if (Math.hypot(dx, dy) > touchSlopPx) {
                                  peekHandler.removeCallbacks(state.runnable);
                              }
                          }
                          return true;
                      }
                      case MotionEvent.ACTION_UP: {
                          if (state.runnable != null) peekHandler.removeCallbacks(state.runnable);
                          v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                          if (state.peeking) {
                              state.peeking = false;
                              if (longPressReleaseListener != null) {
                                  longPressReleaseListener.onLongPressRelease(state.position);
                              }
                              return true;
                          }
                          int pos = holder.getAdapterPosition();
                          if (state.pendingClickRunnable != null) {
                              // Second tap landed inside the double-tap window
                              // of the first → quick-like gesture, NOT a click.
                              peekHandler.removeCallbacks(state.pendingClickRunnable);
                              state.pendingClickRunnable = null;
                              if (pos != RecyclerView.NO_POSITION) {
                                  v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                                  playDoubleTapHeart(heartOverlay);
                                  if (doubleTapLikeListener != null) doubleTapLikeListener.onDoubleTapLike(pos);
                              }
                          } else {
                              // First tap — hold the click for one double-tap
                              // window in case a second tap follows.
                              state.pendingClickRunnable = v::performClick;
                              peekHandler.postDelayed(state.pendingClickRunnable, doubleTapTimeout);
                          }
                          return true;
                      }
                      case MotionEvent.ACTION_CANCEL: {
                          if (state.runnable != null) peekHandler.removeCallbacks(state.runnable);
                          v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                          if (state.peeking) {
                              state.peeking = false;
                              if (longPressReleaseListener != null) {
                                  longPressReleaseListener.onLongPressRelease(state.position);
                              }
                          }
                          return true;
                      }
                  }
                  return false;
              }
          });
      }

      /**
       * Instagram-style heart burst on quick-like: pop in past 1.0 scale then
       * settle and fade — mirrors ReelSocialController#showLikeAnimation()
       * used by the full-screen player, so the gesture feels identical
       * whether liked from the grid or from playback.
       */
      private void playDoubleTapHeart(ImageView heartOverlay) {
          if (heartOverlay == null) return;
          heartOverlay.animate().cancel();
          heartOverlay.setVisibility(View.VISIBLE);
          heartOverlay.setAlpha(1f);
          heartOverlay.setScaleX(0.3f);
          heartOverlay.setScaleY(0.3f);

          AnimatorSet set = new AnimatorSet();
          ObjectAnimator scaleX = ObjectAnimator.ofFloat(heartOverlay, "scaleX", 0.3f, 1.15f, 1.0f);
          ObjectAnimator scaleY = ObjectAnimator.ofFloat(heartOverlay, "scaleY", 0.3f, 1.15f, 1.0f);
          ObjectAnimator alpha  = ObjectAnimator.ofFloat(heartOverlay, "alpha", 1f, 1f, 0f);
          alpha.setStartDelay(350);
          set.playTogether(scaleX, scaleY, alpha);
          set.setDuration(600);
          set.addListener(new android.animation.AnimatorListenerAdapter() {
              @Override public void onAnimationEnd(Animator animation) {
                  heartOverlay.setVisibility(View.GONE);
              }
          });
          set.start();
      }

      /** Optional — enables the grid's double-tap-to-like quick gesture. */
      public void setOnDoubleTapLikeListener(OnDoubleTapLikeListener l) { this.doubleTapLikeListener = l; }

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
          this.touchSlopPx     = ViewConfiguration.get(context).getScaledTouchSlop();
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
          this.touchSlopPx     = ViewConfiguration.get(context).getScaledTouchSlop();
      }

      /** Optional — enables the long-press "peek" mini preview popup's dismiss callback. */
      public void setLongPressReleaseListener(LongPressReleaseListener l) { this.longPressReleaseListener = l; }

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

      /**
       * BUG FIX — Liked/Saved/Repost/Series tabs were showing the wrong
       * reels: this adapter is a single shared instance across all grid
       * tabs (Reels/Liked/Saved/Repost), but it was only ever constructed
       * ONCE with whichever list was active at that moment (the Reels tab's
       * list, since that's the default tab). Switching tabs only called
       * notifyDataSetChanged() — it never told the adapter to look at a
       * DIFFERENT list — so every tab kept rendering the Reels tab's data.
       *
       * Call this whenever the active tab changes (before notifying) so the
       * adapter always points at THAT tab's own backing list
       * (reelsTabData / likedTabData / savedTabData / repostsTabData).
       * Also resets any active filter, since a filter chip selection from
       * one tab must never carry over and hide items on another tab.
       */
      public void setDataList(List<ReelModel> newReels) {
          this.reels = newReels;
          this.displayList = newReels;
          notifyDataSetChanged();
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
      public boolean isSkeletonMode()                { return this.skeletonMode; }
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
                      .thumbnail(glideRequests.load(blurUrl).apply(GRID_OPTIONS))
                      .apply(GRID_OPTIONS)
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
          // Carousel indicator — Instagram-style stack icon for reels backed
          // by more than one photo/clip (r.photoUrls has 2+ entries).
          if (h.ivStackIndicator != null) {
              boolean isCarousel = r.photoUrls != null && r.photoUrls.size() > 1;
              h.ivStackIndicator.setVisibility(isCarousel ? View.VISIBLE : View.GONE);
          }
          // Shared-element transition name — lets UserReelsActivity open
          // SingleReelPlayerActivity with a scale-up "pinch zoom" reveal
          // anchored to exactly this thumbnail (see openPlayerAt()).
          if (r.reelId != null) {
              androidx.core.view.ViewCompat.setTransitionName(h.ivThumb, "reel_thumb_" + r.reelId);
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
          wireItemInteractions(holder, h.ivDoubleTapHeart);
      }

      private void bindPinned(PinnedVH h) {
          if (pinnedReel == null) return;
          if (pinnedReel.thumbUrl != null && !pinnedReel.thumbUrl.isEmpty()) {
              String pinnedUrl = CloudinaryUploader.deriveThumbUrl(pinnedReel.thumbUrl, pinnedThumbSize, "webp");
              String blurUrl   = CloudinaryUploader.deriveThumbUrl(pinnedReel.thumbUrl, BLUR_THUMB_SIZE, "webp");
              Drawable blurPlaceholder = blurHashPlaceholder(pinnedReel.blurHash);
              glideRequests
                      .load(pinnedUrl)
                      .thumbnail(glideRequests.load(blurUrl).apply(GRID_OPTIONS))
                      .apply(GRID_OPTIONS)
                      .placeholder(blurPlaceholder != null ? blurPlaceholder : context.getDrawable(R.drawable.ic_reels))
                      .into(h.ivThumb);
          } else h.ivThumb.setImageResource(R.drawable.ic_reels);
          if (h.ivStackIndicator != null) {
              boolean isCarousel = pinnedReel.photoUrls != null && pinnedReel.photoUrls.size() > 1;
              h.ivStackIndicator.setVisibility(isCarousel ? View.VISIBLE : View.GONE);
          }
          if (pinnedReel.reelId != null) {
              androidx.core.view.ViewCompat.setTransitionName(h.ivThumb, "reel_thumb_" + pinnedReel.reelId);
          }
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
          wireItemInteractions(h, h.ivDoubleTapHeart);
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
              glideRequests.load(preloadUrl).apply(GRID_OPTIONS).preload();
          }
      }
      @Override public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
          super.onViewDetachedFromWindow(holder);
          if (holder instanceof SkeletonVH) ((SkeletonVH)holder).shimmer.stopShimmer();
      }

      @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
          super.onViewRecycled(holder);
          Object tag = holder.itemView.getTag(R.id.tag_peek_runnable);
          if (tag instanceof PeekState) {
              PeekState state = (PeekState) tag;
              if (state.runnable != null) peekHandler.removeCallbacks(state.runnable);
              if (state.pendingClickRunnable != null) {
                  peekHandler.removeCallbacks(state.pendingClickRunnable);
                  state.pendingClickRunnable = null;
              }
              if (state.peeking && longPressReleaseListener != null) {
                  longPressReleaseListener.onLongPressRelease(state.position);
              }
              state.peeking = false;
          }
      }

      static class ReelVH extends RecyclerView.ViewHolder {
          ImageView ivThumb, ivCheckmark, ivStackIndicator, ivDoubleTapHeart;
          TextView tvDuration, tvViewsOverlay, tvCaption;
          View viewSelectOverlay, viewDimOverlay;
          ReelVH(@NonNull View v) {
              super(v);
              ivThumb=v.findViewById(R.id.iv_thumb); tvDuration=v.findViewById(R.id.tv_duration);
              tvViewsOverlay=v.findViewById(R.id.tv_views_overlay); tvCaption=v.findViewById(R.id.tv_caption);
              viewSelectOverlay=v.findViewById(R.id.view_select_overlay);
              viewDimOverlay=v.findViewById(R.id.view_dim_overlay);
              ivCheckmark=v.findViewById(R.id.iv_checkmark);
              ivStackIndicator=v.findViewById(R.id.iv_stack_indicator);
              ivDoubleTapHeart=v.findViewById(R.id.iv_double_tap_heart);
          }
      }
      static class PinnedVH extends RecyclerView.ViewHolder {
          ImageView ivThumb, ivStackIndicator, ivDoubleTapHeart; TextView tvDuration, tvCaption, tvLikes, tvComments, tvViews;
          PinnedVH(@NonNull View v) {
              super(v);
              ivThumb=v.findViewById(R.id.iv_pinned_thumb); tvDuration=v.findViewById(R.id.tv_pinned_duration);
              tvCaption=v.findViewById(R.id.tv_pinned_caption); tvLikes=v.findViewById(R.id.tv_pinned_likes);
              tvComments=v.findViewById(R.id.tv_pinned_comments); tvViews=v.findViewById(R.id.tv_pinned_views);
              ivStackIndicator=v.findViewById(R.id.iv_pinned_stack_indicator);
              ivDoubleTapHeart=v.findViewById(R.id.iv_double_tap_heart);
          }
      }
      static class SkeletonVH extends RecyclerView.ViewHolder {
          ShimmerFrameLayout shimmer;
          SkeletonVH(@NonNull View v) { super(v); shimmer=v.findViewById(R.id.shimmer_layout); }
      }
  }
  