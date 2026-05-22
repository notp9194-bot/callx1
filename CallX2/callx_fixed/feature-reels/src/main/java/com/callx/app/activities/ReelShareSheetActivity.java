package com.callx.app.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import com.callx.app.reels.R;
import com.callx.app.activities.RepostWithCaptionActivity;
import com.callx.app.adapters.ReelContactShareAdapter;
import com.callx.app.models.User;
import com.callx.app.activities.ReelShareToStoryActivity;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.List;

/**
 * ReelShareSheetActivity — Share a reel with rich options.
 *
 * Features:
 *  ✅ Share to individual chat contact (sends deeplink message)
 *  ✅ Copy reel link to clipboard
 *  ✅ Native Android share intent
 *  ✅ Increments sharesCount in Firebase on any share action
 *  ✅ Bottom-sheet style UI (Theme.CallX.Transparent parent + animation)
 */
public class ReelShareSheetActivity extends AppCompatActivity
        implements ReelContactShareAdapter.OnContactShareListener {

    public static final String EXTRA_REEL_ID    = "share_reel_id";
    public static final String EXTRA_VIDEO_URL  = "share_video_url";
    public static final String EXTRA_THUMB_URL  = "share_thumb_url";
    public static final String EXTRA_CAPTION    = "share_caption";
    /** FIX: Pass owner UID so we can check their repost/share privacy setting. */
    public static final String EXTRA_OWNER_UID  = "share_owner_uid";
    /** FIX: Pass creator's allowReposts flag (default true if not passed). */
    public static final String EXTRA_ALLOW_REPOST = "share_allow_repost";

    private static final String DEEP_LINK_PREFIX = com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/reel/";

    private RecyclerView  rvContacts;
    private ProgressBar   progressBar;
    private View          btnCopyLink, btnShareExternal, btnShareToStatus, btnRepostWithCaption;

    private ReelContactShareAdapter  adapter;
    private final List<User>         contacts = new ArrayList<>();

    private String reelId;
    private String videoUrl;
    private String caption;
    private String myUid;
    private String ownerUid;       // FIX: needed to check privacy settings
    private boolean allowRepost;   // FIX: creator can disallow reposts

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_share);

        reelId      = getIntent().getStringExtra(EXTRA_REEL_ID);
        videoUrl    = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        caption     = getIntent().getStringExtra(EXTRA_CAPTION);
        ownerUid    = getIntent().getStringExtra(EXTRA_OWNER_UID);
        // FIX: Default to true — only block if creator explicitly set allowReposts=false
        allowRepost = getIntent().getBooleanExtra(EXTRA_ALLOW_REPOST, true);

        if (reelId == null) { finish(); return; }

        try {
            myUid = FirebaseUtils.getCurrentUid();
        } catch (Exception e) {
            finish();
            return;
        }

        View btnClose = findViewById(R.id.btn_share_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> finish());

        View backdrop = findViewById(R.id.share_backdrop);
        if (backdrop != null) backdrop.setOnClickListener(v -> finish());

        // ── Rubber Bottom Sheet setup ─────────────────────────────────────
        View bottomSheet = findViewById(R.id.share_bottom_sheet);
        if (bottomSheet != null) {
            BottomSheetBehavior<View> bsb = BottomSheetBehavior.from(bottomSheet);
            bsb.setState(BottomSheetBehavior.STATE_EXPANDED);
            bsb.setDraggable(true);
            bsb.setHideable(true);
            bsb.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                @Override public void onStateChanged(@NonNull View v, int newState) {
                    // Neeche drag karke band karo
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) finish();
                }
                @Override public void onSlide(@NonNull View v, float slideOffset) {
                    // Backdrop dim/undim as sheet slides
                    if (backdrop != null)
                        backdrop.setAlpha(Math.max(0f, slideOffset));
                }
            });
        }

        rvContacts       = findViewById(R.id.rv_share_contacts);
        progressBar      = findViewById(R.id.progress_share);
        btnCopyLink          = findViewById(R.id.btn_copy_link);
        btnShareExternal     = findViewById(R.id.btn_share_external);
        btnShareToStatus     = findViewById(R.id.btn_share_to_status);
        btnRepostWithCaption = findViewById(R.id.btn_repost_with_caption);

        adapter = new ReelContactShareAdapter(contacts, this);
        rvContacts.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvContacts.setAdapter(adapter);

        btnCopyLink.setOnClickListener(v -> copyLink());
        btnShareExternal.setOnClickListener(v -> shareExternal());
        btnShareToStatus.setOnClickListener(v -> shareToStatus());
        if (btnRepostWithCaption != null)
            btnRepostWithCaption.setOnClickListener(v -> openRepostWithCaption());

        loadContacts();
    }

    private void loadContacts() {
        progressBar.setVisibility(View.VISIBLE);
        FirebaseUtils.getContactsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    contacts.clear();
                    for (DataSnapshot child : snap.getChildren()) {
                        User u = child.getValue(User.class);
                        if (u != null) {
                            if (u.uid == null) u.uid = child.getKey();
                            contacts.add(u);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    progressBar.setVisibility(View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError error) {
                    progressBar.setVisibility(View.GONE);
                }
            });
    }

    @Override
    public void onShareToContact(User contact) {
        if (contact.uid == null) return;
        // FIX: Respect creator privacy — block share-to-chat if allowReposts is false
        if (!allowRepost) {
            Toast.makeText(this, "This creator has disabled sharing of this reel.", Toast.LENGTH_SHORT).show();
            return;
        }
        String chatId = FirebaseUtils.getChatId(myUid, contact.uid);
        String link   = DEEP_LINK_PREFIX + reelId;
        String text   = (caption != null && !caption.isEmpty())
            ? "🎬 " + caption + "\n" + link
            : "🎬 Check out this reel!\n" + link;

        DatabaseReference msgRef = FirebaseUtils.getMessagesRef(chatId).push();
        java.util.Map<String, Object> msg = new java.util.HashMap<>();
        msg.put("senderId",  myUid);
        msg.put("text",      text);
        msg.put("type",      "reel_share");
        msg.put("reelId",    reelId);
        msg.put("timestamp", System.currentTimeMillis());
        msgRef.setValue(msg);

        incrementShareCount();
        Toast.makeText(this, "Shared with " + contact.name, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void copyLink() {
        String link = DEEP_LINK_PREFIX + reelId;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Reel Link", link));
        incrementShareCount();
        Toast.makeText(this, "Link copied!", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void shareExternal() {
        String link = DEEP_LINK_PREFIX + reelId;
        String text = (caption != null && !caption.isEmpty())
            ? caption + "\n" + link : link;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, "Share Reel via…"));
        incrementShareCount();
        finish();
    }

    private void shareToStatus() {
        // FIX: Respect creator privacy
        if (!allowRepost) {
            Toast.makeText(this, "This creator has disabled sharing of this reel.", Toast.LENGTH_SHORT).show();
            return;
        }
        // FIX: ownerName ko Firebase se fetch karo agar available nahi hai
        String ownerName = "";
        try {
            ownerName = FirebaseUtils.getCurrentName();
            if (ownerName == null) ownerName = "";
        } catch (Exception ignored) {}

        Intent i = new Intent(this, ReelShareToStoryActivity.class);
        i.putExtra(ReelShareToStoryActivity.EXTRA_REEL_ID,        reelId);
        i.putExtra(ReelShareToStoryActivity.EXTRA_REEL_URL,        videoUrl);
        i.putExtra(ReelShareToStoryActivity.EXTRA_REEL_OWNER_NAME, ownerName);
        incrementShareCount();
        startActivity(i);
        finish();
    }

    private void openRepostWithCaption() {
        // Respect creator privacy
        if (!allowRepost) {
            Toast.makeText(this, "This creator has disabled sharing of this reel.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, RepostWithCaptionActivity.class);
        i.putExtra(RepostWithCaptionActivity.EXTRA_REEL_ID,    reelId);
        i.putExtra(RepostWithCaptionActivity.EXTRA_OWNER_UID,  ownerUid != null ? ownerUid : "");
        i.putExtra(RepostWithCaptionActivity.EXTRA_OWNER_NAME, "");
        i.putExtra(RepostWithCaptionActivity.EXTRA_THUMB_URL,  getIntent().getStringExtra(EXTRA_THUMB_URL) != null ? getIntent().getStringExtra(EXTRA_THUMB_URL) : "");
        i.putExtra(RepostWithCaptionActivity.EXTRA_VIDEO_URL,  videoUrl != null ? videoUrl : "");
        i.putExtra(RepostWithCaptionActivity.EXTRA_CAPTION,    caption != null ? caption : "");
        startActivity(i);
        finish();
    }

    private void incrementShareCount() {
        DatabaseReference countRef =
            FirebaseUtils.getReelsRef().child(reelId).child("sharesCount");
        countRef.runTransaction(new Transaction.Handler() {
            @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                Integer c = d.getValue(Integer.class);
                d.setValue(c != null ? c + 1 : 1);
                return Transaction.success(d);
            }
            @Override public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
        });
    }
}
