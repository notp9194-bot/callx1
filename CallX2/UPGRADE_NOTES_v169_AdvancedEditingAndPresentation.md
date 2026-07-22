# v169 — Advanced Text Editing Suite + Presentation Message System

## Summary

Two major systems added on top of v168's expandable input bar:

1. **AdvancedRichTextController** — 7 additional formatting controls in a
   horizontally scrollable second toolbar row: text colour, highlight colour,
   text size (presets + seekbar), paragraph alignment, font family, letter
   spacing, and line height.

2. **Presentation Message System** — a full slide-editor + canvas renderer:
   - `PresentationMessageEditor` — full-screen slide editor: text blocks,
     background (20 gradient presets + gallery photo), aspect ratio toggle,
     dark overlay toggle, per-block colour/size/align/font
   - `PresentationMessage` — data model (JSON-serialisable, stored in Firebase
     under `presentationData` on a `type:"presentation"` message)
   - `PresentationCanvasView` — custom Canvas view used in both the editor
     live preview AND in the chat RecyclerView bubble — zero XML inflate,
     StaticLayout caching, BitmapShader background, overlay gradient

---

## Files added

### New Java files

| File | Description |
|------|-------------|
| `feature-chat/.../conversation/AdvancedRichTextController.java` | 7 advanced format controls; inline popup windows (no BottomSheet dependency); ColorPickerPopup, TextSizePopup, FontFamilyPopup, SliderPopup inner classes; pre-API-29 LineHeightSpan fallback |
| `feature-chat/.../conversation/models/PresentationMessage.java` | Data model: AspectRatio, Theme, TextBlock (role/color/size/font/align/bold/italic/…), JSON serialiser + minimal parser |
| `feature-chat/.../conversation/presentation/PresentationMessageEditor.java` | Full-screen slide editor built entirely in code (no XML inflate); 20 gradient presets, gallery photo picker, aspect-ratio toggle, per-block mini-toolbar, live PresentationCanvasView preview, "Send ➤" callback |
| `feature-chat/.../conversation/canvas/PresentationCanvasView.java` | Canvas bubble view: BitmapShader bg, StaticLayout per block (cached), overlay LinearGradient, rounded clip via Path, drop-shadow, adapts to any aspect ratio |

### New layout files

| File | Description |
|------|-------------|
| `feature-chat/src/main/res/layout/layout_advanced_format_toolbar.xml` | Second toolbar row (HorizontalScrollView): 7 advanced buttons + Presentation "Slide" button |
| `feature-chat/src/main/res/layout/item_presentation_bubble.xml` | RecyclerView item wrapper for presentation bubbles; wraps PresentationCanvasView + timestamp/tick row |

### New drawable files

| File | Description |
|------|-------------|
| `bg_advanced_toolbar.xml` | Light grey background for the second toolbar row |
| `bg_presentation_btn.xml` | Blue-tint pill button for the "Slide" mode launcher |
| `ic_letter_spacing.xml` | Vector — A↔A letter spacing icon |
| `ic_line_height.xml` | Vector — line-height with vertical arrows icon |
| `ic_presentation.xml` | Vector — monitor/slide icon (blue) |

---

## Integration steps

### 1 — Add the advanced toolbar row below the basic toolbar in `view_chat_input_bar.xml`

```xml
<!-- After the existing llFormatToolbar include: -->
<include
    layout="@layout/layout_advanced_format_toolbar"
    android:id="@+id/llAdvancedToolbar"
    android:layout_width="match_parent"
    android:layout_height="44dp"
    android:visibility="gone"/>

<View
    android:id="@+id/divAdvancedToolbar"
    android:layout_width="match_parent"
    android:layout_height="0.5dp"
    android:background="#22000000"
    android:visibility="gone"/>
```

### 2 — Wire AdvancedRichTextController in ChatActivity / ConversationFragment

```java
AdvancedRichTextController advCtrl =
    new AdvancedRichTextController(context, binding.etMessageInput, getSupportFragmentManager());

advCtrl.bindAdvancedToolbar(
    binding.btnFmtTextColor,
    binding.btnFmtHighlight,
    binding.btnFmtSize,
    binding.btnFmtAlign,
    binding.btnFmtFont,
    binding.btnFmtLetterSpacing,
    binding.btnFmtLineHeight
);

// Forward selection changes so button indicators stay accurate:
// Override onSelectionChanged in your EditText subclass (or use a TextWatcher):
binding.etMessageInput.addTextChangedListener(new TextWatcher() {
    @Override public void afterTextChanged(Editable s) {
        advCtrl.onSelectionChanged(
            binding.etMessageInput.getSelectionStart(),
            binding.etMessageInput.getSelectionEnd()
        );
    }
    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
});
```

