# ════════════════════════════════════════════════════════════════════
# CallX ProGuard / R8 Rules  (release build)
# ════════════════════════════════════════════════════════════════════

# ── App entry-points ─────────────────────────────────────────────
-keep class com.callx.app.CallxApp { *; }
-keep class com.callx.app.activities.** { *; }
-keep class com.callx.app.services.**   { *; }
-keep class com.callx.app.notifications.** { *; }
-keep class com.callx.app.workers.**   { *; }

# ── Data / Models (Firebase deserialization) ─────────────────────
-keep class com.callx.app.models.** { *; }
-keepclassmembers class com.callx.app.models.** { *; }

# ── Database (Room + SQLCipher) ──────────────────────────────────
-keep class com.callx.app.db.** { *; }
-keep class com.callx.app.db.entity.** { *; }
-keep class com.callx.app.db.dao.**    { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-dontwarn net.sqlcipher.**

# ── Cache layer ──────────────────────────────────────────────────
-keep class com.callx.app.cache.**      { *; }
-keep class com.callx.app.repository.** { *; }
-keep class com.callx.app.sync.**       { *; }

# ── Security / Privacy ──────────────────────────────────────────
-keep class com.callx.app.utils.SecurityManager { *; }
-keep class com.callx.app.utils.SecurityManager$LoginEvent { *; }
-keep class com.callx.app.utils.AppLockManager  { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# FIX [P4-4]: Do NOT add a blanket -keep for com.callx.app.utils.** here.
# Constants.java contains hardcoded config strings. Letting R8 rename the class
# in release builds adds an extra obscurity layer — reverse-engineers must find
# the renamed class before they can locate the strings.
# Only SecurityManager and AppLockManager need to be kept (they use reflection).

# ── Firebase ────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ── WebRTC (Stream) ─────────────────────────────────────────────
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keep class io.getstream.** { *; }
-dontwarn io.getstream.**

# ── ExoPlayer / Media3 ──────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── CameraX ─────────────────────────────────────────────────────
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── Glide ───────────────────────────────────────────────────────
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { **[] $VALUES; public *; }
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl

# ── OkHttp / Okio ───────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Gson ────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ── Paging 3 / RxJava 3 ─────────────────────────────────────────
-keep class androidx.paging.** { *; }
-dontwarn androidx.paging.**
-keep class io.reactivex.rxjava3.** { *; }
-dontwarn io.reactivex.rxjava3.**

# ── Shimmer ─────────────────────────────────────────────────────
-keep class com.facebook.shimmer.** { *; }
-dontwarn com.facebook.shimmer.**

# ── WorkManager ─────────────────────────────────────────────────
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# ── LiTr (video transcoding) ────────────────────────────────────
-keep class com.linkedin.android.litr.** { *; }
-dontwarn com.linkedin.android.litr.**

# ── Enum / Serializable / Parcelable ─────────────────────────────
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
-keep class * implements java.io.Serializable { *; }
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ── R8 full-mode: keep generic signatures ─────────────────────────
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations

# ── Debug info (keep line numbers for crash reports) ─────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
