package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.adapters.MultiImageGridAdapter;
import com.callx.app.adapters.MultiImageStripAdapter;
import com.callx.app.chat.R;
import com.callx.app.chat.databinding.ActivityMultiImagePickerBinding;
import com.callx.app.models.PendingImage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MultiImagePickerActivity — Pick up to 10 images from gallery and send them in one message.
 *
 * Features:
 *   ✅ Android photo picker (PickMultipleVisualMedia — system picker, no READ_MEDIA permission needed on API 33+)
 *   ✅ Max 10 images enforced (toast shown if user tries more)
 *   ✅ Horizontal STRIP at bottom — thumbnails of selected images, tap to focus
 *   ✅ Focused image shown full-screen in center pane (swipeable)
 *   ✅ Individual caption per image — typed in caption bar
 *   ✅ Remove image: tap ✕ on thumbnail strip
 *   ✅ Reorder via long-press drag on strip
 *   ✅ Send button shows count badge: "Send (3)"
 *   ✅ Pre-compress toggle (applies to all images)
 *   ✅ Reply context (if replying in chat)
 *
 * Launch from ChatActivity:
 *   Intent i = new Intent(this, MultiImagePickerActivity.class);
 *   i.putExtra(MultiImagePickerActivity.EXTRA_PARTNER_NAME, partnerName);
 *   i.putExtra(MultiImagePickerActivity.EXTRA_REPLY_TEXT,   replyText);
 *   startActivityForResult(i, REQ_MULTI_IMAGE);
 *
 * Result (in onActivityResult):
 *   ArrayList<String> uris     = data.getStringArrayListExtra(RESULT_URIS);
 *   ArrayList<String> captions = data.getStringArrayListExtra(RESULT_CAPTIONS);
 *   boolean compressed          = data.getBooleanExtra(RESULT_COMPRESSED, true);
 *   // uris.get(i) pairs with captions.get(i) — same index
 */
public class MultiImagePickerActivity extends AppCompatActivity {

    // ── Intent extras ──────────────────────────────────────────────────────
    public static final String EXTRA_PARTNER_NAME = "partnerName";
    public static final String EXTRA_REPLY_TEXT   = "replyText";
    public static final String EXTRA_REPLY_SENDER = "replySender";

    // ── Result keys ────────────────────────────────────────────────────────
    public static final String RESULT_URIS       = "uris";
    public static final String RESULT_CAPTIONS   = "captions";
    public static final String RESULT_COMPRESSED = "compressed";

    // ── Config ─────────────────────────────────────────────────────────────
    public static final int MAX_IMAGES = 10;

    // ── Views ──────────────────────────────────────────────────────────────
    private ActivityMultiImagePickerBinding binding;

    // ── Adapters ───────────────────────────────────────────────────────────
    private MultiImageStripAdapter stripAdapter;
    private int focusedIndex = 0;

    // ── Data ───────────────────────────────────────────────────────────────
    private final List<PendingImage> pendingImages = new ArrayList<>();
    private boolean compressImages = true;

    // ── Photo picker ───────────────────────────────────────────────────────
    private ActivityResultLauncher<PickVisualMediaRequest> photoPicker;

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMultiImagePickerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupPhotoPicker();
        setupToolbar();
        setupStripRecycler();
        setupFocusedPreview();
        setupCaptionBar();
        setupCompressToggle();
        setupReplyPreview();
        setupSendButton();
        setupAddMoreButton();

        // Auto-open picker on launch
        openPicker();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PHOTO PICKER
    // ─────────────────────────────────────────────────────────────────────