Show/hide the advanced toolbar together with the basic toolbar in
`ChatInputBarController.animateExpand()` / `animateCollapse()`:

```java
// In animateExpand() (already shows llFormatToolbar):
binding.llAdvancedToolbar.setVisibility(View.VISIBLE);
binding.divAdvancedToolbar.setVisibility(View.VISIBLE);

// In animateCollapse() (after basic toolbar fade-out):
binding.llAdvancedToolbar.setVisibility(View.GONE);
binding.divAdvancedToolbar.setVisibility(View.GONE);
```

### 3 — Wire the "Slide" button to PresentationMessageEditor

```java
PresentationMessageEditor presentationEditor = new PresentationMessageEditor(
    this,                                // context (Activity)
    (ViewGroup) getWindow().getDecorView().getRootView(),
    getSupportFragmentManager(),
    pm -> {
        // pm = fully built PresentationMessage
        // 1. If pm.bgImageUrl is a local content:// URI, upload to Cloudinary first:
        if (pm.bgImageUrl != null && pm.bgImageUrl.startsWith("content://")) {
            chatMediaController.uploadPresentationBg(pm, uploadedUrl -> {
                pm.bgImageUrl = uploadedUrl;
                sendPresentationMessage(pm);
            });
        } else {
            sendPresentationMessage(pm);
        }
    }
);

// In your "Slide" button click listener (btnFmtPresentation):
binding.btnFmtPresentation.setOnClickListener(v -> presentationEditor.show());

// In onActivityResult:
@Override
protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == PresentationMessageEditor.REQ_PICK_BG_IMAGE
            && resultCode == RESULT_OK && data != null && data.getData() != null) {
        presentationEditor.onBgImagePicked(data.getData());
    }
}
```

### 4 — sendPresentationMessage() — Firebase write

```java
private void sendPresentationMessage(PresentationMessage pm) {
    String msgId = chatRef.push().getKey();
    Map<String, Object> msg = new HashMap<>();
    msg.put("type",             "presentation");
    msg.put("presentationData", pm.toJson());
    msg.put("senderId",         currentUid);
    msg.put("timestamp",        ServerValue.TIMESTAMP);
    msg.put("status",           "sent");
    // Thumb for notification preview
    if (pm.bgImageThumbUrl != null) msg.put("thumbnailUrl", pm.bgImageThumbUrl);
    chatRef.child(msgId).setValue(msg);
}
```

### 5 — MessagePagingAdapter — route "presentation" to PresentationCanvasView

In `isCanvasEligible()`, add:
```java
if ("presentation".equals(msg.getType())) return true;
```

In `onCreateViewHolder()`, add a branch for the presentation type:
```java
case "presentation": {
    PresentationCanvasView pcv = new PresentationCanvasView(parent.getContext());
    // same LayoutParams as MessageBubbleCanvasView holders
    pcv.setLayoutParams(new RecyclerView.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return new PresentationViewHolder(pcv);
}
```

In `bindCanvasMessage()`, add:
```java
if ("presentation".equals(msg.getType())) {
    PresentationCanvasView pcv = (PresentationCanvasView) holder.itemView;
    PresentationMessage pm = PresentationMessage.fromJson(
        msg.getPresentationData() != null ? msg.getPresentationData() : "{}");

    if (pm.bgImageThumbUrl != null && !pm.bgImageThumbUrl.isEmpty()) {
        Glide.with(pcv.getContext())
            .asBitmap()
            .load(pm.bgImageThumbUrl)
            .into(new CustomTarget<Bitmap>() {
                @Override public void onResourceReady(@NonNull Bitmap bmp,
                    @Nullable Transition<? super Bitmap> t) {
                    pcv.bindPresentation(pm, bmp);
                }
                @Override public void onLoadCleared(@Nullable Drawable p) {
                    pcv.bindPresentation(pm, null);
                }
            });
    } else {
        pcv.bindPresentation(pm, null);
    }
    return;
}
```

Also add `getPresentationData()` to your `Message` / `MessageEntity` model:
```java
@Nullable
public String getPresentationData() { return presentationData; }
```

