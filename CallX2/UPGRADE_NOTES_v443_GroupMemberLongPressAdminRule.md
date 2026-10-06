# v443 — Group Info member long-press + admin-vs-admin rule
6. Long-press on a member row opens the same options menu as the 3-dot button (with haptic feedback). Own row -> profile.
7. Admin rule: admins can only Make Admin / Remove regular members. Revoke Admin / Remove on another admin is creator-only (menu hides it for non-creators + guard in GroupInfoActivity#handleMemberAction with toast). Creator can never be changed.
   Creator detected via groups/{id}/adminUid or own member role == "creator".
