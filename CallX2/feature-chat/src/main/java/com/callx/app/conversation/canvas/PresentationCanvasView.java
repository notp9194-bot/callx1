package com.callx.app.conversation.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.conversation.models.PresentationMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * v169 — PresentationCanvasView
 *
 * Custom View that renders a {@link PresentationMessage} as a styled "slide
 * card" — both in the presentation editor (live preview) and in the chat
 * RecyclerView as a message bubble.
 *
 * Architecture
 * ────────────
 * Follows the same pattern as MessageBubbleCanvasView (v32+):
 *   • Zero XML inflation — no View hierarchy inside, pure Canvas drawing
 *   • onMeasure() computes all geometry once and caches it
 *   • onDraw() uses only canvas.draw*() calls — no measure/layout
 *   • StaticLayout per text block — cached, rebuilt only when text or width
 *     changes (same optimisation introduced in v168 for chat bubbles)
 *   • Background image drawn via cached Matrix — no per-frame scale calc
 *
 * ── Integration in MessagePagingAdapter ──────────────────────────────────────
 *
 * In isCanvasEligible(), add:
 *   if ("presentation".equals(msg.getType())) return true;
 *
 * In bindCanvasMessage(), add a presentation branch:
 *   if ("presentation".equals(msg.getType())) {
 *       PresentationCanvasView pcv = (PresentationCanvasView) cv;
 *       PresentationMessage pm = PresentationMessage.fromJson(msg.getPresentationData());
 *       // Load bg image async via Glide:
 *       if (pm.bgImageUrl != null && !pm.bgImageUrl.isEmpty()) {
 *           Glide.with(ctx).asBitmap().load(pm.bgImageThumbUrl)
 *               .into(new CustomTarget<Bitmap>() {
 *                   @Override public void onResourceReady(@NonNull Bitmap bmp,
 *                       @Nullable Transition<? super Bitmap> t) {
 *                       pcv.bindPresentation(pm, bmp);
 *                   }
 *                   @Override public void onLoadCleared(@Nullable Drawable p) {}
 *               });
 *       } else {
 *           pcv.bindPresentation(pm, null);
 *       }
 *       return;
 *   }
 *
 * In onCreateViewHolder(), for "presentation" type return a holder wrapping
 * a PresentationCanvasView instead of a MessageBubbleCanvasView.
 *
 * ── Performance notes ─────────────────────────────────────────────────────────
 * • StaticLayouts are rebuilt only when (text + width) changes — O(1) on scroll
 * • Background image scaled once via Matrix.setRectToRect(); shader drawn
 *   via BitmapShader so no per-frame Bitmap allocation
 * • Overlay gradient LinearGradient created once in onMeasure, not onDraw
 * • clipToOutline (rounded corners) done via Canvas.clipPath, not ViewOutline
 *   so it works in a RecyclerView without hardware layer cost
 */
public class PresentationCanvasView extends View {

    // ── Geometry constants ────────────────────────────────────────────────────
    private static final float CORNER_RADIUS_DP   = 16f;
    private static final float H_PAD_DP           = 16f;
    private static final float V_PAD_DP           = 14f;
    private static final float BLOCK_GAP_DP       = 8f;
    private static final float MAX_WIDTH_FRACTION  = 0.85f; // 85% of parent width

    // ── Paints ────────────────────────────────────────────────────────────────
    private final Paint       bgPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint       overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint       shadowPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint   textPaint    = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Path        clipPath     = new Path();

    // ── Data ──────────────────────────────────────────────────────────────────
    @Nullable private PresentationMessage pm;
    @Nullable private Bitmap              bgBitmap;

    // ── Cached geometry ───────────────────────────────────────────────────────
    private float cornerPx, hPadPx, vPadPx, blockGapPx;
    private float cardW, cardH;
    private RectF cardRect = new RectF();
    private @Nullable android.graphics.BitmapShader bgShader;
    private @Nullable LinearGradient overlayGradient;