And the Firebase column:
```java
// MessageEntity.java
@ColumnInfo(name = "presentationData")
@Nullable public String presentationData;
```

---

## Advanced toolbar — what each control does

| Button | Span applied | Scope |
|--------|-------------|-------|
| **A** (text color) | `ForegroundColorSpan` | Selection or pending cursor |
| **A** (highlight) | `BackgroundColorSpan` (semi-transparent) | Selection or pending cursor |
| **14sp** (size) | `AbsoluteSizeSpan(sp, true)` | Selection or pending cursor |
| **≡L** (align) | `AlignmentSpan.Standard` | Full paragraph (auto-expanded) |
| **Sans** (font) | `TypefaceSpan(family)` | Selection or pending cursor |
| ↔ (letter spacing) | `LetterSpacingSpan` (API 21+) | Selection or pending cursor |
| ↕ (line height) | `LineHeightSpan.Standard` (API 29+) / `CustomLineHeightSpan` | Selection or pending cursor |

---

## Presentation editor — feature list

| Feature | Detail |
|---------|--------|
| Text blocks | Up to 5 blocks; Title / Body / Caption roles; tap to edit inline |
| Per-block style | Text colour (20 palette) · Size (presets + seekbar) · Align (L/C/R) · Bold · Italic · Font family |
| Background — solid | 20 gradient presets (tap swatch → instant preview update) |
| Background — photo | Gallery picker → decoded at ½ resolution → BitmapShader in canvas |
| Dark overlay | Toggle → LinearGradient(top: #88000000 → transparent → transparent → #AA000000) for legibility |
| Aspect ratio | 16:9 · 1:1 · 9:16 — card resizes instantly in preview |
| Theme | Auto-set (LIGHT/DARK) from background luminance when a colour swatch is picked |
| Live preview | `PresentationCanvasView` inside the editor redraws on every edit (120ms debounce) |
| Send | Syncs EditText content → validates (non-empty) → dismiss → `PresentationSendCallback.onSend(pm)` |

---

## PresentationCanvasView — performance notes

| Property | Implementation |
|----------|---------------|
| Background | `BitmapShader` with `Matrix.setRectToRect()` — no per-frame scale allocation |
| Text | `StaticLayout` per block, rebuilt only when (text + width) changes |
| Overlay | `LinearGradient` created once in `onMeasure` |
| Rounded corners | `Canvas.clipPath()` — no `ViewOutline` / hardware layer cost |
| Shadow | `Paint.setShadowLayer()` + `LAYER_TYPE_SOFTWARE` on the view only |
| Fling | O(1) draw per frame after first layout — same as MessageBubbleCanvasView |

---

## DB migration required

Add `presentationData TEXT` column to `messages` table:

```java
static final Migration MIGRATION_X_Y = new Migration(X, Y) {
    @Override
    public void migrate(@NonNull SupportSQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD COLUMN presentationData TEXT");
    }
};
```

Bump `AppDatabase.version` and register the migration.

---

## Not verified by build

No SDK toolchain in this environment. Traced through:
- AdvancedRichTextController popup lifecycles
- PresentationMessageEditor full-screen overlay animation
- PresentationCanvasView onMeasure → blockLayouts → onDraw path
- MessagePagingAdapter routing for "presentation" type
- Firebase write structure for presentationData

Please build and device-test:
1. Expand input → tap "A" (color) → pick a colour → type → text should appear in that colour.
2. Select text → tap size → pick 28sp → selection should enlarge.
3. Expand → tap "Slide" → editor opens → pick gradient → type title → "Send ➤" → bubble appears in chat as a styled card.
4. Fast-fling 50+ presentation messages → no jank (StaticLayout cache).
5. Rotate device → presentation canvas re-measures to new width correctly.

---

## Files added (complete list)

```
feature-chat/src/main/java/com/callx/app/conversation/
  AdvancedRichTextController.java
  models/
    PresentationMessage.java
  presentation/
    PresentationMessageEditor.java
  canvas/
    PresentationCanvasView.java

feature-chat/src/main/res/layout/
  layout_advanced_format_toolbar.xml
  item_presentation_bubble.xml

feature-chat/src/main/res/drawable/
  bg_advanced_toolbar.xml
  bg_presentation_btn.xml
  ic_letter_spacing.xml
  ic_line_height.xml
  ic_presentation.xml
```
