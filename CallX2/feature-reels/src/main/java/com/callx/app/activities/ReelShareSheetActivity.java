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
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReelShareSheetActivity — Share a reel with rich options.
 *
 * Share options:
 *  ✅ Send to individual chat contact (DM deeplink)
 *  ✅ Copy Link
 *  ✅ Share via... (native Android share intent)
 *  ★ Add to Status — saves reel_clip to status/{uid}
 *      → Visible in Status tab → opens StatusViewerActivity (WhatsApp-style 24h)
 *  ★ Add to Story  — saves reel_story to status/{uid}
 *      → Visible in Reels Home tab stories bar (gradient ring) → opens SingleReelPlayerActivity
 *  ✅ Repost with Caption
 */
public class ReelShareSheetActivity extends AppCompatActivity
        implements ReelContactShareAdapter.OnContactShareListener {

    public static final String EXTRA_REEL_ID      = "share_reel_id";
    public static final String EXTRA_VIDEO_URL     = "share_video_url";
    public static final String EXTRA_THUMB_URL     = "share_thumb_url";
    public static final String EXTRA_CAPTION       = "share_caption";
    public static final String EXTRA_OWNER_UID     = "share_owner_uid";
    public static final String EXTRA_ALLOW_REPOST  = "share_allow_repost";

    private static final String DEEP_LINK_PREFIX =
        com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/reel/";

    private RecyclerView rvContacts;
    private ProgressBar  progressBar;
    private View         btnCopyLink, btnShareExternal;
    private View         btnShareToStatus, btnAddToStory, btnRepostWithCaption;

    private ReelContactShareAdapter adapter;
    private final List<User>        contacts = new ArrayList<>();

    private String  reelId;
    private String  videoUrl;
    private String  thumbUrl;
    private String  caption;
    private String  myUid;
    private String  ownerUid;
    private boolean allowRepost;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_share);

        reelId      = getIntent().getStringExtra(EXTRA_REEL_ID);
        videoUrl    = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        thumbUrl    = getIntent().getStringExtra(EXTRA_THUMB_URL);
        caption     = getIntent().getStringExtra(EXTRA_CAPTION);
        ownerUid    = getIntent().getStringExtra(EXTRA_OWNER_UID);
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

        View bottomSheet = findViewById(R.id.share_bottom_sheet);
        if (bottomSheet != null) {
            BottomSheetBehavior<View> bsb = BottomSheetBehavior.from(bottomSheet);
            bsb.setState(BottomSheetBehavior.STATE_EXPANDED);
            bsb.setDraggable(true);
            bsb.setHideable(true);
            bsb.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                @Override public void onStateChanged(@NonNull View v, int newState) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) finish();
                }
                @Override public void onSlide(@NonNull View v, float slideOffset) {
                    if (backdrop != null) backdrop.setAlpha(Math.max(0f, slideOffset));
                }
            });
        }

        rvContacts           = findViewById(R.id.rv_share_contacts);
        progressBar          = findViewById(R.id.progress_share);
        btnCopyLink          = findViewById(R.id.btn_copy_link);
        btnShareExternal     = findViewById(R.id.btn_share_external);
        btnShareToStatus     = findViewById(R.id.btn_share_to_status);
        btnAddToStory        = findViewById(R.id.btn_add_to_story);
        btnRepostWithCaption = findViewById(R.id.btn_repost_with_caption);

        adapter = new ReelContactShareAdapter(contacts, this);
        rvContacts.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvContacts.setAdapter(adapter);

        btnCopyLink.setOnClickListener(v -> copyLink());
        btnShareExternal.setOnClickListener(v -> shareExternal());

        // ★ Add to Status — WhatsApp-style: saves reel_clip, shows in Status tab
        if (btnShareToStatus != null)
            btnShareToStatus.setOnClickListener(v -> addToStatus());

        // ★ Add to Story — Instagram-style: saves reel_story, shows in Reels Home tab
        if (btnAddToStory != null)
            btnAddToStory.setOnClickListener(v -> addToStory());

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
        if (!allowRepost) {
            toast("This creator has disabled sharing of this reel.");
            return;
        }
        String chatId = FirebaseUtils.getChatId(myUid, contact.uid);
        String link   = DEEP_LINK_PREFIX + reelId;
        String text   = (caption != null && !caption.isEmpty())
            ? "🎬 " + caption + "\n" + link
            : "🎬 Check out this reel!\n" + link;

        DatabaseReference msgRef = FirebaseUtils.getMessagesRef(chatId).push();
        Map<String, Object> msg  = new HashMap<>();
        msg.put("senderId",  myUid);
        msg.put("text",      text);
        msg.put("type",      "reel_share");
        msg.put("reelId",    reelId);
        msg.put("timestamp", System.currentTimeMillis());
        msgRef.setValue(msg);

        incrementShareCount();
        toast("Shared with " + contact.name);
        finish();
    }

    private void copyLink() {
        String link = DEEP_LINK_PREFIX + reelId;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Reel Link", link));
        incrementShareCount();
        toast("Link copied!");
        finish();
    }

    private void shareExternal() {
        String link = DEEP_LINK_PREFIX + reelId;
        String text = (caption != null && !caption.isEmpty()) ? caption + "\n" + link : link;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, "Share Reel via…"));
        incrementShareCount();
        finish();
    }

    /**
     * ★ Add to Status (WhatsApp-style)
     *
     * Saves reel_clip entry to status/{myUid}.
     * Visible in the Status tab for all contacts.
     * When clicked in Status tab → opens StatusViewerActivity.
     * 24-hour auto-expiry applies.
     */
    private void addToStatus() {
        String myName = safeMyName();
        long now       = System.currentTimeMillis();
        long expiresAt = now + 86_400_000L;

        DatabaseReference statusRef =
            FirebaseUtils.db().getReference("status").child(myUid).push();
        String statusId = statusRef.getKey();
        if (statusId == null) { toast("Failed. Try again."); return; }

        Map<String, Object> status = new HashMap<>();
        status.put("id",           statusId);
        status.put("type",         "reel_clip");   // WhatsApp-style: visible in Status tab
        status.put("reelId",       reelId != null ? reelId : "");
        status.put("videoUrl",     videoUrl != null ? videoUrl : "");
        status.put("thumbnailUrl", thumbUrl != null ? thumbUrl : "");
        status.put("mediaUrl",     videoUrl != null ? videoUrl : "");
        status.put("caption",      caption != null ? caption : "");
        status.put("ownerUid",     myUid);
        status.put("ownerName",    myName);
        status.put("privacy",      "contacts");
        status.put("timestamp",    now);
        status.put("expiresAt",    expiresAt);
        status.put("deleted",      false);

        progressBar.setVisibility(View.VISIBLE);
        statusRef.setValue(status).addOnCompleteListener(task -> {
            progressBar.setVisibility(View.GONE);
            if (task.isSuccessful()) {
                incrementShareCount();
                toast("Added to your Status! ✓ Visible in Status tab for 24h");
                finish();
            } else {
                toast("Failed to add status. Try again.");
            }
        });
    }

    /**
     * ★ Add to Story (Instagram-style)
     *
     * Saves reel_story entry to status/{myUid}.
     * Appears in Reels Home tab stories bar with gradient ring.
     * When clicked in Home tab → opens SingleReelPlayerActivity (reel player).
     * 24-hour auto-expiry applies.
     */
    private void addToStory() {
        if (!allowRepost) {
            toast("This creator has disabled sharing of this reel.");
            return;
        }
        String myName  = safeMyName();
        long now       = System.currentTimeMillis();
        long expiresAt = now + 86_400_000L;

        DatabaseReference storyRef =
            FirebaseUtils.db().getReference("status").child(myUid).push();
        String storyId = storyRef.getKey();
        if (storyId == null) { toast("Failed. Try again."); return; }

        Map<String, Object> story = new HashMap<>();
        story.put("id",           storyId);
        story.put("type",         "reel_story");   // gradient ring in Home tab
        story.put("reelId",       reelId != null ? reelId : "");
        story.put("videoUrl",     videoUrl != null ? videoUrl : "");
        story.put("thumbnailUrl", thumbUrl != null ? thumbUrl : "");
        story.put("mediaUrl",     videoUrl != null ? videoUrl : "");
        story.put("caption",      caption != null ? caption : "");
        story.put("ownerUid",     myUid);
        story.put("ownerName",    myName);
        story.put("privacy",      "everyone");
        story.put("timestamp",    now);
        story.put("expiresAt",    expiresAt);
        story.put("deleted",      false);
        story.put("isReelStory",  true);

        progressBar.setVisibility(View.VISIBLE);
        storyRef.setValue(story).addOnCompleteListener(task -> {
            progressBar.setVisibility(View.GONE);
            if (task.isSuccessful()) {
                incrementShareCount();
                toast("Added to your Story! ✨ Visible in Home tab for 24h");
                finish();
            } else {
                toast("Failed to add story. Try again.");
            }
        });
    }

    private void openRepostWithCaption() {
        if (!allowRepost) {
            toast("This creator has disabled sharing of this reel.");
            return;
        }
        Intent i = new Intent(this, RepostWithCaptionActivity.class);
        i.putExtra(RepostWithCaptionActivity.EXTRA_REEL_ID,    reelId);
        i.putExtra(RepostWithCaptionActivity.EXTRA_OWNER_UID,  ownerUid != null ? ownerUid : "");
        i.putExtra(RepostWithCaptionActivity.EXTRA_OWNER_NAME, "");
        i.putExtra(RepostWithCaptionActivity.EXTRA_THUMB_URL,  thumbUrl != null ? thumbUrl : "");
        i.putExtra(RepostWithCaptionActivity.EXTRA_VIDEO_URL,  videoUrl != null ? videoUrl : "");
        i.putExtra(RepostWithCaptionActivity.EXTRA_CAPTION,    caption  != null ? caption  : "");
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

    private String safeMyName() {
        try {
            String n = FirebaseUtils.getCurrentName();
            return n != null ? n : "";
        } catch (Exception e) { return ""; }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
