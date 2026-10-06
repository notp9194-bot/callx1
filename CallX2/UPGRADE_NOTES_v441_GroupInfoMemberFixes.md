# v441 — Group Info member fixes
1. Non-admin members can now use "Message" (was blocked by admin-only gate in GroupInfoActivity#handleMemberAction).
2. "View Profile" now opens UserProfileActivity (uid/name/photo); own row opens ProfileActivity. Previously opened ChatActivity.
3. Member row 3-dot icon: ic_send (rotated) -> ic_more_vert, rotation removed (item_group_member.xml).
