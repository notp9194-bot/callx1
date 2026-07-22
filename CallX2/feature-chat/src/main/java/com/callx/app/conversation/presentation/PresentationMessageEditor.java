package com.callx.app.conversation.presentation;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.conversation.AdvancedRichTextController;
import com.callx.app.conversation.models.PresentationMessage;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v169 — PresentationMessageEditor
 *
 * Full-screen slide editor shown when the user taps "Presentation" in the
 * long-message expanded input bar.  Provides:
 *
 *   • Background system:
 *       — 20 solid-colour presets (gradient gallery, matching WhatsApp)
 *       — "Photo from gallery" → MediaStore image picker
 *       — Optional dark-overlay gradient for legibility over photos
 *       — Aspect-ratio toggle: 16:9 · 1:1 · 9:16
 *
 *   • Per-block text editing:
 *       — Title block (large, centred by default)
 *       — Body block (normal, left-aligned)
 *       — Caption block (small, italic, centred)
 *       — Tap any block → opens AdvancedRichTextController toolbar for that block
 *       — Drag handle → reorder blocks
 *
 *   • Live preview:
 *       — The card preview in the editor is drawn by a PresentationCanvasView
 *         so the user sees exactly what the bubble will look like in chat
 *
 *   • Send path:
 *       — "Send" button calls PresentationSendCallback.onSend(PresentationMessage)
 *       — Caller handles upload (bgImage → Cloudinary) + Firebase write
 *       — Message type = "presentation"; adapter routes to PresentationCanvasView
 *
 * ── Integration in ChatActivity / ConversationFragment ────────────────────────
 *
 *   PresentationMessageEditor editor = new PresentationMessageEditor(
 *       context, rootDecorView, fragmentManager,
 *       pm -> {
 *           // pm is the fully built PresentationMessage
 *           chatMediaController.uploadAndSendPresentation(pm);
 *       }
 *   );
 *
 *   // Show from "Presentation" button inside expanded input bar:
 *   editor.show();
 *
 *   // In onActivityResult for image picker (requestCode = REQ_PICK_BG_IMAGE):
 *   if (requestCode == PresentationMessageEditor.REQ_PICK_BG_IMAGE && resultCode == RESULT_OK) {
 *       editor.onBgImagePicked(data.getData());
 *   }
 */
public class PresentationMessageEditor {

    public static final int REQ_PICK_BG_IMAGE = 7741;

    // ── Preset bg colours (gradient pairs) ───────────────────────────────────
    private static final int[][] BG_GRADIENTS = {
        { 0xFF1A1A2E, 0xFF16213E },  // Dark Navy
        { 0xFF0F3460, 0xFF533483 },  // Royal Blue-Purple
        { 0xFF2D6A4F, 0xFF1B4332 },  // Forest Green
        { 0xFF7B2D8B, 0xFF9B2226 },  // Purple-Crimson
        { 0xFFE63946, 0xFFFF6B6B },  // Red-Coral
        { 0xFFFF9F1C, 0xFFFF6D00 },  // Amber-Orange
        { 0xFF06D6A0, 0xFF118AB2 },  // Mint-Teal
        { 0xFF3A86FF, 0xFF6A4C93 },  // Blue-Indigo
        { 0xFF8338EC, 0xFFFF006E },  // Violet-Pink
        { 0xFFFB5607, 0xFFFF006E },  // Orange-Pink
        { 0xFF2EC4B6, 0xFFCBF3F0 },  // Aqua-Light
        { 0xFF264653, 0xFF2A9D8F },  // Dark Teal-Medium Teal
        { 0xFFE9C46A, 0xFFF4A261 },  // Gold-Sand
        { 0xFF023E8A, 0xFF0096C7 },  // Deep-Sky Blue
        { 0xFF370617, 0xFF6A040F },  // Dark Red
        { 0xFF4A4E69, 0xFF9A8C98 },  // Slate-Mauve
        { 0xFFF8F9FA, 0xFFDEE2E6 },  // Light Gray (LIGHT theme)
        { 0xFFFFFFFF, 0xFFF0F0F0 },  // White (LIGHT theme)
        { 0xFF111111, 0xFF222222 },  // Near-Black
        { 0xFF1C1C1E, 0xFF2C2C2E },  // iOS Dark
    };

