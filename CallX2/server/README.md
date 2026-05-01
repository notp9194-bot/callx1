# CallX Server v2

Production-grade Node.js backend for CallX Android app.

## Endpoints
- `GET /healthz` — health check
- `POST /cloudinary/sign` — signed direct uploads (resource_type aware)
- `POST /notify` — push to single user (calls / messages)
- `POST /notify/group` — fanout push to group members
- `POST /notify/status` — fanout push to all contacts when posting status

## Render env vars (Free plan)
- `FIREBASE_SERVICE_ACCOUNT` — full JSON of Firebase admin service account (one line)
- `CLOUDINARY_CLOUD_NAME` = `dvqqgqdls`
- `CLOUDINARY_API_KEY` — from Cloudinary console
- `CLOUDINARY_API_SECRET` — from Cloudinary console
- `DB_URL` = `https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app`

## 503 image upload fix
Yeh server ab `resource_type` (image / video / raw) ko sahi handle karta hai
aur Cloudinary missing config par clear error deta hai.
