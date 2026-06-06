# Android App Links — Verified Links Upgrade

## Kya kiya gaya

### 1. AndroidManifest.xml — 2 jagah update
- **MainActivity** mein HTTPS App Link intent-filter add kiya
  - Domain: `callx-server.onrender.com`
  - Scheme: `https`
  - autoVerify: `true`

- **JoinGroupActivity** mein HTTPS intent-filter add kiya
  - Path prefix: `/join`
  - Pehle wala `callx://join` custom scheme bhi rakha

### 2. index.js (Server) — 2 routes add kiye
- `GET /.well-known/assetlinks.json` — Android OS yahi verify karta hai
- `GET /assetlinks.json` — manual test ke liye

## SHA-256 Certificate
```
92:31:CD:9F:90:15:45:54:3B:92:D8:21:FC:6E:1F:DC:D5:40:8B:F0:69:04:96:85:BD:30:99:50:1A:EB:5D:03
```

## Verify karne ka tarika (app install ke baad)
```bash
adb shell pm get-app-links com.callx.app
```
Output mein `callx-server.onrender.com: verified` dikhna chahiye.

## Live URL
https://callx-server.onrender.com/.well-known/assetlinks.json
