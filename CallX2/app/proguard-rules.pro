-keep class com.callx.app.models.** { *; }
-keepclassmembers class com.callx.app.models.** { *; }
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { **[] $VALUES; public *; }
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class com.callx.app.CallxApp { *; }
-keep class com.callx.app.activities.** { *; }
-keep class com.callx.app.services.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Privacy & Security - new classes
-keep class com.callx.app.utils.SecurityManager { *; }
-keep class com.callx.app.utils.SecurityManager$LoginEvent { *; }
-keep class com.callx.app.utils.AppLockManager { *; }
-keep class com.callx.app.activities.PrivacySecurityActivity { *; }
-keep class com.callx.app.activities.TwoStepVerificationActivity { *; }
-keep class com.callx.app.activities.LoginActivityLogActivity { *; }
-keep class com.callx.app.activities.LockScreenActivity { *; }
-keep class com.callx.app.activities.AppLockActivity { *; }

# EncryptedSharedPreferences (security-crypto)
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
