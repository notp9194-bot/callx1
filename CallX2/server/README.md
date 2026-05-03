# CallX Server v3 — Production Backend

Express.js server deployed on [Render](https://render.com).

## New in v3 (Production Upgrade)

- **TURN credentials endpoint** (`GET /turn/credentials`) — HMAC-based short-lived credentials for coturn/Metered TURN
- **Rate limiting** — per-route limits prevent abuse
- **Helmet** — security headers (XSS, clickjacking, MIME-sniffing protection)
- **Call log endpoint** (`POST /call/log`) — server-side analytics for call records
- **Global error handler** — structured error responses

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | Health + config status |
| GET | `/healthz` | Quick health check |
| GET | `/turn/credentials` | Short-lived TURN credentials (HMAC) |
| POST | `/cloudinary/sign` | Signed upload params for Cloudinary |
| POST | `/notify` | FCM push to a single user |
| POST | `/notify/group` | FCM fan-out to all group members |
| POST | `/notify/status` | FCM fan-out to all contacts |
| POST | `/group/markRead` | Reset unread counter for a group member |
| POST | `/call/log` | Server-side call record (analytics) |

## Required Environment Variables (Render Dashboard)

| Variable | Description |
|----------|-------------|
| `FIREBASE_SERVICE_ACCOUNT` | Firebase Admin SDK JSON (stringified) |
| `DB_URL` | Firebase Realtime DB URL |
| `CLOUDINARY_CLOUD_NAME` | Cloudinary cloud name |
| `CLOUDINARY_API_KEY` | Cloudinary API key |
| `CLOUDINARY_API_SECRET` | Cloudinary API secret |
| `TURN_SECRET` | HMAC secret for TURN credential generation |
| `TURN_HOST` | TURN server hostname (e.g. `turn.yourserver.com`) |
| `TURN_PORT` | TURN server port (default: `3478`) |

## TURN Server Setup

1. Deploy [coturn](https://github.com/coturn/coturn) on any VPS or use a managed TURN (Metered, Xirsys).
2. Set `static-auth-secret=<your_secret>` in `turnserver.conf`.
3. Set `TURN_SECRET` and `TURN_HOST` env vars on Render.
4. The Android client will automatically fetch credentials from `GET /turn/credentials`.

## Deploy (Render)

```
Build Command:  npm install
Start Command:  node index.js
```
