// ═══════════════════════════════════════════════════════════════════
// Multi-Image Send — Complete Integration Guide
// ═══════════════════════════════════════════════════════════════════

// ── PART 1: ChatActivity.java ─────────────────────────────────────────

// Step A: Add constant
private static final int REQ_MULTI_IMAGE = 504;

// Step B: Launch MultiImagePickerActivity from gallery attach option
//         (replace your existing single-image gallery pick)
void onGalleryAttachClicked() {
    Intent i = new Intent(this, MultiImagePickerActivity.class);
    i.putExtra(MultiImagePickerActivity.EXTRA_PARTNER_NAME, partnerName);
    if (replyingTo != null) {
        i.putExtra(MultiImagePickerActivity.EXTRA_REPLY_TEXT,   replyingTo.text);
        i.putExtra(MultiImagePickerActivity.EXTRA_REPLY_SENDER, replyingTo.senderName);
    }
    startActivityForResult(i, REQ_MULTI_IMAGE);
}

// Step C: Handle result in onActivityResult()
if (requestCode == REQ_MULTI_IMAGE && resultCode == RESULT_OK && data != null) {
    ArrayList<String> uris     = data.getStringArrayListExtra(MultiImagePickerActivity.RESULT_URIS);
    ArrayList<String> captions = data.getStringArrayListExtra(MultiImagePickerActivity.RESULT_CAPTIONS);
    boolean compressed          = data.getBooleanExtra(MultiImagePickerActivity.RESULT_COMPRESSED, true);

    if (uris == null || uris.isEmpty()) return;

    if (uris.size() == 1) {
        // Single image — send normally
        sendSingleImage(Uri.parse(uris.get(0)), captions.get(0), compressed);
    } else {
        // Multiple images — send as multi-image message
        sendMultiImageMessage(uris, captions, compressed);
    }
    clearReply();
}

// Step D: sendMultiImageMessage() — upload all then send ONE message
private void sendMultiImageMessage(List<String> uriStrings, List<String> captions, boolean compressed) {
    int total = uriStrings.size();
    List<String> uploadedUrls = new ArrayList<>(Collections.nCopies(total, null));
    AtomicInteger completed   = new AtomicInteger(0);

    // Show uploading toast
    Snackbar bar = Snackbar.make(binding.getRoot(),
            "Uploading " + total + " photos...", Snackbar.LENGTH_INDEFINITE);
    bar.show();

    for (int i = 0; i < total; i++) {
        final int index = i;
        Uri uri = Uri.parse(uriStrings.get(i));

        // Optionally compress before upload
        if (compressed) {
            Uri compressedUri = ImageCompressor.compressToUri(this, uri, 85, 1080);
            if (compressedUri != null) uri = compressedUri;
        }

        final Uri finalUri = uri;
        CloudinaryUploader.upload(this, finalUri, "image", cdnUrl -> {
            uploadedUrls.set(index, cdnUrl);
            int done = completed.incrementAndGet();

            runOnUiThread(() -> bar.setText("Uploaded " + done + "/" + total + " photos..."));

            if (done == total) {
                // All uploaded — build and send the message
                runOnUiThread(() -> {
                    bar.dismiss();
                    pushMultiImageMessage(uploadedUrls, captions);
                });
            }
        }, error -> {
            runOnUiThread(() -> {
                bar.dismiss();
                Snackbar.make(binding.getRoot(),
                        "Upload failed — try again", Snackbar.LENGTH_SHORT).show();
            });
        });
    }
}

// Step E: pushMultiImageMessage() — build Firebase message
private void pushMultiImageMessage(List<String> imageUrls, List<String> captions) {
    Message msg      = buildOutgoing();
    msg.type         = "multi_image";
    msg.imageUrls    = imageUrls;       // new field: List<String>
    msg.captions     = captions;        // new field: List<String>
    msg.thumbnailUrl = imageUrls.get(0); // first image as list preview
    msg.text         = "📷 " + imageUrls.size() + " photos";

    String preview = "📷 " + imageUrls.size() + " photos";
    if (viewModel != null) viewModel.sendMessage(msg, preview);
    else pushMessage(msg, preview);
}

// ── PART 2: Message.java — add new fields ─────────────────────────────
public List<String> imageUrls = null;  // for multi-image messages
public List<String> captions  = null;  // per-image captions (same size as imageUrls)

// ── PART 3: MessagePagingAdapter.java — render multi-image grid ───────

// In getItemViewType():
case "multi_image": return TYPE_MULTI_IMAGE;  // new constant = 7

