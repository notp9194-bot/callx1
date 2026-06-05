package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.explore.HashtagReelsActivity;
import com.callx.app.music.SoundDetailActivity;
import com.callx.app.conversation.ChatActivity;
import com.callx.app.group.GroupChatActivity;

/**
 * DeepLinkRouterActivity — Handles ALL deep links for CallX app.
 *
 * Supported URL patterns:
 *
 * USER / PROFILE
 *   /u/{uid}                → ProfileActivity
 *   /chat/{uid}             → ChatActivity (1-on-1)
 *
 * GROUP
 *   /join/{groupId}         → JoinGroupActivity
 *   /g/{groupId}            → GroupChatActivity
 *
 * REELS
 *   /reel/{reelId}          → SingleReelPlayerActivity
 *   /reels/user/{uid}       → UserReelsActivity
 *   /reels/hashtag/{tag}    → HashtagReelsActivity
 *   /reels/sound/{soundId}  → SoundDetailActivity
 *
 * STATUS
 *   /status/{uid}           → StatusViewerActivity
 *
 * APP SECTIONS (no param needed)
 *   /chats                  → MainActivity TAB_CHATS
 *   /calls                  → MainActivity TAB_CALLS
 *   /reels                  → MainActivity TAB_REELS
 *   /groups                 → MainActivity TAB_GROUPS
 *
 * MISC
 *   /search?q={query}       → SearchActivity
 *   /notifications          → AllNotificationsActivity
 */
public class DeepLinkRouterActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Must be logged in to use deep links
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, AuthActivity.class));
            finish();
            return;
        }

        Uri uri = getIntent().getData();
        if (uri == null) {
            goHome();
            return;
        }

        route(uri);
        finish();
    }

    private void route(Uri uri) {
        String path = uri.getPath();          // e.g. "/u/uid123"
        if (path == null || path.isEmpty()) { goHome(); return; }

        // Remove leading slash and split
        String[] parts = path.replaceFirst("^/", "").split("/", 3);
        String section = parts.length > 0 ? parts[0] : "";
        String param1  = parts.length > 1 ? parts[1] : "";
        String param2  = parts.length > 2 ? parts[2] : "";

        switch (section) {

            // ── USER PROFILE ──────────────────────────────────────────────
            case "u":
            case "profile": {   // callx://profile/{uid} aur callx://u/{uid} dono support
                if (param1.isEmpty()) { goHome(); return; }
                Intent i = new Intent(this, ProfileActivity.class);
                i.putExtra("uid", param1);
                startActivity(i);
                break;
            }

            // ── DIRECT CHAT ───────────────────────────────────────────────
            case "chat": {
                if (param1.isEmpty()) { goHome(); return; }
                Intent i = new Intent(this, ChatActivity.class);
                i.putExtra("uid", param1);
                startActivity(i);
                break;
            }

            // ── GROUP JOIN ────────────────────────────────────────────────
            case "join": {
                if (param1.isEmpty()) { goHome(); return; }
                // Reuse existing JoinGroupActivity via URI
                Uri joinUri = Uri.parse("callx://join/" + param1);
                Intent i = new Intent(Intent.ACTION_VIEW, joinUri);
                i.setClass(this, JoinGroupActivity.class);
                startActivity(i);
                break;
            }

            // ── GROUP CHAT ────────────────────────────────────────────────
            case "g": {
                if (param1.isEmpty()) { goHome(); return; }
                Intent i = new Intent(this, GroupChatActivity.class);
                i.putExtra("groupId", param1);
                startActivity(i);
                break;
            }

            // ── REELS ─────────────────────────────────────────────────────
            case "reel": {
                // /reel/{reelId}
                if (param1.isEmpty()) { goHome(); return; }
                java.util.ArrayList<String> ids = new java.util.ArrayList<>();
                ids.add(param1);
                Intent i = new Intent(this, SingleReelPlayerActivity.class);
                i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
                i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, 0);
                startActivity(i);
                break;
            }

            case "reels": {
                switch (param1) {
                    case "user": {
                        // /reels/user/{uid}
                        Intent i = new Intent(this, UserReelsActivity.class);
                        i.putExtra(UserReelsActivity.EXTRA_UID, param2);
                        startActivity(i);
                        break;
                    }
                    case "hashtag": {
                        // /reels/hashtag/{tag}
                        Intent i = new Intent(this, HashtagReelsActivity.class);
                        i.putExtra(HashtagReelsActivity.EXTRA_HASHTAG, param2);
                        startActivity(i);
                        break;
                    }
                    case "sound": {
                        // /reels/sound/{soundId}
                        Intent i = new Intent(this, SoundDetailActivity.class);
                        i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID, param2);
                        startActivity(i);
                        break;
                    }
                    default:
                        // /reels → open reels tab
                        openMainTab("reels");
                        break;
                }
                break;
            }

            // ── STATUS ────────────────────────────────────────────────────
            case "status": {
                if (param1.isEmpty()) { goHome(); return; }
                Intent i = new Intent(this, StatusViewerActivity.class);
                i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID, param1);
                startActivity(i);
                break;
            }

            // ── SEARCH ────────────────────────────────────────────────────
            case "search": {
                String query = uri.getQueryParameter("q");
                Intent i = new Intent(this, SearchActivity.class);
                if (query != null) i.putExtra("query", query);
                startActivity(i);
                break;
            }

            // ── NOTIFICATIONS ─────────────────────────────────────────────
            case "notifications": {
                startActivity(new Intent(this, AllNotificationsActivity.class));
                break;
            }

            // ── APP SECTIONS (bottom nav tabs) ────────────────────────────
            case "chats":
                openMainTab("chats"); break;
            case "calls":
                openMainTab("calls"); break;
            case "groups":
                openMainTab("groups"); break;

            default:
                goHome();
                break;
        }
    }

    /** Open MainActivity and jump to a specific tab */
    private void openMainTab(String tab) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("open_tab", tab);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    private void goHome() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }
}
