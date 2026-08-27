package com.callx.app.comments;

/**
 * A single @mention autocomplete suggestion — uid + display name + last
 * known avatar URL, built from commenters/repliers seen in the current
 * thread (see ReelCommentFragment#registerMentionCandidate).
 *
 * Previously ReelCommentFragment tracked candidates as a bare
 * Map<String,String> (lowercase name → uid) with no avatar, and rebuilt the
 * proper-cased display name on every suggestion render by linearly scanning
 * allComments (capitalizeFromCandidate) — O(comments) work repeated for
 * every chip on every keystroke. Storing the resolved name + avatar here
 * once, at registration time, removes both problems.
 */
public class MentionCandidate {
    public final String uid;
    public final String name;
    public final String avatarUrl;

    public MentionCandidate(String uid, String name, String avatarUrl) {
        this.uid = uid;
        this.name = name;
        this.avatarUrl = avatarUrl;
    }
}