    // ── Callbacks ─────────────────────────────────────────────────────────────
    public interface PresentationSendCallback {
        void onSend(@NonNull PresentationMessage presentation);
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final PresentationMessage pm = new PresentationMessage();
    private int activeBlockIndex = 0;

    // ── Views ─────────────────────────────────────────────────────────────────
    private final Context context;
    private final ViewGroup rootView;        // activity DecorView or root FrameLayout
    private final Object    fragmentManager; // FragmentManager (Object to avoid import)
    private final PresentationSendCallback sendCallback;

    // The editor overlay itself
    private @Nullable View editorView;
    private @Nullable com.callx.app.conversation.canvas.PresentationCanvasView previewCanvas;

    // Per-block EditTexts (up to 3 blocks: title, body, caption)
    private final List<EditText> blockEditors = new ArrayList<>();

    // Background image bitmap (local, before upload)
    private @Nullable Bitmap pendingBgBitmap;
    private @Nullable Uri    pendingBgUri;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Constructor ───────────────────────────────────────────────────────────
    public PresentationMessageEditor(@NonNull Context context,
                                     @NonNull ViewGroup rootView,
                                     @Nullable Object fragmentManager,
                                     @NonNull PresentationSendCallback sendCallback) {
        this.context         = context;
        this.rootView        = rootView;
        this.fragmentManager = fragmentManager;
        this.sendCallback    = sendCallback;

        // Default: one body block
        pm.textBlocks.add(PresentationMessage.TextBlock.title("Title"));
        pm.textBlocks.add(PresentationMessage.TextBlock.body("Your message here…"));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Slide editor ko screen pe show karo. Dismiss = back button or X button. */
    public void show() {
        if (editorView != null) return; // already shown

        editorView = buildEditorView();

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        editorView.setAlpha(0f);
        editorView.setTranslationY(dp(40));
        rootView.addView(editorView, lp);

        editorView.animate()
                .alpha(1f).translationY(0f)
                .setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
    }

    /** Dismiss editor without sending. */
    public void dismiss() {
        if (editorView == null) return;
        View v = editorView;
        v.animate()
            .alpha(0f).translationY(dp(40))
            .setDuration(200)
            .withEndAction(() -> {
                rootView.removeView(v);
                editorView = null;
            }).start();
    }

    /**
     * Called from host onActivityResult when the user picks a background image.
     * @param uri  content URI from the image picker
     */
    public void onBgImagePicked(@NonNull Uri uri) {
        pendingBgUri = uri;
        bgExecutor.execute(() -> {
            try {
                InputStream is = context.getContentResolver().openInputStream(uri);
                if (is == null) return;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2; // decode at half size — enough for preview + upload
                Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                is.close();
                if (bmp == null) return;
                pendingBgBitmap = bmp;
                pm.bgImageUrl = uri.toString(); // placeholder until Cloudinary URL
                mainHandler.post(this::refreshPreview);
            } catch (Exception e) {
                mainHandler.post(() ->
                    Toast.makeText(context, "Could not load image", Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ── Editor layout builder ─────────────────────────────────────────────────

    @NonNull
    private View buildEditorView() {
        // Root full-screen frame
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xF5121212); // near-black translucent curtain

        // ── Top bar ──
        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(48), dp(12), dp(8));

        ImageButton btnClose = new ImageButton(context);
        btnClose.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        btnClose.setBackground(null);
        btnClose.setOnClickListener(v -> dismiss());
        topBar.addView(btnClose, dp(40), dp(40));

        TextView tvTitle = new TextView(context);
        tvTitle.setText("Presentation");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(17f);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvLp.setMarginStart(dp(12));
        topBar.addView(tvTitle, tvLp);

        TextView btnSend = new TextView(context);
        btnSend.setText("Send  ➤");
        btnSend.setTextColor(Color.WHITE);
        btnSend.setBackgroundColor(0xFF1976D2);
        btnSend.setPadding(dp(16), dp(10), dp(16), dp(10));
        btnSend.setTextSize(13f);
        GradientDrawable sendBg = new GradientDrawable();
        sendBg.setColor(0xFF1976D2);
        sendBg.setCornerRadius(dp(20));
        btnSend.setBackground(sendBg);
        btnSend.setOnClickListener(v -> handleSend());
        topBar.addView(btnSend);

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(topBar, topLp);

        // ── Centre: preview canvas ──
        previewCanvas = new com.callx.app.conversation.canvas.PresentationCanvasView(context);
        previewCanvas.bindPresentation(pm, pendingBgBitmap);
        int previewH = (int)(screenWidth() / pm.aspectRatioFloat());
        FrameLayout.LayoutParams cvLp = new FrameLayout.LayoutParams(
                (int)(screenWidth() * 0.88f), (int)(screenWidth() * 0.88f / pm.aspectRatioFloat()));
        cvLp.gravity = Gravity.CENTER;
        cvLp.topMargin = dp(100);
        root.addView(previewCanvas, cvLp);

        // ── Block editor list (below preview) ──
        LinearLayout blockContainer = new LinearLayout(context);
        blockContainer.setOrientation(LinearLayout.VERTICAL);
        blockContainer.setPadding(dp(16), dp(8), dp(16), dp(8));

        for (int i = 0; i < pm.textBlocks.size(); i++) {
            blockContainer.addView(buildBlockEditor(i));
        }

        // Add-block button
        TextView btnAddBlock = new TextView(context);
        btnAddBlock.setText("+ Add text block");
        btnAddBlock.setTextColor(0xFF90CAF9);
        btnAddBlock.setTextSize(13f);
        btnAddBlock.setPadding(0, dp(12), 0, dp(8));
        final int[] blockIdx = { pm.textBlocks.size() };
        btnAddBlock.setOnClickListener(v -> {
            if (pm.textBlocks.size() >= 5) {
                Toast.makeText(context, "Max 5 text blocks", Toast.LENGTH_SHORT).show();
                return;
            }
            PresentationMessage.TextBlock newBlock = PresentationMessage.TextBlock.body("");
            pm.textBlocks.add(newBlock);
            blockContainer.addView(buildBlockEditor(pm.textBlocks.size() - 1),
                                   blockContainer.getChildCount() - 1);
            refreshPreview();
        });
        blockContainer.addView(btnAddBlock);

        // Aspect ratio row
        blockContainer.addView(buildAspectRatioRow());
        // Background picker row
        blockContainer.addView(buildBgPickerRow());
        // Overlay toggle
        blockContainer.addView(buildOverlayToggleRow());

        // Scrollable container for block editors + controls
        androidx.core.widget.NestedScrollView scrollView =
            new androidx.core.widget.NestedScrollView(context);
        scrollView.addView(blockContainer);

        FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        scrollLp.topMargin = dp(100) + previewH + dp(16);
        root.addView(scrollView, scrollLp);

        return root;
    }

    @NonNull
    private View buildBlockEditor(int index) {
        PresentationMessage.TextBlock block = pm.textBlocks.get(index);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF1E1E1E);
        cardBg.setCornerRadius(dp(10));
        card.setBackground(cardBg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        card.setLayoutParams(lp);

        // Role label
        TextView roleLabel = new TextView(context);
        roleLabel.setText(blockRoleLabel(index, block.role));
        roleLabel.setTextColor(0xFF90CAF9);
        roleLabel.setTextSize(11f);
        card.addView(roleLabel);

        // EditText for this block
        EditText et = new EditText(context);
        et.setText(block.text);
        et.setHint(block.role == PresentationMessage.TextRole.TITLE
                   ? "Slide title…"
                   : block.role == PresentationMessage.TextRole.CAPTION
                   ? "Caption…"
                   : "Message body…");
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(0xFF666666);
        et.setTextSize(index == 0 ? 18f : 13f);
        et.setBackgroundColor(Color.TRANSPARENT);
        et.setMaxLines(5);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                block.text = s.toString();
                mainHandler.removeCallbacksAndMessages("preview");
                mainHandler.postDelayed(() -> refreshPreview(), 120);
            }
        });
        blockEditors.add(et);
        card.addView(et, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        // Inline mini-toolbar for this block (text color, size, align)
        card.addView(buildMiniToolbar(index, block));

        return card;
    }

    @NonNull
    private View buildMiniToolbar(int index, PresentationMessage.TextBlock block) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, dp(6), 0, 0);

        // Color dot
        View colorDot = new View(context);
        colorDot.setBackgroundColor(block.textColor);
        colorDot.setOnClickListener(v -> showBlockColorPicker(index, block, colorDot));
        bar.addView(colorDot, dp(24), dp(24));

        spacer(bar, 8);

        // Size
        TextView sizeBtn = new TextView(context);
        sizeBtn.setText(block.textSizeSp + "sp");
        sizeBtn.setTextColor(0xFFAAAAAA);
        sizeBtn.setTextSize(11f);
        sizeBtn.setOnClickListener(v ->
            new AdvancedRichTextController.TextSizePopup(context, block.textSizeSp, sp -> {
                block.textSizeSp = sp;
                sizeBtn.setText(sp + "sp");
                refreshPreview();
            }).showAsDropDown(sizeBtn, 0, 4)
        );
        bar.addView(sizeBtn);

        spacer(bar, 8);

        // Align
        TextView alignBtn = new TextView(context);
        alignBtn.setText(alignLabel(block.alignment));
        alignBtn.setTextColor(0xFFAAAAAA);
        alignBtn.setTextSize(11f);
        alignBtn.setOnClickListener(v -> {
            PresentationMessage.TextAlignment[] aligns = PresentationMessage.TextAlignment.values();
            block.alignment = aligns[(block.alignment.ordinal() + 1) % aligns.length];
            alignBtn.setText(alignLabel(block.alignment));
            refreshPreview();
        });
        bar.addView(alignBtn);

        spacer(bar, 8);

        // Bold toggle
        TextView boldBtn = new TextView(context);
        boldBtn.setText("B");
        boldBtn.setTextSize(13f);
        boldBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        boldBtn.setTextColor(block.bold ? 0xFF90CAF9 : 0xFF666666);
        boldBtn.setOnClickListener(v -> {
            block.bold = !block.bold;
            boldBtn.setTextColor(block.bold ? 0xFF90CAF9 : 0xFF666666);
            refreshPreview();
        });
        bar.addView(boldBtn);

        spacer(bar, 8);

        // Italic toggle
        TextView italicBtn = new TextView(context);
        italicBtn.setText("I");
        italicBtn.setTextSize(13f);
        italicBtn.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC));
        italicBtn.setTextColor(block.italic ? 0xFF90CAF9 : 0xFF666666);
        italicBtn.setOnClickListener(v -> {
            block.italic = !block.italic;
            italicBtn.setTextColor(block.italic ? 0xFF90CAF9 : 0xFF666666);
            refreshPreview();
        });
        bar.addView(italicBtn);