    private void setupPhotoPicker() {
        // Android 13+ system photo picker — no permission needed
        photoPicker = registerForActivityResult(
            new ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES),
            uris -> {
                if (uris == null || uris.isEmpty()) {
                    if (pendingImages.isEmpty()) { setResult(RESULT_CANCELED); finish(); }
                    return;
                }

                int remaining = MAX_IMAGES - pendingImages.size();
                List<Uri> allowed = uris.subList(0, Math.min(uris.size(), remaining));

                if (uris.size() > remaining) {
                    Toast.makeText(this,
                            "Max " + MAX_IMAGES + " images — added first " + remaining,
                            Toast.LENGTH_SHORT).show();
                }

                for (Uri uri : allowed) {
                    // Persist permission so URI survives process death
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) {}
                    pendingImages.add(new PendingImage(uri.toString(), ""));
                }

                refreshAll();
                focusIndex(0);
            }
        );
    }

    private void openPicker() {
        photoPicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    // ─────────────────────────────────────────────────────────────────────
    // TOOLBAR
    // ─────────────────────────────────────────────────────────────────────

    private void setupToolbar() {
        String partnerName = getIntent().getStringExtra(EXTRA_PARTNER_NAME);
        binding.tvSendTo.setText(partnerName != null ? "Send to " + partnerName : "Send");
        binding.btnBack.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
    }

    // ─────────────────────────────────────────────────────────────────────
    // STRIP RECYCLER (horizontal thumbnails)
    // ─────────────────────────────────────────────────────────────────────

    private void setupStripRecycler() {
        stripAdapter = new MultiImageStripAdapter(pendingImages,
            /* onThumbnailClick */ index -> focusIndex(index),
            /* onRemoveClick    */ index -> removeImage(index)
        );

        binding.rvStrip.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        binding.rvStrip.setAdapter(stripAdapter);

        // Drag-to-reorder on strip
        ItemTouchHelper.SimpleCallback dragCallback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv,
                    @NonNull RecyclerView.ViewHolder from,
                    @NonNull RecyclerView.ViewHolder to) {
                int fromPos = from.getAdapterPosition();
                int toPos   = to.getAdapterPosition();
                Collections.swap(pendingImages, fromPos, toPos);
                stripAdapter.notifyItemMoved(fromPos, toPos);
                if (focusedIndex == fromPos) focusedIndex = toPos;
                return true;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {}
        };
        new ItemTouchHelper(dragCallback).attachToRecyclerView(binding.rvStrip);
    }

    // ─────────────────────────────────────────────────────────────────────
    // FOCUSED PREVIEW (center big image)
    // ─────────────────────────────────────────────────────────────────────

    private void setupFocusedPreview() {
        // Swipe left/right to change focused image
        binding.ivFocused.setOnTouchListener(new SwipeGestureListener(this) {
            @Override protected void onSwipeLeft()  { if (focusedIndex < pendingImages.size()-1) focusIndex(focusedIndex+1); }
            @Override protected void onSwipeRight() { if (focusedIndex > 0) focusIndex(focusedIndex-1); }
        });
    }

    private void focusIndex(int index) {
        if (index < 0 || index >= pendingImages.size()) return;

        // Save caption of current focused image before switching
        if (focusedIndex >= 0 && focusedIndex < pendingImages.size()) {
            String current = binding.etCaption.getText() != null
                    ? binding.etCaption.getText().toString() : "";
            pendingImages.get(focusedIndex).caption = current;
        }

        focusedIndex = index;
        PendingImage img = pendingImages.get(index);

        // Load into big preview using Glide
        com.bumptech.glide.Glide.with(this)
                .load(Uri.parse(img.uriString))
                .into(binding.ivFocused);

        // Update caption bar with this image's caption
        binding.etCaption.setText(img.caption);
        binding.etCaption.setSelection(img.caption.length());

        // Highlight active strip item
        stripAdapter.setFocusedIndex(index);
        binding.rvStrip.smoothScrollToPosition(index);

        // Counter: "2 / 5"
        binding.tvCounter.setText((index + 1) + " / " + pendingImages.size());
    }

    // ─────────────────────────────────────────────────────────────────────
    // CAPTION BAR
    // ─────────────────────────────────────────────────────────────────────

    private void setupCaptionBar() {
        binding.etCaption.setHint("Caption for photo " + (focusedIndex + 1) + "...");
    }

    // ─────────────────────────────────────────────────────────────────────
    // COMPRESS TOGGLE
    // ─────────────────────────────────────────────────────────────────────

    private void setupCompressToggle() {
        updateCompressLabel();
        binding.btnCompress.setOnClickListener(v -> {
            compressImages = !compressImages;
            updateCompressLabel();
        });
    }

    private void updateCompressLabel() {
        binding.btnCompress.setText(compressImages ? "⚡ Compressed" : "🖼 Original");
        binding.btnCompress.setAlpha(compressImages ? 1f : 0.65f);
    }

    // ─────────────────────────────────────────────────────────────────────
    // REPLY PREVIEW
    // ─────────────────────────────────────────────────────────────────────

    private void setupReplyPreview() {
        String replyText   = getIntent().getStringExtra(EXTRA_REPLY_TEXT);
        String replySender = getIntent().getStringExtra(EXTRA_REPLY_SENDER);

        if (replyText == null || replyText.isEmpty()) {
            binding.layoutReplyPreview.setVisibility(View.GONE);
            return;
        }
        binding.layoutReplyPreview.setVisibility(View.VISIBLE);
        binding.tvReplyName.setText(replySender != null ? replySender : "Reply");
        binding.tvReplyText.setText(replyText);
        binding.btnCloseReply.setOnClickListener(v ->
                binding.layoutReplyPreview.setVisibility(View.GONE));
    }

    // ─────────────────────────────────────────────────────────────────────
    // SEND BUTTON
    // ─────────────────────────────────────────────────────────────────────

    private void setupSendButton() {
        updateSendButton();
        binding.btnSend.setOnClickListener(v -> sendImages());
    }

    private void updateSendButton() {
        int count = pendingImages.size();
        binding.btnSend.setEnabled(count > 0);
        binding.btnSend.setText(count > 1 ? "Send (" + count + ")" : "Send");
    }

    // ─────────────────────────────────────────────────────────────────────
    // ADD MORE BUTTON
    // ─────────────────────────────────────────────────────────────────────

    private void setupAddMoreButton() {
        binding.btnAddMore.setOnClickListener(v -> {
            if (pendingImages.size() >= MAX_IMAGES) {
                Toast.makeText(this, "Max " + MAX_IMAGES + " images allowed", Toast.LENGTH_SHORT).show();
            } else {
                openPicker();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // REMOVE IMAGE
    // ─────────────────────────────────────────────────────────────────────

    private void removeImage(int index) {
        if (pendingImages.isEmpty()) return;
        pendingImages.remove(index);

        if (pendingImages.isEmpty()) {
            // No images left — go back
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        int newFocus = Math.min(focusedIndex, pendingImages.size() - 1);
        focusedIndex = -1; // force refresh
        refreshAll();
        focusIndex(newFocus);
    }

    // ─────────────────────────────────────────────────────────────────────
    // SEND
    // ─────────────────────────────────────────────────────────────────────

    private void sendImages() {
        if (pendingImages.isEmpty()) return;

        // Save caption for currently focused image
        if (focusedIndex >= 0 && focusedIndex < pendingImages.size()) {
            String cur = binding.etCaption.getText() != null
                    ? binding.etCaption.getText().toString() : "";
            pendingImages.get(focusedIndex).caption = cur;
        }

        ArrayList<String> uris     = new ArrayList<>();
        ArrayList<String> captions = new ArrayList<>();
        for (PendingImage img : pendingImages) {
            uris.add(img.uriString);
            captions.add(img.caption != null ? img.caption : "");
        }

        Intent result = new Intent();
        result.putStringArrayListExtra(RESULT_URIS,       uris);
        result.putStringArrayListExtra(RESULT_CAPTIONS,   captions);
        result.putExtra(RESULT_COMPRESSED, compressImages);
        setResult(RESULT_OK, result);
        finish();
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private void refreshAll() {
        stripAdapter.notifyDataSetChanged();
        updateSendButton();
        binding.tvCounter.setText(pendingImages.size() + " photo" + (pendingImages.size() > 1 ? "s" : ""));
    }

    // ─────────────────────────────────────────────────────────────────────
    // SwipeGestureListener — inner abstract class for swipe detection
    // ─────────────────────────────────────────────────────────────────────

    private static abstract class SwipeGestureListener
            implements View.OnTouchListener {
        private float startX;
        private static final int SWIPE_THRESHOLD = 100;

        SwipeGestureListener(android.content.Context ctx) {}

        @Override
        public boolean onTouch(View v, android.view.MotionEvent e) {
            switch (e.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN: startX = e.getX(); break;
                case android.view.MotionEvent.ACTION_UP:
                    float diff = e.getX() - startX;
                    if (Math.abs(diff) > SWIPE_THRESHOLD) {
                        if (diff < 0) onSwipeLeft();
                        else          onSwipeRight();
                    }
                    v.performClick();
                    break;
            }
            return true;
        }
        protected abstract void onSwipeLeft();
        protected abstract void onSwipeRight();
    }
}
