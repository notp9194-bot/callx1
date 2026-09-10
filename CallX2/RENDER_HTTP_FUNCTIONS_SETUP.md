# Render HTTP Functions Setup

The creator and admin actions no longer call Firebase Callable Functions from
the Android apps. They call the existing Render server with a Firebase ID
token:

- `POST /verification/action`
- `POST /star-talent/action`
- `POST /creator-monetization/action`
- `POST /milestone-earnings/action`
- `POST /admin/action`

The Render `index.js` loads `functions/index.js` as local handler source. It
does not deploy or invoke Firebase Cloud Functions; the source is only reused
by the Render process. Keep the `functions/` directory beside `index.js` in
the Render service, or set `CALLX_FUNCTIONS_FILE` to the deployed file path.

Required Render environment variables remain the same:

- `FIREBASE_SERVICE_ACCOUNT`
- `DB_URL` (optional; the existing default is retained)
- the existing Cloudinary variables used by the server

The database triggers are polled by the Render process. Scheduled jobs retain
their original 5-minute, 5-minute, and 30-minute intervals.