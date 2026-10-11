package com.callx.app.interactions;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;
import com.callx.app.utils.StoryRingApplier;
import com.callx.app.cache.StoryRingRegistry;

/**
 * StatusCommentCanvasView — ONE custom View per public status comment (v515, perf item #12).
 *
 * Same pattern as the channel feed's ChannelPostCanvasView + ChannelPostLayoutPrewarmer:
 *  • zero child views — the avatar, name, time, body and "Reply"/"Delete" actions are painted on one Canvas
 *    (was LinearLayout > CircleImageView + 2 LinearLayouts + 5 TextViews per row);
 *  • the StaticLayouts (name, body) are built OFF the main thread by the sheet's bg parser ({@link #build}) and
 *    handed over inside the Row; onMeasure only reads cached heights. If the width differs from the prewarmed
 *    one (rotation, tablet) they're rebuilt lazily on the main thread;
 *  • all Paints live in a shared {@link Metrics} (one per sheet) — onDraw never allocates;
 *  • avatar = Glide CustomTarget<Drawable> (same request/cache key as an ImageView load, so it still shares the
 *    reels-comments cache entry) drawn through a cached circular BitmapShader.
 *
 * The inline reply box can't live inside a canvas, so the sheet shows it as its own list item right below
 * the row being replied to (one at a time → still created lazily).
 *
 * Trade-offs handled here by hand: tap hit-testing (avatar/name → profile, Reply, Delete, row → owner chat),
 * long-press, pressed ripple (owner rows), and TalkBack (content description + Reply/Delete actions).
 */
final class StatusCommentCanvasView extends View {

    interface Callbacks {
        void onProfile(StatusRepliesBottomSheet.Row r);
        void onStory(StatusRepliesBottomSheet.Row r);
        void onReply(StatusRepliesBottomSheet.Row r);
        void onDelete(StatusRepliesBottomSheet.Row r);
        void onRowClick(StatusRepliesBottomSheet.Row r);
        void onLongPress(StatusRepliesBottomSheet.Row r);
    }

    // ── shared, immutable-after-construction metrics + paints (one per sheet; one more copy for the bg thread) ──
    static final class Metrics {
        final float d;
        final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint time = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint action = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint divider = new Paint();
        final Paint avatarBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint avatarGlyph = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final int padV, avatarSz, ringSz, ringPad, avatarTop, colX, bodyGap, nameTimeGap, actTopPad, actBottomPad, actRightPad;
        final float replyW, deleteW, actTextH, actAscent;
        final int avatarPx;
        final RequestOptions avatarOpts;
        final AvatarSizeTier tier = AvatarSizeTier.TINY; // 32dp — same as reels comments

