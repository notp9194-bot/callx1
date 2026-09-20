# CallX2 merged verification upgrade

This drop merges **Get Verification Badge** and **Request Verification** into
one flow:

- Star, Gold, and Platinum tiers
- Monthly, 3 Months, 6 Months, and Yearly periods
- Optional reason field
- Server-owned catalog and prices
- Admin queue with atomic approval of `isVerified`, `badgeTier`, and
  `badgePeriod`
- Admin price editor plus a one-time legacy talent backfill
- Reusable tier pills and avatar overlays backed by a tier-aware cache
- `/star-talent/action` remains available as a compatibility wrapper and
  maps old requests into the merged flow

Default prices are:

| Tier | Monthly | 3 Months | 6 Months | Yearly |
|---|---:|---:|---:|---:|
| Star | ₹195 | ₹395 | ₹795 | ₹1,395 |
| Gold | ₹299 | ₹599 | ₹1,199 | ₹2,099 |
| Platinum | ₹499 | ₹999 | ₹1,999 | ₹3,499 |

## Included deliverables

- `functions/index.js` — merged callable actions and admin actions
- `server_index.js` — Render bridge with `/verification/action` and the
  legacy `/star-talent/action` route
- `firebase_security_rules_UPDATED_pagination_fix.json` — protected request
  and badge fields; normal clients cannot write verification state
- Full Android project under this directory

The app was not built or run, as requested. Only static consistency checks were
performed. Deploy the functions/server and rules, then use the admin
verification screen's **Backfill legacy** action once after deployment.