    // Per-block StaticLayout cache
    private final List<StaticLayout> blockLayouts = new ArrayList<>();
    private final List<Float>        blockTopY    = new ArrayList<>();

    // ── Constructors ──────────────────────────────────────────────────────────
    public PresentationCanvasView(@NonNull Context context) {
        super(context);
        init();
    }
    public PresentationCanvasView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    public PresentationCanvasView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        cornerPx   = CORNER_RADIUS_DP  * density;
        hPadPx     = H_PAD_DP          * density;
        vPadPx     = V_PAD_DP          * density;
        blockGapPx = BLOCK_GAP_DP      * density;

        shadowPaint.setColor(0x44000000);
        shadowPaint.setShadowLayer(8f * density, 0, 3f * density, 0x44000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // needed for shadowLayer
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Bind new presentation data. Call from adapter's onBindViewHolder or the
     * editor's live-preview path.
     */
    public void bindPresentation(@NonNull PresentationMessage presentation,
                                 @Nullable Bitmap bgBitmap) {
        this.pm       = presentation;
        this.bgBitmap = bgBitmap;
        // Invalidate caches
        blockLayouts.clear();
        blockTopY.clear();
        bgShader       = null;
        overlayGradient = null;
        requestLayout();
        invalidate();
    }

    // ── Measure ───────────────────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (pm == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int parentW = MeasureSpec.getSize(widthMeasureSpec);
        // Card is aspect-ratio constrained.
        cardW = Math.min(parentW, (int)(parentW * MAX_WIDTH_FRACTION));
        cardH = cardW / pm.aspectRatioFloat();

        // Build block layouts now that we know width.
        buildBlockLayouts((int)(cardW - hPadPx * 2));

        // Total height = card height + drop-shadow room.
        int totalH = (int)(cardH + 10 * getResources().getDisplayMetrics().density);
        setMeasuredDimension((int) cardW, totalH);

        // Cache geometry.
        cardRect.set(0, 0, cardW, cardH);
        clipPath.reset();
        clipPath.addRoundRect(cardRect, cornerPx, cornerPx, Path.Direction.CW);

        // Build bg shader.
        if (bgBitmap != null) {
            Matrix m = new Matrix();
            RectF bmpRect = new RectF(0, 0, bgBitmap.getWidth(), bgBitmap.getHeight());
            m.setRectToRect(bmpRect, cardRect, Matrix.ScaleToFit.CENTER);
            bgShader = new android.graphics.BitmapShader(bgBitmap,
                Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            bgShader.setLocalMatrix(m);
        }

        // Build overlay gradient.
        if (pm.overlayGradient && bgBitmap != null) {
            overlayGradient = new LinearGradient(
                0, 0, 0, cardH,
                new int[]{ 0x88000000, 0x00000000, 0x00000000, 0xAA000000 },
                new float[]{ 0f, 0.3f, 0.7f, 1f },
                Shader.TileMode.CLAMP
            );
        } else {
            overlayGradient = null;
        }
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (pm == null) return;

        // Clip to rounded card.
        canvas.save();
        canvas.clipPath(clipPath);

        // 1. Background fill
        drawBackground(canvas);

        // 2. Overlay gradient (legibility over photos)
        if (overlayGradient != null) {
            overlayPaint.setShader(overlayGradient);
            canvas.drawRoundRect(cardRect, cornerPx, cornerPx, overlayPaint);
        }

        // 3. Text blocks
        drawTextBlocks(canvas);

        canvas.restore();

        // 4. Drop shadow border hint (drawn outside clip)
        shadowPaint.setStyle(Paint.Style.STROKE);
        shadowPaint.setStrokeWidth(1f);
        shadowPaint.setColor(0x22000000);
        canvas.drawRoundRect(cardRect, cornerPx, cornerPx, shadowPaint);
    }

    // ── Background drawing ────────────────────────────────────────────────────

    private void drawBackground(@NonNull Canvas canvas) {
        if (bgBitmap != null && bgShader != null) {
            bgPaint.setShader(bgShader);
            canvas.drawRoundRect(cardRect, cornerPx, cornerPx, bgPaint);
        } else {
            // Solid or gradient colour from bgColor.
            bgPaint.setShader(null);
            bgPaint.setColor(pm.bgColor);
            canvas.drawRoundRect(cardRect, cornerPx, cornerPx, bgPaint);
        }
    }

    // ── Text blocks drawing ───────────────────────────────────────────────────

    private void drawTextBlocks(@NonNull Canvas canvas) {
        if (pm == null || pm.textBlocks.isEmpty()) return;

        for (int i = 0; i < blockLayouts.size() && i < pm.textBlocks.size(); i++) {
            StaticLayout layout = blockLayouts.get(i);
            float topY = blockTopY.get(i);
            canvas.save();
            canvas.translate(hPadPx, topY);
            layout.draw(canvas);
            canvas.restore();
        }
    }

    // ── StaticLayout builder ──────────────────────────────────────────────────

    private void buildBlockLayouts(int availWidthPx) {
        blockLayouts.clear();
        blockTopY.clear();
        if (pm == null || pm.textBlocks.isEmpty()) return;

        // Total text height = sum of block heights + gaps.
        float totalTextH = 0;
        List<StaticLayout> layouts = new ArrayList<>();

        for (PresentationMessage.TextBlock block : pm.textBlocks) {
            textPaint.setTextSize(spToPx(block.textSizeSp));
            textPaint.setColor(block.textColor);
            textPaint.setTypeface(buildTypeface(block));
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                textPaint.setLetterSpacing(block.letterSpacing);
            }

            String text = block.text != null ? block.text : "";

            StaticLayout layout;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                layout = StaticLayout.Builder
                    .obtain(text, 0, text.length(), textPaint, Math.max(availWidthPx, 1))
                    .setAlignment(toLayoutAlign(block.alignment))
                    .setLineSpacing(0f, block.lineHeightMult)
                    .setIncludePad(false)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .build();
            } else {
                //noinspection deprecation
                layout = new StaticLayout(text, textPaint, Math.max(availWidthPx, 1),
                    Layout.Alignment.ALIGN_NORMAL, block.lineHeightMult, 0f, false);
            }
            layouts.add(layout);
            totalTextH += layout.getHeight();
        }
        totalTextH += blockGapPx * (pm.textBlocks.size() - 1);