        Metrics(Context ctx, StatusRepliesBottomSheet.StyleHolder st) {
            d = ctx.getResources().getDisplayMetrics().density;
            name.setTextSize(13 * d);  name.setTypeface(st.medium);  name.setColor(st.primary);
            time.setTextSize(11 * d);  time.setColor(st.secondary);
            body.setTextSize(13 * d);  body.setColor(st.primary);
            action.setTextSize(12 * d); action.setTypeface(st.medium); action.setColor(st.secondary);
            divider.setColor(st.divider);
            avatarBg.setColor((st.secondary & 0x00FFFFFF) | 0x33000000);
            avatarGlyph.setTextSize(16 * d); avatarGlyph.setColor(st.secondary);
            avatarGlyph.setTextAlign(Paint.Align.CENTER);
            padV = Math.round(10 * d); avatarSz = Math.round(32 * d); avatarTop = Math.round(2 * d);
            ringPad = Math.round(3 * d); ringSz = avatarSz + 2 * ringPad; // story ring box around the 32dp avatar
            colX = Math.round((38 + 8) * d); bodyGap = Math.round(3 * d); nameTimeGap = Math.round(8 * d);
            actTopPad = Math.round(6 * d); actBottomPad = Math.round(2 * d); actRightPad = Math.round(16 * d);
            replyW = action.measureText("Reply");
            deleteW = action.measureText("Delete");
            Paint.FontMetrics fm = action.getFontMetrics();
            actAscent = -fm.ascent;
            actTextH = fm.descent - fm.ascent;
            avatarPx = AvatarUrlBuilder.tierPx(ctx, tier);
            avatarOpts = new RequestOptions()
                    .override(avatarPx, avatarPx)
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE);
        }
        int actionsHeight() { return Math.round(actTopPad + actTextH + actBottomPad); }
    }

    /** Pre-built text layouts for ONE row at ONE width. Immutable → safe to build on a bg thread. */
    static final class Built {
        final int width;
        final StaticLayout name, bodyLayout;
        final String timeText;
        final float nameLineW;
        final int bodyTop, columnBottom;
        Built(int width, StaticLayout name, StaticLayout body, String timeText, float nameLineW,
              int bodyTop, int columnBottom) {
            this.width = width; this.name = name; this.bodyLayout = body; this.timeText = timeText;
            this.nameLineW = nameLineW; this.bodyTop = bodyTop; this.columnBottom = columnBottom;
        }
    }

    /** Builds the row's layouts. Any thread (the bg parser calls it with its own Metrics copy). */
    static Built build(Metrics m, StatusRepliesBottomSheet.Row r, int widthPx) {
        final int contentW = Math.max(1, widthPx - m.colX);
        String timeText = "";
        float timeW = 0;
        if (r.ts > 0) {
            timeText = DateUtils.getRelativeTimeSpanString(r.ts, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
            timeW = m.time.measureText(timeText) + m.nameTimeGap;
        }
        String nm = r.name != null ? r.name : "Someone";
        int nameAvail = Math.max(1, (int) (contentW - timeW));
        StaticLayout nameL = StaticLayout.Builder.obtain(nm, 0, nm.length(), m.name, nameAvail)
                .setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        String tx = r.text != null ? r.text : "";
        StaticLayout bodyL = StaticLayout.Builder.obtain(tx, 0, tx.length(), m.body, contentW)
                .setIncludePad(false).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        float nameLineW = nameL.getLineCount() > 0 ? nameL.getLineWidth(0) : 0;
        int bodyTop = m.padV + nameL.getHeight() + m.bodyGap;
        return new Built(widthPx, nameL, bodyL, timeText, nameLineW, bodyTop, bodyTop + bodyL.getHeight());
    }

    // ── per-view state ─────────────────────────────────────────────────────
    private final Metrics m;
    private final Callbacks cb;
    private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix avatarMatrix = new Matrix();
    private final RectF tmpRect = new RectF();
    private final GestureDetector gestures;

    private StatusRepliesBottomSheet.Row row;
    private boolean canReply, canDelete, ownerClickable;
    private Built built;
    private int totalH, actTop;

    private int ringState = StoryRingApplier.STATE_NONE;
    private Drawable ringUnseen, ringSeen; // per-view instances (Drawable bounds are mutable state)

    private String avatarUrlTag;
    private CustomTarget<Drawable> avatarTarget;
    private Bitmap avatarBmp;
    private BitmapShader avatarShader;

    StatusCommentCanvasView(Context ctx, Metrics metrics, Callbacks callbacks, boolean ownerClickable) {
        super(ctx);
        this.m = metrics;
        this.cb = callbacks;
        this.ownerClickable = ownerClickable;
        setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        setClickable(true);
        setLongClickable(true);
        if (ownerClickable) { // pressed ripple only where a row tap does something
            android.util.TypedValue tv = new android.util.TypedValue();
            if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true))
                setBackgroundResource(tv.resourceId);
        }
        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(@NonNull MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(@NonNull MotionEvent e) { handleTap(e.getX(), e.getY()); return true; }
            @Override public void onLongPress(@NonNull MotionEvent e) {
                if (row != null) { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); cb.onLongPress(row); }
            }
        });
        ViewCompat.setAccessibilityDelegate(this, new AccessibilityDelegateCompat() {
            @Override public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (canReply) info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        0x7f0A0001, "Reply"));
                if (canDelete) info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        0x7f0A0002, "Delete"));
            }
            @Override public boolean performAccessibilityAction(@NonNull View host, int action, android.os.Bundle args) {
                if (row != null && action == 0x7f0A0001 && canReply) { cb.onReply(row); return true; }
                if (row != null && action == 0x7f0A0002 && canDelete) { cb.onDelete(row); return true; }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    void bind(StatusRepliesBottomSheet.Row r, boolean canReply, boolean canDelete) {
        final boolean sameContent = row != null && row.sameContent(r) && row.key.equals(r.key);
        row = r;
        this.canReply = canReply;
        this.canDelete = canDelete;
        if (!sameContent || built == null) {
            built = (r.built != null) ? r.built : null; // prewarmed (may be null → lazy in onMeasure)
        }
        setContentDescription((r.name != null ? r.name : "Someone") + ": " + (r.text != null ? r.text : ""));
        bindAvatar(r.avatarUrl);
        refreshRing();
        // Live propagation: marking a story seen anywhere repaints this ring (same registry as all other screens).
        final java.lang.ref.WeakReference<StatusCommentCanvasView> self = new java.lang.ref.WeakReference<>(this);
        StoryRingRegistry.register(getContext().getApplicationContext(), this, () -> { // weak: registry value must not pin this view
            StatusCommentCanvasView v = self.get();
            if (v != null) v.refreshRing();
        });
        requestLayout();
        invalidate();
    }

    private void bindAvatar(String url) {
        final String tag = url == null ? "" : url;
        if (tag.equals(avatarUrlTag)) return; // unchanged rebind → keep the bitmap, no new request
        clearAvatar();
        avatarUrlTag = tag;
        if (tag.isEmpty()) return;
        final CustomTarget<Drawable> t = new CustomTarget<Drawable>(m.avatarPx, m.avatarPx) {
            @Override public void onResourceReady(@NonNull Drawable res, @Nullable Transition<? super Drawable> tr) {
                if (res instanceof BitmapDrawable && tag.equals(avatarUrlTag)) {
                    avatarBmp = ((BitmapDrawable) res).getBitmap();
                    avatarShader = new BitmapShader(avatarBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    float s = Math.max((float) m.avatarSz / avatarBmp.getWidth(), (float) m.avatarSz / avatarBmp.getHeight());
                    avatarMatrix.setScale(s, s);
                    avatarMatrix.postTranslate((m.avatarSz - avatarBmp.getWidth() * s) / 2f,
                            (m.avatarSz - avatarBmp.getHeight() * s) / 2f);
                    avatarShader.setLocalMatrix(avatarMatrix);
                    avatarPaint.setShader(avatarShader);
                    invalidate();
                }
            }
            @Override public void onLoadCleared(@Nullable Drawable placeholder) { dropBitmap(); }
        };
        avatarTarget = t;
        Glide.with(getContext())
                .load(AvatarUrlBuilder.buildResponsive(getContext(), tag, m.tier))
                .apply(m.avatarOpts)
                .into(t);
    }

    /** Re-reads the 3-state ring (gradient / seen / none) from StatusCacheManager; cheap map lookups. */
    private void refreshRing() {
        final int st = row != null ? StoryRingApplier.stateOf(getContext(), row.uid) : StoryRingApplier.STATE_NONE;
        if (st == ringState) return;
        ringState = st;
        postInvalidate();
    }

    private Drawable ringFor(int st) {
        if (st == StoryRingApplier.STATE_UNSEEN) {
            if (ringUnseen == null) ringUnseen = StoryRingApplier.ringDrawable(getContext(), st);
            return ringUnseen;
        }
        if (st == StoryRingApplier.STATE_SEEN) {
            if (ringSeen == null) ringSeen = StoryRingApplier.ringDrawable(getContext(), st);
            return ringSeen;
        }
        return null;
    }

    private void dropBitmap() { avatarBmp = null; avatarShader = null; avatarPaint.setShader(null); invalidate(); }

    private void clearAvatar() {
        if (avatarTarget != null) { try { Glide.with(getContext()).clear(avatarTarget); } catch (Exception ignored) {} }
        avatarTarget = null;
        avatarUrlTag = null;
        dropBitmap();
    }

    /** Call from onViewRecycled. */
    void release() { clearAvatar(); row = null; built = null; ringState = StoryRingApplier.STATE_NONE; }

    StatusRepliesBottomSheet.Row boundRow() { return row; }

    // ── measure / draw ──────────────────────────────────────────────────────────
    @Override protected void onMeasure(int wSpec, int hSpec) {
        final int w = MeasureSpec.getSize(wSpec);
        if (row == null || w <= 0) { setMeasuredDimension(w, 0); return; }
        if (built == null || built.width != w) {
            built = (row.built != null && row.built.width == w) ? row.built : build(m, row, w);
        }
        final boolean actions = canReply || canDelete;
        actTop = built.columnBottom;
        int colBottom = built.columnBottom + (actions ? m.actionsHeight() : 0);
        int contentBottom = Math.max(colBottom, m.avatarTop + m.ringSz);
        totalH = contentBottom + m.padV + 1;
        setMeasuredDimension(w, totalH);
    }

    @Override protected void onDraw(@NonNull Canvas c) {
        final Built b = built;
        if (row == null || b == null) return;
        // avatar
        final float r = m.avatarSz / 2f;
        final Drawable ring = ringFor(ringState);
        if (ring != null) {
            c.save();
            c.translate(0, m.padV + m.avatarTop);
            ring.setBounds(0, 0, m.ringSz, m.ringSz);
            ring.draw(c);
            c.restore();
        }
        c.save();
        c.translate(m.ringPad, m.padV + m.avatarTop + m.ringPad);
        if (avatarBmp != null && avatarShader != null) {
            c.drawCircle(r, r, r, avatarPaint);
        } else {
            c.drawCircle(r, r, r, m.avatarBg);
            c.drawText("👤", r, r - (m.avatarGlyph.ascent() + m.avatarGlyph.descent()) / 2f, m.avatarGlyph);
        }
        c.restore();
        // name + time (same baseline)
        c.save();
        c.translate(m.colX, m.padV);
        b.name.draw(c);
        c.restore();
        if (!b.timeText.isEmpty()) {
            float baseline = m.padV + b.name.getLineBaseline(0);
            c.drawText(b.timeText, m.colX + b.nameLineW + m.nameTimeGap, baseline, m.time);
        }
        // body
        c.save();
        c.translate(m.colX, b.bodyTop);
        b.bodyLayout.draw(c);
        c.restore();
        // actions
        if (canReply || canDelete) {
            float x = m.colX;
            float y = actTop + m.actTopPad + m.actAscent;
            if (canReply) { c.drawText("Reply", x, y, m.action); x += m.replyW + m.actRightPad; }
            if (canDelete) c.drawText("Delete", x, y, m.action);
        }
        // divider
        c.drawRect(0, totalH - 1, getWidth(), totalH, m.divider);
    }

    // ── touch ─────────────────────────────────────────────────────────────────────
    @Override public boolean onTouchEvent(MotionEvent e) {
        final int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN && ownerClickable) { drawableHotspotChanged(e.getX(), e.getY()); setPressed(true); }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) setPressed(false);
        return gestures.onTouchEvent(e);
    }

    private void handleTap(float x, float y) {
        final Built b = built;
        if (row == null || b == null) return;
        // avatar + name line → profile
        // avatar/ring: story viewer when there is a story (like every other avatar list), else profile
        if (x < m.colX && y < m.padV + m.avatarTop + m.ringSz) {
            if (ringState != StoryRingApplier.STATE_NONE) cb.onStory(row); else cb.onProfile(row);
            return;
        }
        if (x < m.colX + b.nameLineW && y < b.bodyTop - m.bodyGap) { cb.onProfile(row); return; }
        if (canReply || canDelete) {
            float left = m.colX;
            if (canReply) {
                float right = left + m.replyW + m.actRightPad;
                if (inActionRow(x, y, left, right)) { cb.onReply(row); return; }
                left = right;
            }
            if (canDelete && inActionRow(x, y, left, left + m.deleteW + m.actRightPad)) { cb.onDelete(row); return; }
        }
        if (ownerClickable) cb.onRowClick(row);
    }

    private boolean inActionRow(float x, float y, float left, float right) {
        return x >= left && x <= right && y >= actTop && y <= actTop + m.actionsHeight();
    }
}