// In onCreateViewHolder():
case TYPE_MULTI_IMAGE:
    View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_message_image_grid, parent, false);
    return new VH(v);

// In onBindViewHolder() — bind multi-image grid:
private void bindMultiImage(VH h, Message msg) {
    if (msg.imageUrls == null || msg.imageUrls.isEmpty()) return;

    h.gridImages.removeAllViews();
    int count = msg.imageUrls.size();

    // Determine column count and cell sizes based on count
    int cols   = count == 1 ? 1 : count == 2 ? 2 : 3;
    int cellPx = count == 1 ? dpToPx(260) : count == 2 ? dpToPx(130) : dpToPx(88);

    h.gridImages.setColumnCount(cols);

    for (int i = 0; i < Math.min(count, 6); i++) {   // show max 6 cells
        final int index   = i;
        final String url  = msg.imageUrls.get(i);
        boolean isLast    = (i == 5 && count > 6);
        int remaining     = count - 6;

        ImageView cell = new ImageView(h.itemView.getContext());
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = lp.height = cellPx;
        lp.setMargins(1, 1, 1, 1);
        cell.setLayoutParams(lp);
        cell.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Glide.with(h.itemView.getContext()).load(url).centerCrop().into(cell);

        if (isLast) {
            // Overlay "+N more" on the last cell
            FrameLayout frame = new FrameLayout(h.itemView.getContext());
            frame.setLayoutParams(lp);
            frame.addView(cell);

            TextView overlay = new TextView(h.itemView.getContext());
            overlay.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            overlay.setBackgroundColor(0x88000000);
            overlay.setText("+" + remaining + "\nmore");
            overlay.setTextColor(0xFFFFFFFF);
            overlay.setTextSize(14f);
            overlay.setGravity(android.view.Gravity.CENTER);
            frame.addView(overlay);

            frame.setOnClickListener(v2 -> openImageViewer(msg, index));
            h.gridImages.addView(frame);
        } else {
            cell.setOnClickListener(v2 -> openImageViewer(msg, index));
            h.gridImages.addView(cell);
        }
    }

    // Caption (first non-empty caption)
    String firstCaption = "";
    if (msg.captions != null) {
        for (String c : msg.captions) {
            if (c != null && !c.isEmpty()) { firstCaption = c; break; }
        }
    }
    h.tvCaption.setVisibility(firstCaption.isEmpty() ? View.GONE : View.VISIBLE);
    h.tvCaption.setText(firstCaption.isEmpty() ? "" :
            firstCaption + (msg.imageUrls.size() > 1 ? " +" + (msg.imageUrls.size()-1) + " more" : ""));

    // Timestamp
    if (h.tvTime != null && msg.timestamp != null) {
        h.tvTime.setText(new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
                .format(new java.util.Date(msg.timestamp)));
    }

    // Bubble gravity
    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) h.llBubbleRoot.getLayoutParams();
    boolean isSent = msg.senderId != null && msg.senderId.equals(currentUid);
    params.gravity = isSent ? android.view.Gravity.END : android.view.Gravity.START;
    h.llBubbleRoot.setBackgroundResource(isSent ? R.drawable.bubble_sent : R.drawable.bubble_received);
    h.llBubbleRoot.setLayoutParams(params);
}

// ── PART 4: openImageViewer() — full-screen swipeable viewer ─────────

private void openImageViewer(Message msg, int startIndex) {
    // Use ViewPager2 full-screen overlay, or launch ImageViewerActivity
    // Pass imageUrls + startIndex
    Intent i = new Intent(context, ImageViewerActivity.class);
    i.putStringArrayListExtra("urls", new ArrayList<>(msg.imageUrls));
    i.putExtra("startIndex", startIndex);
    context.startActivity(i);
}

// ── PART 5: Firebase data structure ──────────────────────────────────
/*
chats/{chatId}/messages/{msgId}:
{
  "type":         "multi_image",
  "imageUrls":    ["https://cdn..../img1.jpg", "https://cdn..../img2.jpg", ...],
  "captions":     ["Caption for photo 1", "", "Caption for photo 3", ...],
  "thumbnailUrl": "https://cdn..../img1.jpg",
  "text":         "📷 3 photos",
  "timestamp":    1717430400000,
  "senderId":     "uid123",
  "status":       "sent"
}
*/

// ── PART 6: AndroidManifest.xml ──────────────────────────────────────
/*
<activity
    android:name="com.callx.app.activities.MultiImagePickerActivity"
    android:theme="@style/Theme.AppCompat.NoActionBar"
    android:windowSoftInputMode="adjustResize"
    android:exported="false"
    android:screenOrientation="portrait"/>
*/
