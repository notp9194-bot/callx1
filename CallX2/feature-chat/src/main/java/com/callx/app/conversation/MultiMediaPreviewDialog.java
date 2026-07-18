package com.callx.app.conversation;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WhatsApp-style caption dialog shown before sending multiple media.
 *
 * Shows a scrollable thumbnail strip with, per item:
 *   - a "✕" remove button (drop a photo/video before sending)
 *   - "‹ ›" arrows to reorder it left/right
 *   - a small caption pin — tap to set a caption for just that item
 *     (shown later when that item is opened individually; see
 *     GalleryPagerAdapter's per-item caption overlay)
 * plus the original group-level caption EditText (shown under the grid
 * in the chat bubble) and a Send button.
 */
public class MultiMediaPreviewDialog {

    public interface OnSendCallback {
        /**
         * @param uris            final ordered list (after any remove/reorder)
         * @param perItemCaptions same size/order as uris — per-item caption,
         *                        or null/empty string if that item has none
         * @param caption         the group-level caption (shown under the grid)
         */
        void onSend(List<Uri> uris, List<String> perItemCaptions, String caption);
    }

    public static void show(Context ctx, List<Uri> initialUris, OnSendCallback cb) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        float d = ctx.getResources().getDisplayMetrics().density;

        // Mutable working copies — remove/reorder operate on these directly.
        final List<Uri> uris = new ArrayList<>(initialUris);
        final List<String> perItemCaptions = new ArrayList<>();
        for (int i = 0; i < uris.size(); i++) perItemCaptions.add(null);

        // ── Root layout ─────────────────────────────────────────────────────
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * d);
        root.setPadding(p, p, p, (int) (24 * d));

        // ── Title ────────────────────────────────────────────────────────────
        TextView tvTitle = new TextView(ctx);
        tvTitle.setTextSize(17f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = (int) (12 * d);
        tvTitle.setLayoutParams(titleLp);
        root.addView(tvTitle);

        // ── Hint ─────────────────────────────────────────────────────────────
        TextView tvHint = new TextView(ctx);
        tvHint.setText("Tap ✕ to remove · ‹ › to reorder · 💬 for a per-photo caption");
        tvHint.setTextSize(11f);
        tvHint.setTextColor(0xFFAAAAAA);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.bottomMargin = (int) (8 * d);
        tvHint.setLayoutParams(hintLp);
        root.addView(tvHint);

        // ── Thumbnail strip (rebuilt on every remove/reorder) ──────────────────
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        LinearLayout llThumbs = new LinearLayout(ctx);
        llThumbs.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(llThumbs);
        LinearLayout.LayoutParams hsvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (132 * d));
        hsvLp.bottomMargin = (int) (14 * d);
        hsv.setLayoutParams(hsvLp);
        root.addView(hsv);

        // ── Caption EditText (group-level) ──────────────────────────────────
        EditText etCaption = new EditText(ctx);
        etCaption.setHint("Caption likhein (optional)...");
        etCaption.setMaxLines(3);
        etCaption.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etCaption.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp.bottomMargin = (int) (16 * d);
        etCaption.setLayoutParams(etLp);
        root.addView(etCaption);

        // ── Buttons row ──────────────────────────────────────────────────────
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnCancel = new Button(ctx);
        btnCancel.setText("Cancel");
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cancelLp.rightMargin = (int) (8 * d);
        btnCancel.setLayoutParams(cancelLp);
        btnCancel.setOnClickListener(v -> sheet.dismiss());

        Button btnSend = new Button(ctx);
        btnSend.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnSend.setOnClickListener(v -> {
            if (uris.isEmpty()) { sheet.dismiss(); return; }
            String caption = etCaption.getText().toString().trim();
            sheet.dismiss();
            cb.onSend(new ArrayList<>(uris), new ArrayList<>(perItemCaptions), caption);
        });

        btnRow.addView(btnCancel);
        btnRow.addView(btnSend);
        root.addView(btnRow);

        // ── Thumbnail strip rebuild (called after any remove/reorder/caption edit) ─
        final Runnable[] refreshHolder = new Runnable[1];
        refreshHolder[0] = () -> {
            llThumbs.removeAllViews();
            tvTitle.setText(uris.size() + " media selected");
            btnSend.setText("Send " + uris.size());
            btnSend.setEnabled(!uris.isEmpty());
            btnSend.setAlpha(uris.isEmpty() ? 0.5f : 1f);

            int thumbSz = (int) (88 * d);
            for (int i = 0; i < uris.size(); i++) {
                final int pos = i;
                LinearLayout cellCol = new LinearLayout(ctx);
                cellCol.setOrientation(LinearLayout.VERTICAL);
                cellCol.setGravity(Gravity.CENTER_HORIZONTAL);
                LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                        thumbSz, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) colLp.leftMargin = (int) (6 * d);
                cellCol.setLayoutParams(colLp);

                FrameLayout cell = new FrameLayout(ctx);
                cell.setLayoutParams(new LinearLayout.LayoutParams(thumbSz, thumbSz));
                cell.setClipToOutline(true);

                ImageView iv = new ImageView(ctx);
                iv.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(0xFF333333);
                Glide.with(ctx).load(uris.get(pos)).centerCrop().override(720, 720).into(iv);
                cell.addView(iv);

                // Number badge (top-left)
                TextView tvNum = new TextView(ctx);
                FrameLayout.LayoutParams numLp = new FrameLayout.LayoutParams(
                        (int) (20 * d), (int) (20 * d));
                numLp.gravity = Gravity.TOP | Gravity.START;
                numLp.topMargin = (int) (2 * d);
                numLp.leftMargin = (int) (2 * d);
                tvNum.setLayoutParams(numLp);
                tvNum.setText(String.valueOf(pos + 1));
                tvNum.setTextColor(Color.WHITE);
                tvNum.setTextSize(10f);
                tvNum.setGravity(Gravity.CENTER);
                tvNum.setBackgroundColor(0xBB000000);
                cell.addView(tvNum);

                // Remove "✕" button (top-right)
                TextView tvRemove = new TextView(ctx);
                FrameLayout.LayoutParams rmLp = new FrameLayout.LayoutParams(
                        (int) (22 * d), (int) (22 * d));
                rmLp.gravity = Gravity.TOP | Gravity.END;
                rmLp.topMargin = (int) (2 * d);
                rmLp.rightMargin = (int) (2 * d);
                tvRemove.setLayoutParams(rmLp);
                tvRemove.setText("✕");
                tvRemove.setTextColor(Color.WHITE);
                tvRemove.setTextSize(12f);
                tvRemove.setGravity(Gravity.CENTER);
                tvRemove.setBackgroundColor(0xBBCC2222);
                tvRemove.setOnClickListener(v -> {
                    uris.remove(pos);
                    perItemCaptions.remove(pos);
                    refreshHolder[0].run();
                });
                cell.addView(tvRemove);

                // Caption pin (bottom-right) — shows 💬 filled if a caption is set
                TextView tvCapPin = new TextView(ctx);
                FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT);
                capLp.gravity = Gravity.BOTTOM | Gravity.END;
                capLp.bottomMargin = (int) (2 * d);
                capLp.rightMargin = (int) (2 * d);
                tvCapPin.setLayoutParams(capLp);
                String existingCap = perItemCaptions.get(pos);
                boolean hasCap = existingCap != null && !existingCap.isEmpty();
                tvCapPin.setText(hasCap ? "💬" : "💬");
                tvCapPin.setAlpha(hasCap ? 1f : 0.55f);
                tvCapPin.setTextSize(13f);
                tvCapPin.setOnClickListener(v ->
                        showPerItemCaptionDialog(ctx, pos, perItemCaptions, refreshHolder[0]));
                cell.addView(tvCapPin);

                cellCol.addView(cell);

                // Reorder arrows row (‹ ›)
                LinearLayout arrowRow = new LinearLayout(ctx);
                arrowRow.setOrientation(LinearLayout.HORIZONTAL);
                arrowRow.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams arrowRowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                arrowRowLp.topMargin = (int) (2 * d);
                arrowRow.setLayoutParams(arrowRowLp);

                TextView tvLeft = new TextView(ctx);
                tvLeft.setText("‹");
                tvLeft.setTextSize(16f);
                tvLeft.setPadding((int) (6 * d), 0, (int) (6 * d), 0);
                tvLeft.setEnabled(pos > 0);
                tvLeft.setAlpha(pos > 0 ? 1f : 0.3f);
                tvLeft.setOnClickListener(v -> {
                    if (pos == 0) return;
                    Collections.swap(uris, pos, pos - 1);
                    Collections.swap(perItemCaptions, pos, pos - 1);
                    refreshHolder[0].run();
                });

                TextView tvRight = new TextView(ctx);
                tvRight.setText("›");
                tvRight.setTextSize(16f);
                tvRight.setPadding((int) (6 * d), 0, (int) (6 * d), 0);
                boolean canRight = pos < uris.size() - 1;
                tvRight.setEnabled(canRight);
                tvRight.setAlpha(canRight ? 1f : 0.3f);
                tvRight.setOnClickListener(v -> {
                    if (pos >= uris.size() - 1) return;
                    Collections.swap(uris, pos, pos + 1);
                    Collections.swap(perItemCaptions, pos, pos + 1);
                    refreshHolder[0].run();
                });

                arrowRow.addView(tvLeft);
                arrowRow.addView(tvRight);
                cellCol.addView(arrowRow);

                llThumbs.addView(cellCol);
            }
        };

        refreshHolder[0].run();
        sheet.setContentView(root);
        sheet.show();
    }

    /** Small AlertDialog with one EditText to set/edit a single item's caption. */
    private static void showPerItemCaptionDialog(Context ctx, int pos,
                                                  List<String> perItemCaptions,
                                                  Runnable refresh) {
        EditText et = new EditText(ctx);
        et.setHint("Caption for photo " + (pos + 1) + " (optional)");
        String existing = perItemCaptions.get(pos);
        if (existing != null) et.setText(existing);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int padPx = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        et.setPadding(padPx, padPx / 2, padPx, padPx / 2);

        com.callx.app.utils.AlertDialogStyler.showRounded(
            new AlertDialog.Builder(ctx)
                .setTitle("Photo " + (pos + 1) + " caption")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String text = et.getText().toString().trim();
                    perItemCaptions.set(pos, text.isEmpty() ? null : text);
                    refresh.run();
                })
                .setNegativeButton("Cancel", null)
        .create(), com.callx.app.utils.AlertDialogStyler.DialogSize.WIDE);
    }
}
