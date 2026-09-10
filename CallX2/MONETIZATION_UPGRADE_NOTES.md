# Reels monetization upgrade

This archive adds the creator monetization journey shown in the supplied
screenshots and wires it to the existing Reels and admin modules.

## Creator app

- `ReelMonetizationActivity` is reachable from **Settings → Creator Tools →
  Monetization**.
- The screen shows the creator journey, live follower/view/following totals,
  Star/Gold/Platinum payout timing, Starter/Pro/Pro Plus criteria and earnings
  rates, available/lifetime earnings, and payout status.
- Enrollment and payout requests use the callable
  `creatorMonetizationAction`; the client cannot award itself earnings or edit
  payout status.
- Existing `reelCreatorFund/{uid}` records are read as a backwards-compatible
  balance fallback. New records live under `creatorMonetization/{uid}`.

## Admin app

- The dashboard now includes **Reels monetization**.
- Finance and super-admin users can review payout requests, approve them, mark
  them paid, or reject them and restore the creator balance.
- Every payout review is written to `admin_audit`.

## Backend and rules

Deploy the updated `functions/index.js` so these functions exist:

- `creatorMonetizationAction`
- `adminAction` actions `listCreatorPayouts` and `reviewCreatorPayout`
- `settleCreatorReelViews` Realtime Database trigger on `reels/{reelId}/viewsCount`

Merge `firebase_rules/firebase_monetization_rules.json` into the live Realtime
Database rules. It keeps creator balances server-written while allowing each
creator to read only their own monetization record.

Earnings are intentionally server-controlled. A production payment provider
still needs to be connected before `paid` represents a real bank/UPI/PayPal
transfer; the admin action currently records the audited payout decision.
When an enrolled, eligible creator's Reel crosses a rate milestone, the
Realtime Database trigger credits the milestone delta exactly once using the
server-controlled `reelEarnings` ledger.