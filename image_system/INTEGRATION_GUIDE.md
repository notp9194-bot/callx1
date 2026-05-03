# 🚀 CallX2 Image System — Integration Guide

**v19 → v20 Image System Upgrade**

---

## 📦 Files Delivered

```
image_system/
├── utils/
│   ├── ImageCompressor.java     ← Core compression (5MB → 300-800KB)
│   ├── ImageUploader.java       ← Firebase dual upload (thumb + full)
│   ├── GlideImageLoader.java    ← Smart progressive loading
│   ├── ImagePickerHelper.java   ← Gallery + Camera picker
│   └── NetworkUtils.java        ← Network quality detection
├── workers/
│   └── ImageUploadWorker.java   ← WorkManager offline queue
├── activities/
│   ├── ChatActivityImageGuide.java  ← Integration guide (copy snippets)
│   └── ImageViewerActivity.java     ← Full-screen viewer
└── layouts/
    ├── activity_image_viewer.xml    ← Full-screen viewer layout
    └── item_message_image.xml       ← Chat bubble for images
```

---

## 🔧 Step 1: Add Dependencies to `app/build.gradle`

```groovy
// Already in your project:
// implementation 'com.github.bumptech.glide:glide:4.16.0'
// implementation 'androidx.work:work-runtime:2.9.0'
// implementation 'com.google.firebase:firebase-storage'

// ADD THESE NEW ones:
implementation 'com.github.chrisbanes:PhotoView:2.3.0'        // pinch-to-zoom
implementation 'androidx.exifinterface:exifinterface:1.3.7'    // EXIF rotation fix
```

---

## 🔧 Step 2: Add Files to Project

Copy these to `app/src/main/java/com/callx/app/`:

| Source File | Destination |
|------------|------------|
| `utils/ImageCompressor.java`   | `utils/ImageCompressor.java`   |
| `utils/ImageUploader.java`     | `utils/ImageUploader.java`     |
| `utils/GlideImageLoader.java`  | `utils/GlideImageLoader.java`  |
| `utils/ImagePickerHelper.java` | `utils/ImagePickerHelper.java` |
| `utils/NetworkUtils.java`      | `utils/NetworkUtils.java`      |
| `workers/ImageUploadWorker.java` | `workers/ImageUploadWorker.java` |
| `activities/ImageViewerActivity.java` | `activities/ImageViewerActivity.java` |

Copy layouts to `app/src/main/res/layout/`:

| Source | Destination |
|--------|------------|
| `layouts/activity_image_viewer.xml` | `layout/activity_image_viewer.xml` |
| `layouts/item_message_image.xml`    | `layout/item_message_image.xml`    |

---

## 🔧 Step 3: AndroidManifest.xml changes

```xml
<!-- Add permission (API 33+) -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.CAMERA" />

<!-- Register ImageViewerActivity -->
<activity
    android:name=".activities.ImageViewerActivity"
    android:theme="@style/Theme.AppCompat.NoActionBar" />

<!-- FileProvider for camera capture -->
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

---

## 🔧 Step 4: Create `res/xml/file_paths.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="images" path="." />
</paths>
```

---

## 🔧 Step 5: Integrate in ChatActivity

```java
// 1. Field add karo
private ImagePickerHelper imagePicker;

// 2. onCreate() me
imagePicker = new ImagePickerHelper(this, uri -> {
    // Show progress
    progressBar.setVisibility(View.VISIBLE);

    // Background compress (no UI freeze)
    ImageCompressor.compress(this, uri, new ImageCompressor.Callback() {
        @Override
        public void onSuccess(ImageCompressor.Result result) {
            sendImageMessage(result);
        }
        @Override
        public void onError(Exception e) {
            progressBar.setVisibility(View.GONE);
            Toast.makeText(ChatActivity.this, "Error", Toast.LENGTH_SHORT).show();
        }
    });
});

// 3. Button click
btnAttach.setOnClickListener(v -> imagePicker.openGallery());
btnCamera.setOnClickListener(v -> imagePicker.openCamera());
```

---

## 🔧 Step 6: In MessageAdapter — show image

```java
// In onBindViewHolder for image type:
GlideImageLoader.loadProgressive(
    context,
    message.getThumbUrl(),
    message.getFullUrl(),
    holder.ivImage
);

// Click → full-screen
holder.ivImage.setOnClickListener(v -> {
    Intent intent = new Intent(context, ImageViewerActivity.class);
    intent.putExtra(ImageViewerActivity.EXTRA_THUMB_URL, message.getThumbUrl());
    intent.putExtra(ImageViewerActivity.EXTRA_FULL_URL, message.getFullUrl());
    context.startActivity(intent);
});

// Preload next image (smooth scroll)
if (position + 1 < messages.size()) {
    Message next = messages.get(position + 1);
    if ("image".equals(next.getType())) {
        GlideImageLoader.preload(context, next.getThumbUrl(), next.getFullUrl());
    }
}
```

---

## 🔧 Step 7: Firebase message structure

Image message format in Firebase Realtime DB:

```json
{
  "messages": {
    "MSG_ID": {
      "type":          "image",
      "senderId":      "UID",
      "timestamp":     1234567890,
      "thumbUrl":      "https://firebasestorage.../thumb/xxx.webp",
      "fullUrl":       "https://firebasestorage.../full/xxx.webp",
      "status":        "sent",
      "mediaLocalPath": null
    }
  }
}
```

---

## 🔧 Step 8: Firebase Storage rules

```
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /images/thumb/{imageId} {
      allow read: if request.auth != null;
      allow write: if request.auth != null
                   && request.resource.size < 100 * 1024;  // max 100KB thumb
    }
    match /images/full/{imageId} {
      allow read: if request.auth != null;
      allow write: if request.auth != null
                   && request.resource.size < 2 * 1024 * 1024;  // max 2MB
    }
  }
}
```

---

## ✅ Final Result

```
User selects image (any size, e.g. 5MB)
         ↓
ImagePickerHelper → Uri
         ↓
ImageCompressor (background thread, no UI freeze)
   ├── Fix EXIF rotation
   ├── Resize to max 1280px
   └── Compress to WebP
         ↓
Two files generated:
   ├── thumb_xxx.webp  (~30KB)
   └── full_xxx.webp   (~500KB)
         ↓
Firebase placeholder message (status: "uploading")
         ↓
ImageUploader.upload()
   ├── images/thumb/xxx.webp  (0-40% progress)
   └── images/full/xxx.webp   (40-100% progress)
         ↓
Firebase message updated (thumbUrl + fullUrl + status: "sent")
         ↓
Chat list: GlideImageLoader.loadProgressive()
   ├── thumbUrl → instant display
   └── fullUrl  → replaces thumb (fade)
         ↓
User taps → ImageViewerActivity (pinch-to-zoom)
```

---

## 📊 Size Comparison

| Image | Before | After Thumb | After Full |
|-------|--------|-------------|------------|
| 5MB JPEG | 5MB | ~30KB | ~500KB |
| 3MB JPEG | 3MB | ~25KB | ~350KB |
| 1MB JPEG | 1MB | ~20KB | ~200KB |
| 500KB JPEG | 500KB | ~15KB | ~150KB |

---

## 🔥 Key Points

1. **UI freeze nahi hoga** — sab background thread pe
2. **EXIF fix** — image kabhi ulta nahi aayega
3. **Offline support** — WorkManager retry karega
4. **Slow network** — thumbnail only dikhega, data save
5. **Auto retry** — upload 3 baar try karega before fail
6. **Progressive load** — blur → sharp instantly