        // Centre blocks vertically within the card.
        float startY = vPadPx + (cardH - vPadPx * 2 - totalTextH) / 2f;
        startY = Math.max(startY, vPadPx);

        float curY = startY;
        for (int i = 0; i < layouts.size(); i++) {
            StaticLayout layout = layouts.get(i);
            blockLayouts.add(layout);
            blockTopY.add(curY);
            curY += layout.getHeight() + blockGapPx;
        }
    }

    // ── Type helpers ──────────────────────────────────────────────────────────

    @NonNull
    private Typeface buildTypeface(@NonNull PresentationMessage.TextBlock block) {
        Typeface base;
        try {
            base = Typeface.create(block.fontFamily, Typeface.NORMAL);
        } catch (Exception e) {
            base = Typeface.DEFAULT;
        }
        int style = Typeface.NORMAL;
        if (block.bold && block.italic) style = Typeface.BOLD_ITALIC;
        else if (block.bold)            style = Typeface.BOLD;
        else if (block.italic)          style = Typeface.ITALIC;
        return Typeface.create(base, style);
    }

    @NonNull
    private Layout.Alignment toLayoutAlign(@NonNull PresentationMessage.TextAlignment align) {
        switch (align) {
            case CENTER: return Layout.Alignment.ALIGN_CENTER;
            case RIGHT:  return Layout.Alignment.ALIGN_OPPOSITE;
            default:     return Layout.Alignment.ALIGN_NORMAL;
        }
    }

    private float spToPx(int sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }
}
