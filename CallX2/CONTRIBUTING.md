# CallX2 — Resource Rules (MUST READ)

## ❌ Kabhi mat karo

App module (`app/src/main/res/`) mein ye folders SIRF app-specific cheezein hain:
- Chat screens
- Call screens  
- Home/navigation

**Reel, Status, X, YouTube module ke kisi bhi file ko
`app/src/main/res/` mein copy MAT karo — kabhi nahi।**

---

## ✅ Sahi jagah

| Feature | Java/Kotlin | Layout/Drawable |
|---------|-------------|-----------------|
| Reels | `feature-reels/src/main/java/` | `feature-reels/src/main/res/` |
| Chat | `feature-chat/src/main/java/` | `feature-chat/src/main/res/` |
| Calls | `feature-calls/src/main/java/` | `feature-calls/src/main/res/` |
| Shared icons/colors | `core/src/main/res/` | `core/src/main/res/` |
| App-only | `app/src/main/java/` | `app/src/main/res/` |

---

## 🔴 Naya reel feature add karna ho to

1. Java file → `feature-reels/src/main/java/`
2. Layout XML → `feature-reels/src/main/res/layout/`
3. Activity register → `feature-reels/src/main/AndroidManifest.xml`
4. `app/build.gradle` mein kuch mat chhedna

---

## ⚠️ Duplicate kya hota hai aur kyun bura hai

Agar `activity_reel_upload.xml` dono jagah ho:
- `app/src/main/res/layout/` ← YE JEETEGA (galat)
- `feature-reels/src/main/res/layout/` ← ignore hoga

Matlab feature-reels mein kitna bhi kaam karo,
app module wala purana file override kar dega।
Features kaam nahi karenge, crash aayega।

---

## Quick checklist (commit se pehle)

- [ ] Meri nayi file kisi aur module mein already toh nahi hai?
- [ ] Maine `app/src/main/res/` mein koi reel/chat/calls file toh copy nahi ki?
- [ ] Activity `feature-*` ke manifest mein register ki, `app` ke nahi?