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
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.callx.app.reels.R;
import java.util.List;

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

    // ── Fields ─────────────────────────────────────────────────────────
    private final List<HighlightAlbum> items;
    private final boolean              isSelf;
    private final Listener             listener;

    public HighlightsRowAdapter(List<HighlightAlbum> items, boolean isSelf, Listener listener) {
        this.items    = items;
        this.isSelf   = isSelf;
        this.listener = listener;
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
        gapBg.setColor(Color.WHITE);
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
        tvName.setTextColor(Color.parseColor("#111111"));
        tvName.setSingleLine(true);
        tvName.setMaxWidth(dp(ctx, 76));
        tvName.setEllipsize(TextUtils.TruncateAt.END);
        tvName.setTag("tv_name");
        root.addView(tvName);

        return root;
    }

    // ── Bind helpers ──────────────────────────────────────────────────

    private void bindNewButton(HVH h) {
        // Ring → dashed gray
        h.ringBg.setBackground(ctx(h).getDrawable(R.drawable.bg_highlight_ring_seen));

        // Cover → light circle with "+"
        GradientDrawable newBg = new GradientDrawable();
        newBg.setShape(GradientDrawable.OVAL);
        newBg.setColor(Color.parseColor("#F0F0F0"));
        h.ivCover.setBackground(newBg);
        h.ivCover.setImageResource(android.R.drawable.ic_input_add);
        h.ivCover.setColorFilter(Color.parseColor("#555555"));
        h.ivCover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        h.ivCover.setPadding(dp(ctx(h), 14), dp(ctx(h), 14), dp(ctx(h), 14), dp(ctx(h), 14));

        h.tvName.setText("New");
        h.tvName.setTypeface(null, Typeface.NORMAL);

        h.root.setOnClickListener(v -> { if (listener != null) listener.onNewClicked(); });
        h.root.setOnLongClickListener(null);
    }

    private void bindAlbum(HVH h, HighlightAlbum album, int position) {
        Context ctx = ctx(h);

        // Ring color — gradient (all highlights show active ring for now)
        try {
            h.ringBg.setBackground(ctx.getDrawable(R.drawable.bg_highlight_ring_active));
        } catch (Exception e) {
            h.ringBg.setBackgroundColor(Color.parseColor("#DD2A7B"));
        }
        // Reset cover padding/filter
        h.ivCover.setPadding(0, 0, 0, 0);
        h.ivCover.clearColorFilter();

        // Cover photo / fallback color
        if (album.coverUrl != null && !album.coverUrl.isEmpty()) {
            GradientDrawable placeholder = oval(safeColor(album.coverBgColor, "#6C5CE7"));
            Glide.with(ctx)
                 .load(album.coverUrl)
                 .transform(new CircleCrop())
                 .placeholder(placeholder)
                 .error(placeholder)
                 .override(480, 853)
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

    private static android.graphics.drawable.Drawable rippleBackground(Context ctx) {
        android.util.TypedValue tv = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        return ctx.getDrawable(tv.resourceId);
    }
}