        spacer(bar, 8);

        // Font
        TextView fontBtn = new TextView(context);
        fontBtn.setText(shortFontLabel(block.fontFamily));
        fontBtn.setTextSize(11f);
        fontBtn.setTextColor(0xFFAAAAAA);
        fontBtn.setOnClickListener(v ->
            new AdvancedRichTextController.FontFamilyPopup(context, block.fontFamily, family -> {
                block.fontFamily = family;
                fontBtn.setText(shortFontLabel(family));
                refreshPreview();
            }).showAsDropDown(fontBtn, 0, 4)
        );
        bar.addView(fontBtn);

        // Delete block button (not shown for block 0)
        if (index > 0) {
            spacer(bar, 0, true); // push to right
            TextView delBtn = new TextView(context);
            delBtn.setText("✕");
            delBtn.setTextColor(0xFF666666);
            delBtn.setTextSize(13f);
            delBtn.setOnClickListener(v -> {
                pm.textBlocks.remove(index);
                refreshPreview();
                // Trigger full rebuild of block editors
                mainHandler.post(this::rebuildEditorBlockList);
            });
            bar.addView(delBtn);
        }

        return bar;
    }

    @NonNull
    private View buildAspectRatioRow() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(6));

        TextView label = new TextView(context);
        label.setText("Ratio  ");
        label.setTextColor(0xFF888888);
        label.setTextSize(12f);
        row.addView(label);

        PresentationMessage.AspectRatio[] ratios = PresentationMessage.AspectRatio.values();
        String[] labels = { "16:9", "1:1", "9:16" };
        for (int i = 0; i < ratios.length; i++) {
            final PresentationMessage.AspectRatio r = ratios[i];
            TextView btn = new TextView(context);
            btn.setText(labels[i]);
            btn.setTextSize(12f);
            btn.setPadding(dp(10), dp(6), dp(10), dp(6));
            boolean sel = pm.aspectRatio == r;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(sel ? 0xFF1976D2 : 0xFF2A2A2A);
            bg.setCornerRadius(dp(6));
            btn.setBackground(bg);
            btn.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(4), 0, dp(4), 0);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> {
                pm.aspectRatio = r;
                refreshPreview();
                // Rebuild ratio buttons state
                for (int j = 0; j < row.getChildCount(); j++) {
                    View child = row.getChildAt(j);
                    if (child instanceof TextView && child != label) {
                        GradientDrawable d = new GradientDrawable();
                        d.setCornerRadius(dp(6));
                        d.setColor(child == btn ? 0xFF1976D2 : 0xFF2A2A2A);
                        child.setBackground(d);
                    }
                }
            });
            row.addView(btn);
        }
        return row;
    }

    @NonNull
    private View buildBgPickerRow() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(8), 0, dp(8));

        TextView label = new TextView(context);
        label.setText("Background");
        label.setTextColor(0xFF888888);
        label.setTextSize(12f);
        col.addView(label);

        // Gradient colour swatches
        androidx.core.widget.NestedScrollView hsv = new androidx.core.widget.NestedScrollView(context);
        LinearLayout swatchRow = new LinearLayout(context);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        swatchRow.setPadding(0, dp(8), 0, dp(8));

        // "Photo" swatch first
        TextView photoPick = new TextView(context);
        photoPick.setText("🖼️");
        photoPick.setTextSize(22f);
        photoPick.setGravity(Gravity.CENTER);
        GradientDrawable photoBg = new GradientDrawable();
        photoBg.setColor(0xFF2A2A2A);
        photoBg.setCornerRadius(dp(8));
        photoPick.setBackground(photoBg);
        photoPick.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(48), dp(48));
        plp.setMargins(0, 0, dp(6), 0);
        photoPick.setLayoutParams(plp);
        photoPick.setOnClickListener(v -> triggerImagePicker());
        swatchRow.addView(photoPick);

        for (int[] gradient : BG_GRADIENTS) {
            final int[] g = gradient;
            View swatch = new View(context);
            GradientDrawable swatchBg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, g);
            swatchBg.setCornerRadius(dp(8));
            swatch.setBackground(swatchBg);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(48), dp(48));
            slp.setMargins(0, 0, dp(6), 0);
            swatch.setLayoutParams(slp);
            swatch.setOnClickListener(view -> {
                pm.bgColor    = g[0];
                pm.bgImageUrl = null;
                pendingBgBitmap = null;
                // Set theme automatically based on luminance
                pm.theme = luminance(g[0]) > 0.5f
                           ? PresentationMessage.Theme.DARK
                           : PresentationMessage.Theme.LIGHT;
                refreshPreview();
            });
            swatchRow.addView(swatch);
        }

        hsv.addView(swatchRow);
        col.addView(hsv);
        return col;
    }

    @NonNull
    private View buildOverlayToggleRow() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(16));

        TextView label = new TextView(context);
        label.setText("Dark overlay (for photo bg)");
        label.setTextColor(0xFF888888);
        label.setTextSize(12f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(lp);
        row.addView(label);

        android.widget.Switch toggle = new android.widget.Switch(context);
        toggle.setChecked(pm.overlayGradient);
        toggle.setOnCheckedChangeListener((btn, checked) -> {
            pm.overlayGradient = checked;
            refreshPreview();
        });
        row.addView(toggle);

        return row;
    }

    // ── Send handler ──────────────────────────────────────────────────────────

    private void handleSend() {
        // Sync EditText content back to TextBlocks
        for (int i = 0; i < blockEditors.size() && i < pm.textBlocks.size(); i++) {
            EditText et = blockEditors.get(i);
            if (et.getText() != null) {
                pm.textBlocks.get(i).text = et.getText().toString();
            }
        }
        // Validate: at least one non-empty block
        boolean hasContent = false;
        for (PresentationMessage.TextBlock b : pm.textBlocks) {
            if (b.text != null && !b.text.trim().isEmpty()) { hasContent = true; break; }
        }
        if (!hasContent) {
            Toast.makeText(context, "Add some text to your slide", Toast.LENGTH_SHORT).show();
            return;
        }
        dismiss();
        sendCallback.onSend(pm);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void refreshPreview() {
        if (previewCanvas != null) {
            previewCanvas.bindPresentation(pm, pendingBgBitmap);
            previewCanvas.invalidate();
        }
    }

    private void rebuildEditorBlockList() {
        // Dismiss and re-show editor with updated block list.
        dismiss();
        mainHandler.postDelayed(this::show, 200);
    }

    private void triggerImagePicker() {
        // Signal to the host Activity to start the picker.
        if (context instanceof Activity) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            ((Activity) context).startActivityForResult(
                Intent.createChooser(intent, "Select Background Image"),
                REQ_PICK_BG_IMAGE);
        }
    }

    private void showBlockColorPicker(int index, PresentationMessage.TextBlock block, View anchor) {
        new AdvancedRichTextController.ColorPickerPopup(
            context,
            AdvancedRichTextController.TEXT_COLORS,
            "Block text color",
            color -> {
                block.textColor = color;
                anchor.setBackgroundColor(color);
                refreshPreview();
            }
        ).showAsDropDown(anchor, 0, 8);
    }

    private String blockRoleLabel(int index, PresentationMessage.TextRole role) {
        switch (role) {
            case TITLE:   return "TITLE";
            case CAPTION: return "CAPTION";
            default:      return "BODY " + (index);
        }
    }

    private String alignLabel(PresentationMessage.TextAlignment align) {
        switch (align) {
            case CENTER: return "≡C";
            case RIGHT:  return "≡R";
            default:     return "≡L";
        }
    }

    private String shortFontLabel(String family) {
        if (family.contains("mono"))      return "Mono";
        if (family.contains("light"))     return "Light";
        if (family.contains("medium"))    return "Med";
        if (family.contains("thin"))      return "Thin";
        if (family.contains("condensed")) return "Cond";
        if (family.contains("cursive"))   return "Italic";
        if (family.contains("serif"))     return "Serif";
        return "Sans";
    }

    private static float luminance(@ColorInt int color) {
        float r = Color.red(color)   / 255f;
        float g = Color.green(color) / 255f;
        float b = Color.blue(color)  / 255f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private void spacer(LinearLayout parent, int widthDp) {
        spacer(parent, widthDp, false);
    }

    private void spacer(LinearLayout parent, int widthDp, boolean flexible) {
        View sp = new View(context);
        if (flexible) {
            parent.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        } else {
            parent.addView(sp, dp(widthDp), 1);
        }
    }

    private int dp(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private int screenWidth() {
        return context.getResources().getDisplayMetrics().widthPixels;
    }
}
