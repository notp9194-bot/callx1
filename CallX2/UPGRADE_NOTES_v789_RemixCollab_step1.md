# v789 — Remix/Collab upgrade, step 1 (data layer)
- Rules: `remixRequests/{owner}/{id}`, `remixRequestsSent/{uid}/{id}` (see firebase_rules_FINAL_v789.json).
- Function: `expireRemixRequests` (hourly, pending -> expired after 48h).
- App: `feature-reels/.../social/RemixRequestHelper.java` (send / cancel / decide / watch).
- NOT done yet: screens (mode chooser, request permission, status, select post, edit, share, profile Collabs), push for new request.
