# CallX2 — Version Auto-Increment Guide

## Problem Jo Solve Hua
Pehle har build pe versionCode same (103) rehta tha.
Android same versionCode pe UPGRADE nahi karta — fresh install ho jaata tha → login jaati thi.

## Ab Kaise Kaam Karta Hai

### version.properties (root mein)
```
VERSION_CODE=104
```
Yeh file track karti hai current version number ko.

### Automatic Increment
Jab bhi tum **Build → Generate Signed APK** karo (ya `gradlew assembleRelease`):
- version.properties mein VERSION_CODE automatically +1 ho jaata hai
- APK ka versionCode naya number hota hai
- Phone pe naya APK install karne pe **"Upgrade"** option aata hai
- Login save rehti hai ✅

### Android Studio Run button
Run button se version NAHI badhta (woh sirf debug ke liye hai).
Sirf APK banate waqt badhta hai.

---

## Pehli Baar Setup

1. `version.properties` mein current installed APK ka version daalo:
   ```
   VERSION_CODE=103
   ```
   (Jo bhi abhi phone pe installed hai)

2. Phir **Build → Generate Signed APK** karo → version 104 ka APK banega

3. Phone pe install karo → "Upgrade" option aayega, login save rahegi ✅

---

## In-App Update Dialog (Optional)

Agar chahte ho ki app khud bataye "update available hai":

Firebase Console → Realtime Database mein add karo:
```json
{
  "app_config": {
    "latest_version_code": 105,
    "latest_version_name": "3.105",
    "apk_download_url": "https://aapka-apk-link.com/callx.apk",
    "force_update": false,
    "update_message": "Nayi features aur bug fixes!"
  }
}
```
AppUpdateManager.java automatically check karega aur dialog dikhayega.
