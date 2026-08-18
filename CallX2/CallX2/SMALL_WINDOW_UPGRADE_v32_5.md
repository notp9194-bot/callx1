# CallX2 — Small Window + PrivacyDirectDialog Feature
## Version: v32.5

---

## Overview

Is update mein **Small Window** aur **PrivacyDirectDialog** feature add kiya gaya hai.
MIUI / ColorOS ke "Small Window" jaisi functionality — chat ya call ko ek draggable
floating mini-window mein open kar sakte ho.

---

## New Files

| File | Description |
|------|-------------|
| `app/.../smallwindow/SmallWindowManager.java` | WindowManager se floating overlay banata hai, drag + minimize support |
| `app/.../smallwindow/SmallWindowService.java` | Foreground service — window ko alive rakhta hai |
| `app/.../smallwindow/PrivacyDirectDialog.java` | Bottom sheet dialog: Lock / App Info / Privacy / Small Window |
| `app/.../res/layout/layout_small_window.xml` | Floating window layout (260×180dp, dark card, drag bar) |
| `app/.../res/layout/layout_small_window_bubble.xml` | Minimized bubble layout (56dp circle, corner) |
| `app/.../res/layout/bottom_sheet_privacy_direct.xml` | PrivacyDirectDialog layout (4 action rows) |
| `app/.../res/drawable/ic_picture_in_picture.xml` | Small Window icon |

---

## Modified Files

| File | Change |
|------|--------|
| `app/src/main/AndroidManifest.xml` | `SmallWindowService` registered with `foregroundServiceType="shortService"` |

---

## How to Use

### 1. PrivacyDirectDialog — Chat mein dialog open karo

```java
// ChatActivity ya ContactsFragment mein (long-press pe)
PrivacyDirectDialog dialog = PrivacyDirectDialog.newInstance(
    userId,    // String — Firebase user ID
    userName,  // String — "Ali Hassan"
    "Online"   // String — status text
);
dialog.show(getSupportFragmentManager(), "privacy_direct");
```

Dialog mein 4 options hain:
- **Lock** — chat lock (AppLockManager se integrate karo)
- **App info** — system app settings khulta hai
- **Privacy settings** — PrivacySecurityActivity khulta hai
- **Small window** — floating window open karta hai ✨

---

### 2. SmallWindowManager — Direct use

```java
// Small window dikhao
SmallWindowManager.getInstance().show(
    getApplicationContext(),
    "Ali Hassan",   // Name
    "Online"        // Status
);

// Band karo
SmallWindowManager.getInstance().dismiss(getApplicationContext());
```

---

### 3. SmallWindowService — Service se start karo (recommended)

```java
Intent svc = new Intent(context, SmallWindowService.class);
svc.putExtra(SmallWindowService.EXTRA_NAME,   "Ali Hassan");
svc.putExtra(SmallWindowService.EXTRA_STATUS, "Online");

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    context.startForegroundService(svc);
} else {
    context.startService(svc);
}
```

---

## Permission Check

`SYSTEM_ALERT_WINDOW` permission manifest mein already thi. PrivacyDirectDialog
runtime check bhi karta hai — agar permission nahi hai to system settings open ho jaata hai.

```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        && !Settings.canDrawOverlays(context)) {
    // Dialog automatically redirects to settings
}
```

---

## Small Window UX

| Action | Result |
|--------|--------|
| Window dikhna | 260×180dp dark floating card, screen ke upar |
| Drag | Anywhere move kar sako |
| Minimize button | 56dp circle bubble — corner mein |
| Bubble tap | Window wapas restore |
| Close button | Window aur service dono band |

---

## Build Notes

- No new Gradle dependencies needed (CardView already used in project)
- `foregroundServiceType="shortService"` — Android 14+ compatible
- `FOREGROUND_SERVICE_SHORT_SERVICE` permission already in manifest (added in v14)
