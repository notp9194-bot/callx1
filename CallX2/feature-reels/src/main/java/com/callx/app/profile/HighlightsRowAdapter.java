package com.callx.app.profile;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;
import com.callx.app.utils.CloudinaryUploader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * HighlightsRowAdapter — Instagram-style horizontal Highlights row.
 *
 * Layout per item:
 *   ┌─────────────────────┐
 *   │  [gradient ring]    │  ← 70dp × 70dp oval ring (3dp stroke gap)
 *   │   [cover circle]    │  ← 62dp circle cover photo / emoji / color
 *   │  Album Name         │  ← 11sp, max 8 chars, centered below
 *   └─────────────────────┘
 *
 * First item (isSelf=true): "+" / "New" button → create/manage highlights.
 * Subsequent items: existing albums → tap to view, long-press (self) to manage.
 */
public class HighlightsRowAdapter
        extends RecyclerView.Adapter<HighlightsRowAdapter.HVH> {

    // ── Model ──────────────────────────────────────────────────────────
    public static class HighlightAlbum {
        public String  albumId;
        public String  albumName;
        public String  coverUrl;      // first media URL (nullable)
        public String  coverBgColor;  // fallback hex if no image
        public int     itemCount;
        public boolean isNew;         // "+" placeholder item

        /** User-chosen ring color (e.g. "#FF3B5C") — null means the default
         *  app-wide multi-color gradient ring. Set from
         *  statusHighlightMeta/{uid}/{albumId}/ringColor. */
        public String  ringColor;
        /** {@link com.callx.app.utils.HighlightRingDrawable#MODE_SOLID} or
         *  {@link com.callx.app.utils.HighlightRingDrawable#MODE_DOMINANT};
         *  only meaningful when ringColor is non-null. */
        public String  ringMode;

        /** Normal album */
        public HighlightAlbum(String albumId, String albumName,
                               String coverUrl, String coverBgColor, int itemCount) {
            this.albumId      = albumId;
            this.albumName    = albumName;
            this.coverUrl     = coverUrl;
            this.coverBgColor = coverBgColor;
            this.itemCount    = itemCount;
            this.isNew        = false;
        }

        /** "New" add-button placeholder */
        public static HighlightAlbum newButton() {
            HighlightAlbum a = new HighlightAlbum("__new__", "New", null, null, 0);
            a.isNew = true;
            return a;
        }
    }

    // ── Listener ───────────────────────────────────────────────────────
    public interface Listener {
        /** Tap on an existing album */
        void onAlbumClicked(HighlightAlbum album);
        /** Long-press on an existing album (self only) */
        void onAlbumLongPressed(HighlightAlbum album, int position);
        /** Tap "New" button */
        void onNewClicked();
    }

    // Right-sized cover: circle is 62dp — at 3x density that's ~186px, so
    // 160px is already sharp with zero visible detail loss, vs. the old
    // hardcoded .override(480, 853) which pulled a full portrait-sized
    // image just to show inside a small circle (Instagram-style "load only
    // what the cell can actually show", same approach as the reel grid).
    private static final int COVER_SIZE      = 160;
    // Tiny/heavily-compressed variant of the same cover shown via Glide's
    // thumbnail() request while the real cover loads — blur-up instead of
    // a blank flash, same trick used in the reel grid (ReelGridAdapter).
    private static final int BLUR_COVER_SIZE = 16;
    // How many upcoming highlight covers to warm into Glide's disk cache
    // while the earlier ones are still on screen, so scrolling the row
    // never shows a fetch pause.
    private static final int PRELOAD_AHEAD   = 4;

    // Shared, reusable options for cover loads — CircleCrop is stateless so
    // it's safe to reuse across every bind/preload instead of allocating a
    // fresh transform object each time. Kept at ARGB_8888 (Glide's default)
    // deliberately: CircleCrop needs an alpha channel to mask outside the
    // circle, so RGB_565 (used in ReelGridAdapter for opaque rectangular
    // thumbs) would corrupt the circular edge here — dontAnimate() is the
    // safe win for this shape (skips the crossfade TransitionDrawable).
    private static final RequestOptions COVER_OPTIONS = new RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .transform(new CircleCrop())
            .dontAnimate();

    // ── Fields ─────────────────────────────────────────────────────────
    private final List<HighlightAlbum> items;
    private final boolean              isSelf;
    private final Listener             listener;
    // Built once and reused for every bind/preload instead of calling
    // Glide.with(ctx) fresh per item — avoids repeated RequestManager
    // lookups while scrolling.
    private final RequestManager       glideRequests;

    public HighlightsRowAdapter(Context ctx, List<HighlightAlbum> items, boolean isSelf, Listener listener) {
        this.glideRequests = Glide.with(ctx);
        this.items    = items;
        this.isSelf   = isSelf;
        this.listener = listener;
    }

    /**
     * Instagram-style incremental update: instead of the caller building a
     * brand-new adapter + calling RecyclerView.setAdapter() on every reload
     * (which used to force a full teardown/rebind of every row, including
     * re-fetching covers that hadn't actually changed), diff against the
     * currently-bound list and dispatch minimal notify calls. Unaffected
     * rows are left untouched — their Glide requests/bitmaps aren't redone.
     */
    public void submitAlbums(List<HighlightAlbum> newItems) {
        List<HighlightAlbum> old = new ArrayList<>(items);
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return newItems.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                return Objects.equals(old.get(oldPos).albumId, newItems.get(newPos).albumId);
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                HighlightAlbum a = old.get(oldPos), b = newItems.get(newPos);
                return Objects.equals(a.albumName, b.albumName)
                        && Objects.equals(a.coverUrl, b.coverUrl)
                        && Objects.equals(a.coverBgColor, b.coverBgColor)
                        && Objects.equals(a.ringColor, b.ringColor)
                        && Objects.equals(a.ringMode, b.ringMode)
                        && a.itemCount == b.itemCount;
            }
        });
        items.clear();
        items.addAll(newItems);
        result.dispatchUpdatesTo(this);
    }

    // ── RecyclerView ───────────────────────────────────────────────────

    @NonNull
    @Override
    public HVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new HVH(buildItemView(parent.getContext()), parent.getContext());
    }

    @Override
    public void onBindViewHolder(@NonNull HVH h, int position) {
        HighlightAlbum album = items.get(position);

        if (album.isNew) {
            bindNewButton(h);
        } else {
            bindAlbum(h, album, position);
        }
    }

    @Override public int getItemCount() { return items.size(); }

    // ── View creation ─────────────────────────────────────────────────

    /**
     * Builds one item:
     *   LinearLayout (vertical, 80dp wide)
     *     FrameLayout (70dp × 70dp)          ← ring + cover
     *       View      ring_bg                ← gradient oval (full 70dp)
     *       FrameLayout cover_frame          ← 62dp, centred, clipped circle
     *         ImageView iv_cover
     *     TextView tvName (11sp, centred)
     */
    private LinearLayout buildItemView(Context ctx) {
        int dp4  = dp(ctx, 4);
        int dp70 = dp(ctx, 70);
        int dp62 = dp(ctx, 62);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp4, dp(ctx, 8), dp4, dp(ctx, 6));
        root.setLayoutParams(new RecyclerView.LayoutParams(
                dp(ctx, 80), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setClickable(true);
        root.setFocusable(true);
        root.setBackground(rippleBackground(ctx));

        // ── Ring frame ──────────────────────────────────────────────
        FrameLayout ringFrame = new FrameLayout(ctx);
        FrameLayout.LayoutParams rfLp = new FrameLayout.LayoutParams(dp70, dp70);
        rfLp.gravity = Gravity.CENTER_HORIZONTAL;
        ringFrame.setLayoutParams(rfLp);
        ringFrame.setTag("ring_frame");

        // Gradient ring (full 70dp oval)
        View ringBg = new View(ctx);
        ringBg.setLayoutParams(new FrameLayout.LayoutParams(dp70, dp70));
        ringBg.setTag("ring_bg");
        ringFrame.addView(ringBg);

        // White gap separator (66dp oval)
        View ringGap = new View(ctx);
        int dp66 = dp(ctx, 66);
        FrameLayout.LayoutParams gapLp = new FrameLayout.LayoutParams(dp66, dp66);
        gapLp.gravity = Gravity.CENTER;
        ringGap.setLayoutParams(gapLp);
        GradientDrawable gapBg = new GradientDrawable();
        gapBg.setShape(GradientDrawable.OVAL);
        gapBg.setColor(resolveSurfaceColor(ctx));
        ringGap.setBackground(gapBg);
        ringFrame.addView(ringGap);

        // Cover circle (62dp)
        FrameLayout coverFrame = new FrameLayout(ctx);
        FrameLayout.LayoutParams cfLp = new FrameLayout.LayoutParams(dp62, dp62);
        cfLp.gravity = Gravity.CENTER;
        coverFrame.setLayoutParams(cfLp);
        coverFrame.setTag("cover_frame");

        ImageView ivCover = new ImageView(ctx);
        ivCover.setLayoutParams(new FrameLayout.LayoutParams(dp62, dp62));
        ivCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivCover.setTag("iv_cover");
        coverFrame.addView(ivCover);
        ringFrame.addView(coverFrame);
        root.addView(ringFrame);

        // ── Name label ──────────────────────────────────────────────
        TextView tvName = new TextView(ctx);
        LinearLayout.LayoutParams tnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tnLp.topMargin = dp(ctx, 4);
        tvName.setLayoutParams(tnLp);
        tvName.setGravity(Gravity.CENTER);
        tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        // Was hardcoded #111111 (near-black) — invisible against a dark-mode
        // background since it never adapted with the theme. Resolve
        // ?attr/colorOnSurface instead, same pattern as resolveSurfaceColor()
        // below (white text in dark mode, dark text in light mode).
        tvName.setTextColor(resolveOnSurfaceColor(ctx));
        tvName.setSingleLine(true);
        tvName.setMaxWidth(dp(ctx, 76));
        tvName.setEllipsize(TextUtils.TruncateAt.END);
        tvName.setTag("tv_name");
        root.addView(tvName);

        return root;
    }

    // ── Bind helpers ──────────────────────────────────────────────────

    private void bindNewButton(HVH h) {
        // Ring → dashed gray (matches the dotted "+" circle in the target
        // design). Was bg_highlight_ring_seen — a *solid* gray stroke, not
        // dashed, so it never matched. Dashed <stroke> only renders on a
        // software layer (hardware layers ignore dashWidth/dashGap), so
        // force LAYER_TYPE_SOFTWARE on this view; bindAlbum() below runs
        // for every non-"New" item and doesn't touch layerType, so it's
        // reset back to the default (HARDWARE) whenever the view is
        // recycled into a real album.
        h.ringBg.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        h.ringBg.setBackground(ctx(h).getDrawable(R.drawable.bg_highlight_ring_new_dashed));

        // Cover → circle with "+". In dark mode the old #F0F0F0 circle stayed
        // a near-white blob (wrong — the request is for it to read as a dark
        // chip like the rest of dark-mode UI), so flip it: dark mode = black
        // circle + white plus icon, light mode = unchanged light circle +
        // dark plus icon.
        boolean isNightMode = (ctx(h).getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        GradientDrawable newBg = new GradientDrawable();
        newBg.setShape(GradientDrawable.OVAL);
        newBg.setColor(isNightMode ? Color.parseColor("#000000") : Color.parseColor("#F0F0F0"));
        h.ivCover.setBackground(newBg);
        h.ivCover.setImageResource(android.R.drawable.ic_input_add);
        h.ivCover.setColorFilter(isNightMode ? Color.parseColor("#FFFFFF") : Color.parseColor("#555555"));
        h.ivCover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        h.ivCover.setPadding(dp(ctx(h), 14), dp(ctx(h), 14), dp(ctx(h), 14), dp(ctx(h), 14));

        h.tvName.setText("New");
        h.tvName.setTypeface(null, Typeface.NORMAL);

        h.root.setOnClickListener(v -> { if (listener != null) listener.onNewClicked(); });
        h.root.setOnLongClickListener(null);
    }

    private void bindAlbum(HVH h, HighlightAlbum album, int position) {
        Context ctx = ctx(h);
        // Undo the software layer bindNewButton() forces for the dashed
        // ring — a recycled ViewHolder that was previously the "New"
        // button would otherwise keep rendering this view (and its Glide
        // cover image) on a software layer, which is slower and can look
        // slightly different than hardware-accelerated drawing.
        h.ringBg.setLayerType(View.LAYER_TYPE_NONE, null);

        // Ring color — user's custom color (solid or dominant-gradient) if the
        // album has one set (statusHighlightMeta/{uid}/{albumId}/ringColor+ringMode),
        // otherwise the default app-wide seamless gradient ring.
        // FIX: bg_highlight_ring_active.xml used Android's XML sweep <gradient>,
        // which only supports 3 stops (start/center/end) and does NOT loop back
        // cleanly — it leaves a visible seam where endColor meets startColor.
        // Swapped for the same seamless StoryRingGradientDrawable used app-wide
        // (home story row, reel comments, profile top ring) — palindrome color
        // stops blend back into themselves with zero seam.
        try {
            float density = ctx.getResources().getDisplayMetrics().density;
            if (album.ringColor != null && !album.ringColor.isEmpty()) {
                int customColor = safeColor(album.ringColor, "#DD2A7B");
                h.ringBg.setBackground(
                        com.callx.app.utils.HighlightRingDrawable.withStrokeDp(
                                customColor, album.ringMode, 4f, density));
            } else {
                h.ringBg.setBackground(
                        com.callx.app.utils.StoryRingGradientDrawable.withStrokeDp(4f, density));
            }
        } catch (Exception e) {
            h.ringBg.setBackgroundColor(Color.parseColor("#DD2A7B"));
        }
        // Reset cover padding/filter
        h.ivCover.setPadding(0, 0, 0, 0);
        h.ivCover.clearColorFilter();

        // Cover photo / fallback color — right-sized to the 62dp circle
        // (COVER_SIZE) instead of a full portrait frame, with a tiny blur-up
        // thumbnail shown while the real cover loads (Instagram-style).
        if (album.coverUrl != null && !album.coverUrl.isEmpty()) {
            GradientDrawable placeholder = oval(safeColor(album.coverBgColor, "#6C5CE7"));
            String coverUrl = CloudinaryUploader.deriveThumbUrl(album.coverUrl, COVER_SIZE, "webp");
            String blurUrl  = CloudinaryUploader.deriveThumbUrl(album.coverUrl, BLUR_COVER_SIZE, "webp");
            glideRequests
                 .load(coverUrl)
                 .thumbnail(glideRequests.load(blurUrl).apply(COVER_OPTIONS))
                 .apply(COVER_OPTIONS)
                 .placeholder(placeholder)
                 .error(placeholder)
                 .into(h.ivCover);
        } else {
            h.ivCover.setImageDrawable(null);
            h.ivCover.setBackground(oval(safeColor(album.coverBgColor, "#6C5CE7")));
        }

        // Make cover clip to circle
        GradientDrawable clipBg = new GradientDrawable();
        clipBg.setShape(GradientDrawable.OVAL);
        clipBg.setColor(Color.TRANSPARENT);

        h.tvName.setText(album.albumName != null ? album.albumName : album.albumId);
        h.tvName.setTypeface(null, Typeface.NORMAL);

        h.root.setOnClickListener(v -> { if (listener != null) listener.onAlbumClicked(album); });

        if (isSelf) {
            h.root.setOnLongClickListener(v -> {
                if (listener != null) listener.onAlbumLongPressed(album, position);
                return true;
            });
        } else {
            h.root.setOnLongClickListener(null);
        }
    }

    // ── Preload-ahead ─────────────────────────────────────────────────

    @Override
    public void onViewAttachedToWindow(@NonNull HVH holder) {
        super.onViewAttachedToWindow(holder);
        preloadAhead(holder.getAdapterPosition());
    }

    /** Warms Glide's disk cache for the next few highlight covers past fromPosition. */
    private void preloadAhead(int fromPosition) {
        if (fromPosition < 0) return;
        int end = Math.min(fromPosition + PRELOAD_AHEAD, items.size() - 1);
        for (int pos = fromPosition + 1; pos <= end; pos++) {
            HighlightAlbum album = items.get(pos);
            if (album == null || album.isNew || album.coverUrl == null || album.coverUrl.isEmpty()) continue;
            String preloadUrl = CloudinaryUploader.deriveThumbUrl(album.coverUrl, COVER_SIZE, "webp");
            glideRequests.load(preloadUrl).apply(COVER_OPTIONS).preload();
        }
    }

    // ── ViewHolder ────────────────────────────────────────────────────

    static class HVH extends RecyclerView.ViewHolder {
        final LinearLayout root;
        final View         ringBg;
        final ImageView    ivCover;
        final TextView     tvName;

        HVH(LinearLayout root, Context ctx) {
            super(root);
            this.root    = root;
            this.ringBg  = root.findViewWithTag("ring_bg");
            this.ivCover = root.findViewWithTag("iv_cover");
            this.tvName  = root.findViewWithTag("tv_name");
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private static Context ctx(RecyclerView.ViewHolder h) {
        return h.itemView.getContext();
    }

    private static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private static int safeColor(String hex, String fallback) {
        try { return Color.parseColor(hex); } catch (Exception e) {
            try { return Color.parseColor(fallback); } catch (Exception e2) { return 0xFF6C5CE7; }
        }
    }

    /**
     * Resolves ?attr/colorSurface from the current theme — white in light
     * mode, the app's dark surface color in dark mode (values-night/colors.xml
     * overrides colorSurface app-wide). This is the same approach already used
     * for the top-left profile avatar's ring gap (civ_border_color=
     * "?attr/colorSurface" in activity_user_reels.xml) — this ring's white
     * spacer was hardcoded to Color.WHITE instead, which is why it stayed
     * white even in dark mode. Falls back to white if the attribute can't be
     * resolved for any reason.
     */
    private static int resolveSurfaceColor(Context ctx) {
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            ctx.getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, tv, true);
            if (tv.resourceId != 0) return ctx.getResources().getColor(tv.resourceId, ctx.getTheme());
            return tv.data;
        } catch (Exception e) {
            return Color.WHITE;
        }
    }

    /** Resolves ?attr/colorOnSurface — the theme's default text color
     *  (dark in light mode, light in dark mode). See resolveSurfaceColor's
     *  javadoc above for why hardcoded literals break dark mode here. */
    private static int resolveOnSurfaceColor(Context ctx) {
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            ctx.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true);
            if (tv.resourceId != 0) return ctx.getResources().getColor(tv.resourceId, ctx.getTheme());
            return tv.data;
        } catch (Exception e) {
            return Color.parseColor("#111111");
        }
    }

    private static android.graphics.drawable.Drawable rippleBackground(Context ctx) {
        android.util.TypedValue tv = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        return ctx.getDrawable(tv.resourceId);
    }
}
