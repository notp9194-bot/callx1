# v653 — Status viewer polish: strings (1), contentDescriptions (2), real Report (4)

Files changed
- feature-status/.../viewer/StatusViewerActivity.java
- feature-status/src/main/res/layout/activity_status_viewer.xml
- feature-status/src/main/res/values/strings_status_viewer.xml (new)

1. Toasts -> strings.xml: all 24 hardcoded toasts (mentions, unavailable status/highlight, original story, profile, music failed,
   sent privately, sharing disabled, stabilizer, delete/archive/highlight actions, mute, add-to-album) now use viewer_toast_*.
   Text unchanged. Parametrised ones use %1$s (e.g. "%1$s muted").
2. Accessibility: existing hardcoded descriptions (verified, song, more, mute, comment, like, remove mention, react with,
   your reaction, view original story, latest comment, like/remove like) moved to strings. Added the missing ones:
   close button, owner avatar, touch layer (tap zones / hold to pause). Decorative backgrounds are importantForAccessibility=no.
3. Report is real: menu -> reason list (7 reasons) -> writes /reports/{ownerUid}/status_{statusId}_{reporterUid}
   {reportedUid, reporterUid, reason, reasonIndex, status:"open", timestamp, contentType:"status", statusId}.
   - Same shape the admin "Reports" screen (functions listAllReports/normaliseReport) already reads -> shows up as a user report.
   - Deterministic key + write-once rule (!data.exists()) = one report per (status, reporter); a repeat shows "already reported".
   - Report entry hidden on your own status. Success / failure toasts; story resumes in every path.
   - Needs the existing rule  reports/$id/$reportId  (.write: auth != null && !data.exists()) to be deployed - it is in
     firebase_security_rules_UPDATED_pagination_fix.json. Moderators removing the status itself is still a manual action
     (admin "removeTarget" has no status path yet).

Not compiled / run here -> build + test: report each reason, report twice, report own status (no menu item), TalkBack on
header / reply bar / reactions, and the toasts in a non-English locale.
