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
  import androidx.annotation.Nullable;
  import androidx.fragment.app.Fragment;
  import androidx.recyclerview.widget.DiffUtil;
  import androidx.recyclerview.widget.GridLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.bumptech.glide.ListPreloader;
  import com.bumptech.glide.RequestBuilder;
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
  import java.util.Collections;
  import java.util.HashMap;
  import java.util.List;
  import java.util.Locale;
  import java.util.Map;
  import java.util.Objects;

  public class ReelGridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>
          implements ListPreloader.PreloadModelProvider<String> {

      public static final int TYPE_SKELETON = 0;
      public static final int TYPE_REEL     = 1;
      public static final int TYPE_PINNED   = 2;
      // Instagram-style bottom spinner row shown during infinite-scroll
      // pagination — see setLoadingFooterVisible().
      public static final int TYPE_FOOTER_LOADING = 3;
      // Instagram-style Posts tab: same cell layout, view-holder and Glide
      // pipeline as TYPE_REEL, just a square (1:1) cell instead of 9:16 —
      // kept as its own type (not a runtime height mutation on TYPE_REEL)
      // so the shared RecycledViewPool never hands a square-sized cell back
      // to the Reels tab or vice versa. See setSquareGridMode().
      public static final int TYPE_POST     = 4;
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
      // Public — the host screen passes this straight to Glide's
      // RecyclerViewPreloader as its maxPreload value (see
      // UserReelsActivity#setupGlidePreloader()), so both stay in sync.
      // Bumped from 6 → 12 alongside the larger network page size (18) so
      // Glide's warm-cache window covers a comparable slice of a page.
      public static final int PRELOAD_AHEAD        = 12;
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
      // Instagram-style bottom spinner row — see setLoadingFooterVisible().
      private boolean                     showLoadingFooter = false;
      // reelIds the CURRENT viewer has already watched (their own
      // reelWatchHistory, regardless of whose profile grid this is) —
      // powers the "Just watched" overlay. See setWatchedReelIds().
      private java.util.Set<String>       watchedReelIds    = java.util.Collections.emptySet();
      private boolean                     skeletonMode      = false;
      private boolean                     showViewsOverlay  = false;
      private boolean                     multiSelectMode   = false;
      private final Map<Integer, Boolean> selectedPositions = new HashMap<>();

      // Advance #4 — resolved once per adapter instance (network type rarely
      // flips mid-scroll; re-create the adapter/screen to re-resolve).
      private final int gridThumbSize;
      private final int pinnedThumbSize;

      // ── Precomputed cell height (no runtime measure) ────────────────────
      // Old approach measured holder.itemView.getWidth() inside a post{}
      // callback on every single onViewAttachedToWindow() and then called
      // setLayoutParams() — that's an extra layout pass triggered on every
      // cell attach while scrolling, a real source of scroll jank on a 3-
      // column grid. Instead, the cell width (screen width / span count,
      // minus the same gutter WhiteGridDecoration reserves) is computed
      // ONCE per adapter instance, and the 16:9 height derived from it is
      // applied directly at creation time in onCreateViewHolder() — no
      // post(), no re-measure, no per-attach layout pass.
      private static final int GRID_SPAN_COUNT = 3;
      private final int precomputedCellHeightPx;
      // Square (1:1) cell height for the Posts tab — same cell width as the
      // 9:16 Reels cell (same GRID_SPAN_COUNT columns), just height = width.
      private final int precomputedSquareCellHeightPx;
      // Instagram-style: Posts tab (photo-only) shows a square grid instead
      // of the 9:16 Reels grid. Toggled by the host screen via
      // setSquareGridMode() whenever the active tab changes.
      private boolean squareGridMode = false;

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
          this.precomputedCellHeightPx = computeCellHeightPx(context);
          this.precomputedSquareCellHeightPx = computeCellWidthPx(context);
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
          this.precomputedCellHeightPx = computeCellHeightPx(context);
          this.precomputedSquareCellHeightPx = computeCellWidthPx(context);
      }

      /** Cell width = screen width split across GRID_SPAN_COUNT columns (matching
       *  WhiteGridDecoration's gutter), height = that width at a 16:9 ratio —
       *  computed once so every cell gets its final height at creation time. */
      private static int computeCellHeightPx(Context ctx) {
          return Math.round(computeCellWidthPx(ctx) * 16f / 9f);
      }

      /** Cell width alone — shared by the 9:16 Reels cell and the 1:1 Posts
       *  cell (square height == this same width). */
      private static int computeCellWidthPx(Context ctx) {
          android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
          int spacingPx  = Math.round(2 * dm.density); // matches WhiteGridDecoration
          return (dm.widthPixels - spacingPx * (GRID_SPAN_COUNT + 1)) / GRID_SPAN_COUNT;
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
          List<ReelModel> oldSnapshot = new ArrayList<>(displayList);
          this.reels = newReels;
          this.displayList = newReels;
          diffDataSetChanged(oldSnapshot);
      }

      /** Called by filter chips to show a subset. Pass null to show all. */
      public void setFilteredData(List<ReelModel> filtered) {
          List<ReelModel> oldSnapshot = new ArrayList<>(displayList);
          this.displayList = (filtered != null) ? filtered : reels;
          diffDataSetChanged(oldSnapshot);
      }

      // ── DiffUtil-based refresh ───────────────────────────────────────────
      // Replaces the old notifyDataSetChanged() (full rebind of every visible
      // cell — every thumbnail re-decoded/re-bound even for rows that didn't
      // change) on the tab-switch/filter/delete paths. Callers pass the
      // list's content as it was BEFORE their mutation; this diffs it
      // against the current (post-mutation) displayList and dispatches only
      // the actual inserts/removes/changes, so RecyclerView can run its own
      // move/fade animations on just the affected rows instead of a hard
      // full-grid rebind.
      public void diffDataSetChanged(List<ReelModel> oldSnapshot) {
          List<Object> oldSnap = buildDiffSnapshot(oldSnapshot);
          List<Object> newSnap = buildDiffSnapshot(displayList);
          DiffUtil.DiffResult result = DiffUtil.calculateDiff(new ReelDiffCallback(oldSnap, newSnap));
          result.dispatchUpdatesTo(this);
      }

      /** Snapshot list = [pinned marker if present] + data, so diff positions map 1:1 to adapter positions. */
      private List<Object> buildDiffSnapshot(List<ReelModel> data) {
          List<Object> snap = new ArrayList<>(data.size() + 1);
          if (hasPinned()) snap.add(pinnedReel);
          snap.addAll(data);
          return snap;
      }

      /** Identity = reelId (or same pinned-marker reference); content = the fields the grid cell actually shows. */
      private static class ReelDiffCallback extends DiffUtil.Callback {
          private final List<Object> oldList, newList;
          ReelDiffCallback(List<Object> oldList, List<Object> newList) {
              this.oldList = oldList; this.newList = newList;
          }
          @Override public int getOldListSize() { return oldList.size(); }
          @Override public int getNewListSize() { return newList.size(); }
          @Override public boolean areItemsTheSame(int oldPos, int newPos) {
              Object o = oldList.get(oldPos), n = newList.get(newPos);
              if (o instanceof ReelModel && n instanceof ReelModel) {
                  String oid = ((ReelModel) o).reelId, nid = ((ReelModel) n).reelId;
                  return oid != null && oid.equals(nid);
              }
              return o == n;
          }
          @Override public boolean areContentsTheSame(int oldPos, int newPos) {
              Object o = oldList.get(oldPos), n = newList.get(newPos);
              if (o instanceof ReelModel && n instanceof ReelModel) {
                  ReelModel a = (ReelModel) o, b = (ReelModel) n;
                  return a.likesCount == b.likesCount
                          && a.commentsCount == b.commentsCount
                          && a.viewsCount == b.viewsCount
                          && Objects.equals(a.caption, b.caption)
                          && Objects.equals(a.thumbUrl, b.thumbUrl);
              }
              return true;
          }
      }

      /**
       * ULTRA: was a full notifyDataSetChanged() — rebound every visible cell
       * (re-decoded every thumbnail) just to change the ONE pinned slot.
       * Now dispatches only the minimal structural change:
       *  - pin added where there wasn't one  → notifyItemInserted(0)
       *  - pin removed                        → notifyItemRemoved(0)
       *  - pin content changed (still pinned) → notifyItemChanged(0)
       *  - no pin before/after                → nothing to do
       */
      public void setPinnedReel(ReelModel reel) {
          boolean hadPinned = hasPinned();
          this.pinnedReel = reel;
          boolean hasPinnedNow = hasPinned();
          if (!hadPinned && hasPinnedNow)      notifyItemInserted(0);
          else if (hadPinned && !hasPinnedNow) notifyItemRemoved(0);
          else if (hadPinned && hasPinnedNow)  notifyItemChanged(0);
      }
      public boolean hasPinned()                    { return pinnedReel != null && !skeletonMode; }
      private int reelIndexFor(int pos)             { return hasPinned() ? pos - 1 : pos; }
      public void setSkeletonMode(boolean s)        { this.skeletonMode = s; }
      public boolean isSkeletonMode()                { return this.skeletonMode; }
      public void setShowViewsOverlay(boolean show) { this.showViewsOverlay = show; }
      /**
       * ULTRA: entering multi-select needs every VISIBLE cell to rebind (a
       * checkbox overlay appears on each), so a full rebind is unavoidable —
       * but notifyItemRangeChanged(content-only) is lighter than
       * notifyDataSetChanged() since it skips RecyclerView's structural
       * re-evaluation (no need to re-check span/type/stable-id layout) and
       * lets the ItemAnimator run a plain change animation instead of a
       * full adapter reset. Leaving multi-select just clears selections,
       * which already only touches the previously-selected cells.
       */
      public void setMultiSelectMode(boolean e) {
          this.multiSelectMode = e;
          if (!e) clearSelections();
          else if (getItemCount() > 0) notifyItemRangeChanged(0, getItemCount());
      }
      public void setSelected(int pos, boolean sel) { if (sel) selectedPositions.put(pos, true); else selectedPositions.remove(pos); }
      /**
       * ULTRA: was notifyDataSetChanged() on every "clear" — rebound the
       * WHOLE grid to undo checkmarks on a handful of cells. Now only the
       * cells that were actually selected get notified.
       */
      public void clearSelections() {
          if (selectedPositions.isEmpty()) return;
          List<Integer> toClear = new ArrayList<>(selectedPositions.keySet());
          selectedPositions.clear();
          for (int pos : toClear) notifyItemChanged(pos);
      }
      public int  getSelectedCount()                { return selectedPositions.size(); }

      @Override public int getItemViewType(int pos) {
          if (skeletonMode) return TYPE_SKELETON;
          if (hasPinned() && pos == 0) return TYPE_PINNED;
          if (showLoadingFooter && pos == getItemCount() - 1) return TYPE_FOOTER_LOADING;
          return squareGridMode ? TYPE_POST : TYPE_REEL;
      }

      /**
       * Instagram-style Posts tab toggle: call whenever the host screen's
       * active tab changes (see UserReelsActivity's tab-select listener).
       * Switches ordinary reel cells between the 9:16 Reels layout and the
       * 1:1 Posts layout. Cheap no-op if the mode isn't actually changing.
       * notifyDataSetChanged() is correct (not wasteful) here — the item
       * VIEW TYPE itself changes for every reel cell, so every visible
       * holder needs a fresh onCreateViewHolder anyway; a diff wouldn't
       * avoid that.
       */
      public void setSquareGridMode(boolean square) {
          if (this.squareGridMode == square) return;
          this.squareGridMode = square;
          notifyDataSetChanged();
      }
      public boolean isSquareGridMode() { return squareGridMode; }
      @Override public int getItemCount() {
          if (skeletonMode) return SKELETON_COUNT;
          return displayList.size() + (hasPinned() ? 1 : 0) + (showLoadingFooter ? 1 : 0);
      }

      /**
       * Instagram-style bottom "loading more" row for infinite scroll:
       * shown the instant pagination decides to fetch the next page (see
       * UserReelsActivity#maybeLoadNextPage), hidden the instant that fetch
       * lands — whether it added rows or found there was nothing left.
       * A single notifyItemInserted/Removed on just the footer slot; every
       * other bound cell is left completely untouched.
       */
      public void setLoadingFooterVisible(boolean show) {
          if (show == showLoadingFooter) return;
          if (show) {
              showLoadingFooter = true;
              notifyItemInserted(getItemCount() - 1);
          } else {
              int footerPos = getItemCount() - 1; // still true here, so this IS the footer's position
              showLoadingFooter = false;
              notifyItemRemoved(footerPos);
          }
      }
      public boolean isLoadingFooterVisible() { return showLoadingFooter; }

      /**
       * Removes the footer at the EXACT position it occupied at show-time.
       * Callers must pass the position captured right after
       * setLoadingFooterVisible(true) returned, before any further list
       * mutation — RecyclerView's own internal bookkeeping hasn't seen those
       * mutations either yet, so this keeps the notify call in sync with
       * what RecyclerView currently believes, instead of recomputing from a
       * getItemCount() that may already reflect items appended since.
       */
      public void hideLoadingFooterAt(int position) {
          if (!showLoadingFooter) return;
          showLoadingFooter = false;
          if (position >= 0) notifyItemRemoved(position);
      }

      /**
       * Instant, no-notify reset — for when a broader structural change
       * (tab switch → setDataList()'s own diff/notifyDataSetChanged) is
       * about to run anyway and will already reflect the corrected item
       * count on its own. Prevents a footer shown on the previous tab from
       * silently surviving as a phantom row on the tab just switched to.
       */
      public void resetLoadingFooterState() { showLoadingFooter = false; }

      /**
       * Instagram-style "Just watched" grid overlay: pass the full set of
       * reelIds the CURRENT viewer has already watched (see
       * UserReelsActivity#loadWatchedReelIds() — local Room cache first for
       * an instant first paint, then merged with a Firebase incremental
       * sync). Called at most twice per screen open (once per source), so a
       * plain notifyDataSetChanged() here is the right tradeoff — same as
       * every other "whole-grid recompute" moment in this adapter (tab
       * switch, filter change).
       */
      public void setWatchedReelIds(java.util.Set<String> ids) {
          this.watchedReelIds = ids != null ? ids : java.util.Collections.emptySet();
          notifyDataSetChanged();
      }

      @NonNull @Override
      public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int type) {
          // ULTRA (profiling): named Trace section so Perfetto/Systrace shows
          // inflation as its own slice, separate from bind/image-decode work
          // below — lets you see at a glance in the trace whether a jank
          // frame's time was spent HERE (inflate + layout) or in the Glide
          // section inside onBindViewHolder. No-op / negligible cost when no
          // trace is being captured.
          android.os.Trace.beginSection("ReelGridAdapter.onCreateViewHolder");
          try {
              LayoutInflater inf = LayoutInflater.from(context);
              if (type == TYPE_SKELETON) return new SkeletonVH(inf.inflate(R.layout.item_reel_skeleton, p, false));
              if (type == TYPE_PINNED)   return new PinnedVH(inf.inflate(R.layout.item_pinned_reel, p, false));
              if (type == TYPE_FOOTER_LOADING) return new FooterVH(inf.inflate(R.layout.item_reel_loading_footer, p, false));
              boolean isPost = (type == TYPE_POST);
              // Same cell XML/ids for both — only the target height differs,
              // so the exact same Glide/optimization pipeline in bind() below
              // applies unchanged to Posts cells.
              View reelView = inf.inflate(R.layout.item_saved_reel, p, false);
              // Height fixed here, once, instead of measured+applied on every
              // onViewAttachedToWindow() — see precomputedCellHeightPx /
              // precomputedSquareCellHeightPx.
              int targetHeight = isPost ? precomputedSquareCellHeightPx : precomputedCellHeightPx;
              ViewGroup.LayoutParams lp = reelView.getLayoutParams();
              if (lp == null) lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, targetHeight);
              else lp.height = targetHeight;
              reelView.setLayoutParams(lp);
              return new ReelVH(reelView, isPost);
          } finally {
              android.os.Trace.endSection();
          }
      }

      @Override
      public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
          // ULTRA (profiling): whole bind wrapped so it's visible as a slice
          // per row in Perfetto; the Glide setup call is wrapped separately
          // inside so that slice specifically shows request-building cost
          // (NOT the actual bitmap decode — Glide decodes off the main
          // thread on a background executor, so decode itself won't show up
          // as main-thread jank here; if THIS slice is wide, it's inflation/
          // bind-side work, if frames still drop with this slice narrow,
          // suspect decode/upload-to-GPU on Glide's own threads instead —
          // check Perfetto's "GlideModule" / Glide-thread tracks for that).
          android.os.Trace.beginSection("ReelGridAdapter.onBindViewHolder");
          try {
              bindViewHolderInternal(holder, position);
          } finally {
              android.os.Trace.endSection();
          }
      }

      private void bindViewHolderInternal(@NonNull RecyclerView.ViewHolder holder, int position) {
          if (holder instanceof SkeletonVH) { ((SkeletonVH) holder).shimmer.startShimmer(); return; }
          if (holder instanceof PinnedVH)   { bindPinned((PinnedVH) holder); return; }
          if (holder instanceof FooterVH)   { return; } // indeterminate ProgressBar animates on its own, nothing to bind
          if (!(holder instanceof ReelVH))  return;
          ReelVH h = (ReelVH) holder;
          int idx = reelIndexFor(position);
          if (idx < 0 || idx >= displayList.size()) return;
          ReelModel r = displayList.get(idx);

          if (r.thumbUrl != null && !r.thumbUrl.isEmpty()) {
              // Ultra: the single biggest per-bind cost in this holder —
              // two deriveThumbUrl() string builds plus the entire Glide
              // .load().thumbnail().apply().placeholder().into() chain —
              // was re-running on EVERY bind, including rebinds that have
              // nothing to do with the image itself (like-count ticks,
              // watched-state, selection-mode, DiffUtil payload updates).
              // The thumbnail this holder's ImageView is already showing is
              // still correct in all of those cases, since it was set by
              // the last bind for this exact same thumbUrl and nothing
              // clears it in between (onViewRecycled doesn't touch the
              // image). So: skip the whole chain when this holder's
              // last-loaded thumbUrl hasn't changed, and only pay for it
              // again on a genuine recycle onto a different reel/image.
              if (!r.thumbUrl.equals(h.lastThumbUrl)) {
                  h.lastThumbUrl = r.thumbUrl;
                  android.os.Trace.beginSection("ReelGridAdapter.glideRequestSetup");
                  try {
                      String gridUrl = CloudinaryUploader.deriveThumbUrl(r.thumbUrl, gridThumbSize, "webp");
                      String blurUrl = CloudinaryUploader.deriveThumbUrl(r.thumbUrl, BLUR_THUMB_SIZE, "webp");
                      Drawable blurPlaceholder = blurHashPlaceholderFor(h, r.blurHash);
                      glideRequests
                              .load(gridUrl)
                              .thumbnail(glideRequests.load(blurUrl).apply(GRID_OPTIONS))
                              .apply(GRID_OPTIONS)
                              .placeholder(blurPlaceholder != null ? blurPlaceholder : context.getDrawable(R.drawable.ic_reels))
                              .into(h.ivThumb);
                  } finally {
                      android.os.Trace.endSection();
                  }
              }
          } else {
              h.lastThumbUrl = null;
              h.ivThumb.setImageResource(R.drawable.ic_reels);
          }

          // Ultra: tv_caption doesn't exist in item_saved_reel.xml (only
          // fragment_reel_player.xml has that id) — h.tvCaption was always
          // null here, so this whole block was permanently dead code, not
          // just a redundant call. Removed along with the field/lookup
          // below (ReelVH.tvCaption / findViewById(R.id.tv_caption)).
          // Instagram-style: the Posts tab (photo-only, square grid) shows
          // neither the play affordance nor the views-count pill — those
          // are video-only signals and would sit oddly on a plain photo
          // cell. Both ivPlayOverlay's and tvViewsOverlay's visibility are
          // now set once in ReelVH's constructor instead of here (isPost is
          // fixed per holder, so GONE-for-post/VISIBLE-for-reel never needs
          // to be re-applied on later binds — only the views *text* below
          // still needs a per-bind update, since the count itself changes).
          if (h.tvViewsOverlay != null && !h.isPost) {
              // Ultra: same rebind-skip pattern as the carousel badge —
              // viewsCount rarely changes between rebinds (like/watch/
              // selection updates don't touch it), so re-running
              // formatCount() + setText() every time is wasted work.
              int views = Math.max(r.viewsCount, 0);
              if (h.lastViewsCount != views) {
                  h.lastViewsCount = views;
                  h.tvViewsOverlay.setText(formatCount(views));
              }
          }
          // Ultra: same last-state-skip pattern as the selection overlays
          // below — justWatched is derived from a Set#contains() check
          // that resolves to the same VISIBLE/GONE result on almost every
          // rebind of this holder, so only touch the two views when the
          // resolved visibility actually flips.
          boolean justWatched = r.reelId != null && watchedReelIds.contains(r.reelId);
          int watchedVis = justWatched ? View.VISIBLE : View.GONE;
          if (h.viewWatchedScrim != null && h.lastWatchedScrimVis != watchedVis) {
              h.viewWatchedScrim.setVisibility(watchedVis);
              h.lastWatchedScrimVis = watchedVis;
          }
          if (h.tvJustWatched != null && h.lastJustWatchedVis != watchedVis) {
              h.tvJustWatched.setVisibility(watchedVis);
              h.lastJustWatchedVis = watchedVis;
          }
          // Carousel indicator — "+N" total-photo-count badge (Instagram-
          // style, top-right) for reels backed by more than one photo/clip
          // (r.photoUrls has 2+ entries). Same badge used on both the 9:16
          // Reels cell and the square Posts cell now — the old plain stack
          // icon (iv_stack_indicator) is retired in favor of this, since the
          // count tells the viewer more than a generic "multiple items" glyph.
          // Ultra: iv_stack_indicator is retired in favor of tv_carousel_count
          // (below) and its layout default is already visibility="gone" —
          // this used to force-set GONE on every single bind for no reason
          // (the view is never set VISIBLE anywhere in this holder type).
          // Removed entirely; ivStackIndicator itself is left wired in
          // ReelVH only because bindPinned()'s separate PinnedVH type still
          // uses its own iv_pinned_stack_indicator actively.
          if (h.tvCarouselCount != null) {
              // Ultra: this cell rebinds constantly for reasons that have
              // nothing to do with the photo count (like-count ticks,
              // watched-state toggles, selection-mode changes, DiffUtil
              // payload updates) — most of those rebinds would otherwise
              // redo a "+" + size() concat and a setText()/setVisibility()
              // pass for a value that hasn't actually changed. Compare
              // against what's already showing on this recycled holder and
              // skip both calls entirely when nothing moved; when it did,
              // pull the digit string from a precomputed small-int cache
              // instead of concatenating a fresh String every time.
              int count = (r.photoUrls != null && r.photoUrls.size() > 1) ? r.photoUrls.size() : 0;
              if (h.lastCarouselCount != count) {
                  h.lastCarouselCount = count;
                  if (count > 0) {
                      h.tvCarouselCount.setText(carouselCountText(count));
                      h.tvCarouselCount.setVisibility(View.VISIBLE);
                  } else {
                      h.tvCarouselCount.setVisibility(View.GONE);
                  }
              }
          }
          // Shared-element transition name — lets UserReelsActivity open
          // SingleReelPlayerActivity with a scale-up "pinch zoom" reveal
          // anchored to exactly this thumbnail (see openPlayerAt()). Ultra:
          // reelId never changes across a holder's rebinds (only on real
          // recycle onto a different reel), so gate both the "+" concat and
          // the setTransitionName() call behind an equality check instead
          // of redoing them on every single bind regardless of transitions
          // even being in play for that bind.
          if (r.reelId != null && !r.reelId.equals(h.lastTransitionReelId)) {
              h.lastTransitionReelId = r.reelId;
              androidx.core.view.ViewCompat.setTransitionName(h.ivThumb, "reel_thumb_" + r.reelId);
          }
          if (h.tvDuration != null) {
              // Ultra: same last-state-skip pattern as the selection/
              // watched overlays — setText() was already gated above via
              // lastDurationMs, but setVisibility(VISIBLE) still ran
              // unconditionally on every bind of every video cell (the
              // vast majority of cells) regardless of whether it was
              // already VISIBLE. Gate it the same way.
              int durationVis = r.duration > 0 ? View.VISIBLE : View.GONE;
              if (durationVis == View.VISIBLE && h.lastDurationMs != r.duration) {
                  h.lastDurationMs = r.duration;
                  h.tvDuration.setText(formatDuration(r.duration));
              } else if (durationVis == View.GONE) {
                  h.lastDurationMs = -1;
              }
              if (h.lastDurationVis != durationVis) {
                  h.lastDurationVis = durationVis;
                  h.tvDuration.setVisibility(durationVis);
              }
          }

          // Ultra: GONE→GONE was already a cheap no-op inside Android's own
          // View.setVisibility() (it early-exits when the flag doesn't
          // change), but the VISIBLE↔INVISIBLE toggle here didn't have that
          // for free — multiSelectMode/sel is recomputed and re-applied on
          // every single bind of every cell even when nothing about this
          // holder's selection state actually moved. Track what's already
          // showing on this holder and only call setVisibility() when the
          // resolved value differs from last time.
          boolean sel = multiSelectMode && Boolean.TRUE.equals(selectedPositions.get(position));
          int selectOverlayVis = multiSelectMode ? (sel ? View.VISIBLE : View.INVISIBLE) : View.GONE;
          int checkmarkVis     = selectOverlayVis; // same tri-state resolution as the overlay
          int dimOverlayVis    = multiSelectMode ? View.VISIBLE : View.GONE;
          if (h.viewSelectOverlay != null && h.lastSelectOverlayVis != selectOverlayVis) {
              h.viewSelectOverlay.setVisibility(selectOverlayVis);
              h.lastSelectOverlayVis = selectOverlayVis;
          }
          if (h.ivCheckmark != null && h.lastCheckmarkVis != checkmarkVis) {
              h.ivCheckmark.setVisibility(checkmarkVis);
              h.lastCheckmarkVis = checkmarkVis;
          }
          if (h.viewDimOverlay != null && h.lastDimOverlayVis != dimOverlayVis) {
              h.viewDimOverlay.setVisibility(dimOverlayVis);
              h.lastDimOverlayVis = dimOverlayVis;
          }

          // Ultra: this listener's only job is to forward a click to
          // clickListener.onItemClick(currentAdapterPosition) — it doesn't
          // capture position at bind time, it reads getAdapterPosition()
          // fresh at click time, so the same lambda instance stays correct
          // across every future rebind of this holder no matter what
          // position it gets recycled onto. Allocating a fresh lambda on
          // every single bind (of every cell, every scroll frame) was pure
          // waste; set it once per holder instead.
          if (!h.clickListenerSet) {
              h.clickListenerSet = true;
              h.itemView.setOnClickListener(v -> { if (clickListener != null) clickListener.onItemClick(h.getAdapterPosition()); });
          }
          // Ultra: wireItemInteractions() below allocates a PeekState object
          // AND an anonymous View.OnTouchListener instance, plus does a
          // setTag() + setOnTouchListener() pair — the biggest per-bind
          // allocation cost in this holder. Everything it captures (holder,
          // itemView, heartOverlay) is the exact same object on every
          // future rebind of this same recycled holder, so — like the
          // click listener above — wiring it once per holder and never
          // again is correct, not just faster.
          if (!h.interactionsWired) {
              h.interactionsWired = true;
              wireItemInteractions(holder, h.ivDoubleTapHeart);
          }
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
          if (h.tvJustWatched != null) {
              boolean justWatched = pinnedReel.reelId != null && watchedReelIds.contains(pinnedReel.reelId);
              h.tvJustWatched.setVisibility(justWatched ? View.VISIBLE : View.GONE);
          }
          h.itemView.setOnClickListener(v -> { if (clickListener != null) clickListener.onItemClick(0); });
          if (!h.interactionsWired) {
              h.interactionsWired = true;
              wireItemInteractions(h, h.ivDoubleTapHeart);
          }
      }

      // Ultra: precomputed "+N" text for the carousel badge (N = 1..99,
      // realistically the entire real-world range for a photo carousel).
      // Grid cells rebind far more often than the underlying photo count
      // ever changes, so sharing one String per count instead of
      // concatenating "+" + size() on every bind removes a per-bind
      // allocation from the hot RecyclerView scroll path. Built once,
      // statically, at class-load — not per-adapter-instance.
      private static final int CAROUSEL_COUNT_CACHE_SIZE = 100;
      private static final String[] CAROUSEL_COUNT_CACHE = new String[CAROUSEL_COUNT_CACHE_SIZE];
      static {
          for (int i = 0; i < CAROUSEL_COUNT_CACHE_SIZE; i++) CAROUSEL_COUNT_CACHE[i] = "+" + i;
      }
      private static String carouselCountText(int count) {
          return (count > 0 && count < CAROUSEL_COUNT_CACHE_SIZE) ? CAROUSEL_COUNT_CACHE[count] : ("+" + count);
      }

      private static String formatCount(int n) {
          // Ultra: avoids java.util.Formatter — String.format() parses the
          // "%.1fK"-style pattern and does a Locale lookup on every single
          // call, which is a lot of overhead for two decimal digits and a
          // suffix char. Manual rounding + concat produces the identical
          // output without any of that.
          if (n >= 1_000_000) return formatScaled(n, 1_000_000, 'M');
          if (n >= 1_000)     return formatScaled(n, 1_000, 'K');
          return String.valueOf(n);
      }

      private static String formatScaled(int n, int unit, char suffix) {
          int tenths = Math.round(n * 10f / unit); // same rounding as the old "%.1f"
          int whole = tenths / 10, frac = tenths % 10;
          return whole + "." + frac + suffix;
      }

      private static String formatDuration(int durationMs) {
          // Ultra: same reasoning as formatScaled() above — String.format
          // with "%d:%02d" goes through Formatter/Locale for a fixed
          // mm:ss shape that a plain StringBuilder can produce directly.
          int totalSec = durationMs / 1000;
          int m = totalSec / 60, s = totalSec % 60;
          StringBuilder sb = new StringBuilder(5).append(m).append(':');
          if (s < 10) sb.append('0');
          return sb.append(s).toString();
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

      /**
       * Ultra: per-holder wrapper around blurHashPlaceholder() above. The
       * Bitmap itself was already cached (blurHashCache), but a fresh
       * BitmapDrawable wrapper was still being allocated on every bind even
       * when this exact holder was just showing the same blurHash a moment
       * ago (e.g. rebound for a like/watched/selection change unrelated to
       * the image). Reusing this holder's own previous Drawable instance
       * across such consecutive binds is safe because it's confined to
       * this one ImageView — never handed to a second View at the same
       * time — unlike caching one shared Drawable instance across
       * *different* holders, which would corrupt each other's bounds when
       * drawn at different cell sizes (square Posts cell vs 9:16 Reels
       * cell) since Drawable bounds are per-instance mutable state.
       */
      private Drawable blurHashPlaceholderFor(ReelVH h, String blurHash) {
          if (blurHash == null || blurHash.isEmpty()) {
              h.lastBlurHashKey = null;
              h.lastBlurPlaceholder = null;
              return null;
          }
          if (blurHash.equals(h.lastBlurHashKey) && h.lastBlurPlaceholder != null) {
              return h.lastBlurPlaceholder;
          }
          Drawable d = blurHashPlaceholder(blurHash);
          h.lastBlurHashKey = blurHash;
          h.lastBlurPlaceholder = d;
          return d;
      }

      @Override public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
          super.onViewAttachedToWindow(holder);
          // Cell height is now fixed at creation time (see onCreateViewHolder /
          // precomputedCellHeightPx) — no per-attach measure+relayout here
          // anymore. Thumbnail preloading is likewise no longer done manually
          // per-attach: Glide's RecyclerViewPreloader (wired by the host
          // screen via getPreloadItems()/getPreloadRequestBuilder() below)
          // does this more precisely, based on actual scroll direction and
          // velocity instead of a fixed "next N" guess.
      }

      // ── Glide RecyclerViewPreloader hooks ───────────────────────────────
      // Implements ListPreloader.PreloadModelProvider<String> so the host
      // screen can hand this adapter straight to a RecyclerViewPreloader
      // (see UserReelsActivity#setupGlidePreloader()) instead of the old
      // manual "warm the next 6 cells on attach" approach — the preloader
      // itself decides how far ahead to fetch based on real scroll speed.

      @NonNull @Override
      public List<String> getPreloadItems(int position) {
          if (skeletonMode) return Collections.emptyList();
          int idx = reelIndexFor(position);
          if (idx < 0 || idx >= displayList.size()) return Collections.emptyList();
          ReelModel r = displayList.get(idx);
          if (r == null || r.thumbUrl == null || r.thumbUrl.isEmpty()) return Collections.emptyList();
          return Collections.singletonList(CloudinaryUploader.deriveThumbUrl(r.thumbUrl, gridThumbSize, "webp"));
      }

      @Nullable @Override
      public RequestBuilder<?> getPreloadRequestBuilder(@NonNull String item) {
          return glideRequests.load(item).apply(GRID_OPTIONS);
      }

      /** Thumb pixel size Glide should decode to while preloading — matches the real bind size. */
      public int getGridThumbSizePx() { return gridThumbSize; }

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
          ImageView ivThumb, ivCheckmark, ivStackIndicator, ivDoubleTapHeart, ivPlayOverlay;
          TextView tvDuration, tvViewsOverlay, tvJustWatched, tvCarouselCount;
          View viewSelectOverlay, viewDimOverlay, viewWatchedScrim;
          // true when this holder is currently bound as a square Posts-tab
          // cell (TYPE_POST) rather than the 9:16 Reels cell (TYPE_REEL) —
          // set once at creation since a holder never changes type without
          // being torn down/recreated by RecyclerView.
          final boolean isPost;
          // Ultra: last thumbUrl this holder's Glide chain was actually
          // issued for — see bindViewHolderInternal(). null means "never
          // bound to an image yet".
          String lastThumbUrl = null;
          // Ultra: guards the one-time setOnClickListener() below — see
          // bindViewHolderInternal().
          boolean clickListenerSet = false;
          // Ultra: guards the one-time wireItemInteractions() call — see
          // bindViewHolderInternal(). Same holder = same PeekState +
          // OnTouchListener forever.
          boolean interactionsWired = false;
          // Ultra: last-applied visibility for the "just watched" scrim +
          // label — -1 sentinel means "never bound".
          int lastWatchedScrimVis = -1;
          int lastJustWatchedVis = -1;
          // Ultra: last photo-count actually rendered into tvCarouselCount
          // on this (recycled) holder — -1 means "never bound yet" so the
          // very first bind always runs. See bindViewHolderInternal().
          int lastCarouselCount = -1;
          // Ultra: same rebind-skip idea for views-count and duration —
          // -1 means "never bound", so the first real bind always runs.
          int lastViewsCount = -1;
          int lastDurationMs = -1;
          // Ultra: last-applied visibility for tv_duration — see the
          // gating block in bindViewHolderInternal().
          int lastDurationVis = -1;
          String lastTransitionReelId = null;
          // Ultra: per-holder BlurHash placeholder cache — see
          // blurHashPlaceholderFor(). Confined to this holder only, never
          // shared across holders.
          String lastBlurHashKey = null;
          Drawable lastBlurPlaceholder = null;
          // Ultra: last-applied visibility for the three selection-mode
          // overlays — -1 sentinel means "never bound", so the first real
          // bind always applies. See bindViewHolderInternal().
          int lastSelectOverlayVis = -1;
          int lastCheckmarkVis = -1;
          int lastDimOverlayVis = -1;
          ReelVH(@NonNull View v) { this(v, false); }
          ReelVH(@NonNull View v, boolean isPost) {
              super(v);
              this.isPost = isPost;
              ivThumb=v.findViewById(R.id.iv_thumb); tvDuration=v.findViewById(R.id.tv_duration);
              tvViewsOverlay=v.findViewById(R.id.tv_views_overlay);
              viewSelectOverlay=v.findViewById(R.id.view_select_overlay);
              viewDimOverlay=v.findViewById(R.id.view_dim_overlay);
              viewWatchedScrim=v.findViewById(R.id.view_watched_scrim);
              tvJustWatched=v.findViewById(R.id.tv_just_watched);
              ivCheckmark=v.findViewById(R.id.iv_checkmark);
              ivStackIndicator=v.findViewById(R.id.iv_stack_indicator);
              ivDoubleTapHeart=v.findViewById(R.id.iv_double_tap_heart);
              ivPlayOverlay=v.findViewById(R.id.iv_play_overlay);
              tvCarouselCount=v.findViewById(R.id.tv_carousel_count);
              // Ultra: isPost never changes for the lifetime of this
              // holder (a holder is never re-typed between Posts/Reels —
              // see the isPost field doc above), so this visibility is a
              // one-time constant, not something to redo on every bind.
              // Was previously set unconditionally inside
              // bindViewHolderInternal() on every single bind.
              if (ivPlayOverlay != null) {
                  ivPlayOverlay.setVisibility(isPost ? View.GONE : View.VISIBLE);
              }
              // Ultra: same reasoning as ivPlayOverlay above — the views
              // pill is GONE for Posts cells / VISIBLE for Reels cells,
              // period, for this holder's whole life. Only the *text*
              // inside it still needs a per-bind update (see
              // bindViewHolderInternal) since the view count itself
              // changes; the show/hide state never does.
              if (tvViewsOverlay != null) {
                  tvViewsOverlay.setVisibility(isPost ? View.GONE : View.VISIBLE);
              }
          }
      }
      static class PinnedVH extends RecyclerView.ViewHolder {
          ImageView ivThumb, ivStackIndicator, ivDoubleTapHeart; TextView tvDuration, tvCaption, tvLikes, tvComments, tvViews, tvJustWatched;
          // Ultra: same one-time-wiring guard as ReelVH.interactionsWired.
          boolean interactionsWired = false;
          PinnedVH(@NonNull View v) {
              super(v);
              ivThumb=v.findViewById(R.id.iv_pinned_thumb); tvDuration=v.findViewById(R.id.tv_pinned_duration);
              tvCaption=v.findViewById(R.id.tv_pinned_caption); tvLikes=v.findViewById(R.id.tv_pinned_likes);
              tvComments=v.findViewById(R.id.tv_pinned_comments); tvViews=v.findViewById(R.id.tv_pinned_views);
              ivStackIndicator=v.findViewById(R.id.iv_pinned_stack_indicator);
              ivDoubleTapHeart=v.findViewById(R.id.iv_double_tap_heart);
              tvJustWatched=v.findViewById(R.id.tv_pinned_just_watched);
          }
      }
      static class SkeletonVH extends RecyclerView.ViewHolder {
          ShimmerFrameLayout shimmer;
          SkeletonVH(@NonNull View v) { super(v); shimmer=v.findViewById(R.id.shimmer_layout); }
      }
      static class FooterVH extends RecyclerView.ViewHolder {
          FooterVH(@NonNull View v) { super(v); }
      }
  }
  