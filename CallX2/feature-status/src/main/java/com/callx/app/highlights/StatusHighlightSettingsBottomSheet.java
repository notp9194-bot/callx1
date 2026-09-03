package com.callx.app.highlights;
import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.AlertDialogStyler;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.StatusHighlightManager;
import java.util.List;

/**
 * StatusHighlightSettingsBottomSheet v39 — the "Highlight editing & settings"
 * system that was previously entirely missing (long-press on an album used to
 * only offer a plain "Delete album" AlertDialog with no rename/cover option).
 *
 * Options offered (long-press on an album in StatusHighlightsActivity, owner only):
 *   - Rename Highlight        → StatusHighlightManager.renameAlbum()
 *   - Change Cover            → grid picker over the album's own items → setAlbumCover()
 *   - Delete Highlight        → StatusHighlightManager.deleteAlbum() (with confirm)
 */
public class StatusHighlightSettingsBottomSheet {
    public interface OnChangedListener { void onChanged(); }

    public static void show(Context ctx, String ownerUid, String albumId, String albumName,
                            List<StatusItem> items, OnChangedListener listener) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 28));

        TextView title = new TextView(ctx);
        title.setText(albumName);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(ctx, 16));
        root.addView(title);

        root.addView(rowItem(ctx, "\u270F\uFE0F", "Rename Highlight", v -> {
            sheet.dismiss();
            showRenameDialog(ctx, ownerUid, albumId, albumName, listener);
        }));
        root.addView(rowItem(ctx, "\uD83D\uDDBC\uFE0F", "Change Cover", v -> {
            sheet.dismiss();
            showCoverPicker(ctx, ownerUid, albumId, items, listener);
        }));
        root.addView(rowItem(ctx, "\uD83C\uDFA8", "Ring Color", v -> {
            sheet.dismiss();
            showRingColorPicker(ctx, ownerUid, albumId, listener);
        }));
        root.addView(rowItem(ctx, "\uD83D\uDCF3", "Level Stabilizer", v -> {
            sheet.dismiss();
            showStabilizerToggle(ctx, ownerUid, albumId, listener);
        }));
        root.addView(rowItem(ctx, "\uD83D\uDDD1\uFE0F", "Delete Highlight", v -> {
            sheet.dismiss();
            confirmDelete(ctx, ownerUid, albumId, albumName, listener);
        }));

        sheet.setContentView(root);
        sheet.show();
    }

    private static View rowItem(Context ctx, String emoji, String label, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 4), dp(ctx, 14), dp(ctx, 4), dp(ctx, 14));
        row.setOnClickListener(onClick);
        TextView icon = new TextView(ctx);
        icon.setText(emoji);
        icon.setTextSize(20);
        icon.setPadding(0, 0, dp(ctx, 16), 0);
        row.addView(icon);
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(15);
        row.addView(tv);
        return row;
    }

    private static void showRenameDialog(Context ctx, String ownerUid, String albumId,
                                         String currentName, OnChangedListener listener) {
        EditText input = new EditText(ctx);
        input.setText(currentName);
        if (input.getText() != null) input.setSelection(input.getText().length());
        AlertDialogStyler.showRounded(new AlertDialog.Builder(ctx)
            .setTitle("Rename Highlight")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String newName = input.getText() != null ? input.getText().toString().trim() : "";
                if (!newName.isEmpty()) {
                    StatusHighlightManager.renameAlbum(ownerUid, albumId, newName);
                    if (listener != null) listener.onChanged();
                }
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    private static void showCoverPicker(Context ctx, String ownerUid, String albumId,
                                        List<StatusItem> items, OnChangedListener listener) {
        BottomSheetDialog picker = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 24));
        TextView title = new TextView(ctx);
        title.setText("Choose Cover");
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(ctx, 12));
        root.addView(title);
        RecyclerView rv = new RecyclerView(ctx);
        rv.setLayoutManager(new GridLayoutManager(ctx, 3));
        // Instagram-style: same "load only what the cell can show" pipeline
        // as the highlight picker grids — cell size resolved once, CDN-
        // derived WebP thumb + tiny blur-up placeholder, instead of the old
        // Glide.load(url).centerCrop().into(iv) with no size cap at all
        // (a full-resolution network download for a ~100dp cover cell).
        final int[] resolvedCellPx = {0};
        final int blurSize = 16;
        final RequestOptions coverGridOptions = new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .format(DecodeFormat.PREFER_RGB_565)
                .centerCrop()
                .dontAnimate();
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                ImageView iv = new ImageView(parent.getContext());
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                int size = parent.getWidth() > 0 ? parent.getWidth() / 3 : dp(ctx, 100);
                if (resolvedCellPx[0] == 0) resolvedCellPx[0] = size;
                iv.setLayoutParams(new RecyclerView.LayoutParams(size, size));
                iv.setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2));
                return new RecyclerView.ViewHolder(iv) {};
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                StatusItem item = items.get(position);
                ImageView iv = (ImageView) holder.itemView;
                String url = item.thumbnailUrl != null ? item.thumbnailUrl : item.mediaUrl;
                if (url != null && !url.isEmpty()) {
                    int cellPx = resolvedCellPx[0] > 0 ? resolvedCellPx[0] : dp(ctx, 100);
                    String gridUrl = CloudinaryUploader.deriveThumbUrl(url, cellPx, "webp");
                    String blurUrl = CloudinaryUploader.deriveThumbUrl(url, blurSize, "webp");
                    Glide.with(iv)
                            .load(gridUrl)
                            .thumbnail(Glide.with(iv).load(blurUrl).apply(coverGridOptions))
                            .apply(coverGridOptions)
                            .into(iv);
                } else if (item.bgColor != null) {
                    iv.setImageDrawable(null);
                    iv.setBackgroundColor(Color.parseColor(item.bgColor));
                }
                iv.setOnClickListener(v -> {
                    StatusHighlightManager.setAlbumCover(ownerUid, albumId, item.id, url);
                    if (listener != null) listener.onChanged();
                    picker.dismiss();
                });
            }
            @Override public int getItemCount() { return items.size(); }
        });
        root.addView(rv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 360)));
        picker.setContentView(root);
        picker.show();
    }

    private static void showRingColorPicker(Context ctx, String ownerUid, String albumId,
                                            OnChangedListener listener) {
        StatusHighlightManager.getAlbumMetaRef(ownerUid, albumId)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                    String currentColor = snap.child("ringColor").getValue(String.class);
                    String currentMode  = snap.child("ringMode").getValue(String.class);
                    HighlightRingColorPickerBottomSheet.show(ctx, currentColor, currentMode,
                            currentColor != null && !currentColor.isEmpty(),
                            (colorHex, mode) -> {
                                if (colorHex == null) {
                                    StatusHighlightManager.clearAlbumRingStyle(ownerUid, albumId);
                                } else {
                                    StatusHighlightManager.setAlbumRingStyle(ownerUid, albumId, colorHex, mode);
                                }
                                if (listener != null) listener.onChanged();
                            });
                }
                @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {
                    HighlightRingColorPickerBottomSheet.show(ctx, null, null, false,
                            (colorHex, mode) -> {
                                if (colorHex != null) {
                                    StatusHighlightManager.setAlbumRingStyle(ownerUid, albumId, colorHex, mode);
                                    if (listener != null) listener.onChanged();
                                }
                            });
                }
            });
    }

    /** Owner-only, from the long-press "Level Stabilizer" row: lets them
     *  turn the level-stabilizer on (or back off) for THIS WHOLE HIGHLIGHT
     *  ALBUM after the fact. This is the mechanism that lets a story which
     *  posted without the effect still gain it once saved to Highlights —
     *  or lets a highlighted story that had it while live lose it, all
     *  without touching the individual stories themselves. Reads the
     *  current album-level flag first so the dialog opens already showing
     *  the right state. */
    private static void showStabilizerToggle(Context ctx, String ownerUid, String albumId,
                                              OnChangedListener listener) {
        StatusHighlightManager.getAlbumMetaRef(ownerUid, albumId)
            .child("stabilizerEnabled")
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                    Boolean current = snap.getValue(Boolean.class);
                    renderDialog(current != null && current);
                }
                @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {
                    renderDialog(false);
                }
                private void renderDialog(boolean currentlyOn) {
                    LinearLayout row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8));
                    TextView label = new TextView(ctx);
                    label.setText("Keep media level while viewing");
                    label.setTextSize(14);
                    row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    Switch sw = new Switch(ctx);
                    sw.setChecked(currentlyOn);
                    row.addView(sw);
                    AlertDialogStyler.showRounded(new AlertDialog.Builder(ctx)
                        .setTitle("Level Stabilizer")
                        .setView(row)
                        .setPositiveButton("Save", (d, w) -> {
                            StatusHighlightManager.setAlbumStabilizerEnabled(ownerUid, albumId, sw.isChecked());
                            if (listener != null) listener.onChanged();
                        })
                        .setNegativeButton("Cancel", null)
                        .create());
                }
            });
    }

    private static void confirmDelete(Context ctx, String ownerUid, String albumId,
                                      String albumName, OnChangedListener listener) {
        AlertDialogStyler.showRounded(new AlertDialog.Builder(ctx)
            .setTitle("Delete this Highlight?")
            .setMessage("All statuses in \"" + albumName + "\" will be removed from Highlights. " +
                    "The original stories (if still active) won't be affected.")
            .setPositiveButton("Delete", (d, w) -> {
                StatusHighlightManager.deleteAlbum(ownerUid, albumId);
                if (listener != null) listener.onChanged();
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
