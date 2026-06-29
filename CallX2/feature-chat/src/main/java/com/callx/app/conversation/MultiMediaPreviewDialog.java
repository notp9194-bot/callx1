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

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

/**
 * WhatsApp-style caption dialog shown before sending multiple media.
 * Shows scrollable thumbnails of selected items + caption EditText + Send button.
 */
public class MultiMediaPreviewDialog {

    public interface OnSendCallback {
        void onSend(List<Uri> uris, String caption);
    }

    public static void show(Context ctx, List<Uri> uris, OnSendCallback cb) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        float d = ctx.getResources().getDisplayMetrics().density;

        // ── Root layout ─────────────────────────────────────────────────────
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int)(16 * d);
        root.setPadding(p, p, p, (int)(24 * d));

        // ── Title ────────────────────────────────────────────────────────────
        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(uris.size() + " media selected");
        tvTitle.setTextSize(17f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = (int)(12 * d);
        tvTitle.setLayoutParams(titleLp);
        root.addView(tvTitle);

        // ── Thumbnail strip ──────────────────────────────────────────────────
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        LinearLayout llThumbs = new LinearLayout(ctx);
        llThumbs.setOrientation(LinearLayout.HORIZONTAL);
        int thumbSz = (int)(88 * d);

        for (int i = 0; i < uris.size(); i++) {
            FrameLayout cell = new FrameLayout(ctx);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(thumbSz, thumbSz);
            if (i > 0) cellLp.leftMargin = (int)(4 * d);
            cell.setLayoutParams(cellLp);
            cell.setClipToOutline(true);

            // Thumbnail image
            ImageView iv = new ImageView(ctx);
            iv.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF333333);
            Glide.with(ctx).load(uris.get(i)).centerCrop().into(iv);
            cell.addView(iv);

            // Number badge (top-left)
            TextView tvNum = new TextView(ctx);
            FrameLayout.LayoutParams numLp = new FrameLayout.LayoutParams(
                    (int)(22 * d), (int)(22 * d));
            numLp.gravity = Gravity.TOP | Gravity.START;
            numLp.topMargin  = (int)(4 * d);
            numLp.leftMargin = (int)(4 * d);
            tvNum.setLayoutParams(numLp);
            tvNum.setText(String.valueOf(i + 1));
            tvNum.setTextColor(Color.WHITE);
            tvNum.setTextSize(11f);
            tvNum.setGravity(Gravity.CENTER);
            tvNum.setBackgroundColor(0xBB000000);
            cell.addView(tvNum);

            llThumbs.addView(cell);
        }
        hsv.addView(llThumbs);
        LinearLayout.LayoutParams hsvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int)(100 * d));
        hsvLp.bottomMargin = (int)(14 * d);
        hsv.setLayoutParams(hsvLp);
        root.addView(hsv);

        // ── Caption EditText ─────────────────────────────────────────────────
        EditText etCaption = new EditText(ctx);
        etCaption.setHint("Caption likhein (optional)...");
        etCaption.setMaxLines(3);
        etCaption.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etCaption.setPadding((int)(10*d), (int)(8*d), (int)(10*d), (int)(8*d));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp.bottomMargin = (int)(16 * d);
        etCaption.setLayoutParams(etLp);
        root.addView(etCaption);

        // ── Buttons row ──────────────────────────────────────────────────────
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnCancel = new Button(ctx);
        btnCancel.setText("Cancel");
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cancelLp.rightMargin = (int)(8 * d);
        btnCancel.setLayoutParams(cancelLp);
        btnCancel.setOnClickListener(v -> sheet.dismiss());

        Button btnSend = new Button(ctx);
        btnSend.setText("Send " + uris.size());
        btnSend.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnSend.setOnClickListener(v -> {
            String caption = etCaption.getText().toString().trim();
            sheet.dismiss();
            cb.onSend(uris, caption);
        });

        btnRow.addView(btnCancel);
        btnRow.addView(btnSend);
        root.addView(btnRow);

        sheet.setContentView(root);
        sheet.show();
    }
}
