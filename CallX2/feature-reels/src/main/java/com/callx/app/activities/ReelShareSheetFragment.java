package com.callx.app.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import com.callx.app.reels.R;
import com.callx.app.adapters.ReelContactShareAdapter;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReelShareSheetFragment — BottomSheetDialogFragment
 *
 * Share options:
 *  • Send to contacts (DM)
 *  • Copy Link
 *  • Share via... (external)
 *  • ★ Add to Story  — directly posts reel_story to status/{uid}; shows gradient ring
 *                       in Reels HomeFragment stories bar (Instagram-style, 24h expiry)
 *  • ★ Add to Status — directly posts reel_clip to status/{uid}; visible in Status tab
 *                       (WhatsApp-style, 24h expiry)
 *  • Repost with Caption
 *
 * Launch:
 *   ReelShareSheetFragment.newInstance(reelId, videoUrl, thumbUrl, caption, ownerUid, allowRepost)
 *       .show(getChildFragmentManager(), "share");
 */
public class ReelShareSheetFragment extends BottomSheetDialogFragment
        implements ReelContactShareAdapter.OnContactShareListener {

    // ── Argument keys ──────────────────────────────────────────────────────
    public static final String ARG_REEL_ID      = "share_reel_id";
    public static final String ARG_VIDEO_URL    = "share_video_url";
    public static final String ARG_THUMB_URL    = "share_thumb_url";
    public static final String ARG_CAPTION      = "share_caption";
    public static final String ARG_OWNER_UID    = "share_owner_uid";
    public static final String ARG_ALLOW_REPOST = "share_allow_repost";

    private static final String DEEP_LINK_PREFIX = Constants.DEEP_LINK_BASE_URL + "/reel/";

    // ── Views ──────────────────────────────────────────────────────────────
    private RecyclerView rvContacts;
    private ProgressBar  progressBar;
    private View         btnCopyLink, btnShareExternal;
    private View         btnAddToStory, btnShareToStatus, btnRepostWithCaption;

    // ── Data ───────────────────────────────────────────────────────────────
    private ReelContactShareAdapter adapter;
    private final List<User>        contacts = new ArrayList<>();

    private String  reelId;
    private String  videoUrl;
    private String  thumbUrl;
    private String  caption;
    private String  myUid;
    private String  ownerUid;
    private boolean allowRepost;

    // ── Factory ────────────────────────────────────────────────────────────
    public static ReelShareSheetFragment newInstance(
            String reelId, String videoUrl, String thumbUrl,
            String caption, String ownerUid, boolean allowRepost) {

        Bundle args = new Bundle();
        args.putString(ARG_REEL_ID,      reelId);
        args.putString(ARG_VIDEO_URL,    videoUrl);
        args.putString(ARG_THUMB_URL,    thumbUrl);
        args.putString(ARG_CAPTION,      caption);
        args.putString(ARG_OWNER_UID,    ownerUid);
        args.putBoolean(ARG_ALLOW_REPOST, allowRepost);

        ReelShareSheetFragment f = new ReelShareSheetFragment();
        f.setArguments(args);
        return f;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.Theme_Material3_DayNight_BottomSheetDialog);

        if (getArguments() != null) {
            reelId      = getArguments().getString(ARG_REEL_ID);
            videoUrl    = getArguments().getString(ARG_VIDEO_URL);
            thumbUrl    = getArguments().getString(ARG_THUMB_URL);
            caption     = getArguments().getString(ARG_CAPTION);
            ownerUid    = getArguments().getString(ARG_OWNER_UID);
            allowRepost = getArguments().getBoolean(ARG_ALLOW_REPOST, true);
        }

        try {
            myUid = FirebaseUtils.getCurrentUid();
        } catch (Exception e) {
            dismiss();
        }

        if (reelId == null) dismiss();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_reel_share_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvContacts           = view.findViewById(R.id.rv_share_contacts);
        progressBar          = view.findViewById(R.id.progress_share);
        btnCopyLink          = view.findViewById(R.id.btn_copy_link);
        btnShareExternal     = view.findViewById(R.id.btn_share_external);
        btnAddToStory        = view.findViewById(R.id.btn_add_to_story);
        btnShareToStatus     = view.findViewById(R.id.btn_share_to_status);
        btnRepostWithCaption = view.findViewById(R.id.btn_repost_with_caption);

        // Close button
        View btnClose = view.findViewById(R.id.btn_share_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> dismiss());

        // RecyclerView
        adapter = new ReelContactShareAdapter(contacts, this);
        rvContacts.setLayoutManager(
            new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        rvContacts.setAdapter(adapter);

        // Buttons
        btnCopyLink.setOnClickListener(v -> copyLink());
        btnShareExternal.setOnClickListener(v -> shareExternal());

        // ★ Add to Reels — pushes to full-screen Reels feed
        if (btnAddToStory != null)
            btnAddToStory.setOnClickListener(v -> addToReels());

        // ★ Add to Status — WhatsApp-style status tab
        if (btnShareToStatus != null)
            btnShareToStatus.setOnClickListener(v -> addToStatus());

        if (btnRepostWithCaption != null)
            btnRepostWithCaption.setOnClickListener(v -> openRepostWithCaption());

        loadContacts();
    }

    // ── Contacts ───────────────────────────────────────────────────────────
    private void loadContacts() {
        if (myUid == null) return;
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
                    if (adapter != null) adapter.notifyDataSetChanged();
                    progressBar.setVisibility(View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError error) {
                    progressBar.setVisibility(View.GONE);
                }
            });
    }

    // ── Share actions ──────────────────────────────────────────────────────
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
        dismiss();
    }

    private void copyLink() {
        String link = DEEP_LINK_PREFIX + reelId;
        ClipboardManager cm = (ClipboardManager)
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Reel Link", link));
        incrementShareCount();
        toast("Link copied!");
        dismiss();
    }

    private void shareExternal() {
        String link = DEEP_LINK_PREFIX + reelId;
        String text = (caption != null && !caption.isEmpty()) ? caption + "\n" + link : link;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, "Share Reel via…"));
        incrementShareCount();
        dismiss();
    }

    /**
     * ★ Add to Reels
     *
     * Directly pushes a new ReelModel entry to reels/{newReelId}.
     * This reel will appear in the full-screen vertical Reels feed
     * (ReelsFragment / SingleReelPlayerActivity) for all followers
     * — sorted by trendingScore just like any other reel.
     */
    private void addToReels() {
        if (!allowRepost) {
            toast("This creator has disabled sharing of this reel.");
            return;
        }

        String myName  = "";
        String myPhoto = "";
        try {
            myName = FirebaseUtils.getCurrentName();
            if (myName == null) myName = "";
        } catch (Exception ignored) {}

        long now = System.currentTimeMillis();

        DatabaseReference newReelRef = FirebaseUtils.getReelsRef().push();
        String newReelId = newReelRef.getKey();
        if (newReelId == null) {
            toast("Failed to add to Reels. Try again.");
            return;
        }

        Map<String, Object> reel = new HashMap<>();
        reel.put("reelId",              newReelId);
        reel.put("uid",                 myUid);
        reel.put("ownerName",           myName);
        reel.put("ownerPhoto",          myPhoto);
        reel.put("videoUrl",            videoUrl != null ? videoUrl : "");
        reel.put("thumbUrl",            thumbUrl != null ? thumbUrl : "");
        reel.put("thumbnailUrl",        thumbUrl != null ? thumbUrl : "");
        reel.put("caption",             caption  != null ? caption  : "");
        reel.put("timestamp",           now);
        reel.put("likesCount",          0);
        reel.put("commentsCount",       0);
        reel.put("sharesCount",         0);
        reel.put("viewsCount",          0);
        reel.put("repostCount",         0);
        reel.put("allowReposts",        true);
        reel.put("audienceType",        "everyone");
        // Attribution: mark original reel source
        reel.put("repostedFromReelId",  reelId     != null ? reelId    : "");
        reel.put("repostedFromUid",     ownerUid   != null ? ownerUid  : "");
        reel.put("repostedFromName",    "");

        newReelRef.setValue(reel).addOnCompleteListener(task -> {
            if (!isAdded()) return;
            if (task.isSuccessful()) {
                incrementShareCount();
                toast("Added to Reels! Visible in the Reels feed");
                dismiss();
            } else {
                toast("Failed to add to Reels. Try again.");
            }
        });
    }

    /**
     * ★ Add to Status (WhatsApp-style)
     *
     * Directly pushes a "reel_clip" entry to status/{myUid}.
     * Appears in the Status tab for all contacts — same as a WhatsApp status.
     * 24-hour auto-expiry applies.
     */
    private void addToStatus() {
        String myName = "";
        try {
            myName = FirebaseUtils.getCurrentName();
            if (myName == null) myName = "";
        } catch (Exception ignored) {}

        long now       = System.currentTimeMillis();
        long expiresAt = now + 86_400_000L; // 24 hours

        DatabaseReference statusRef =
            FirebaseUtils.db().getReference("status").child(myUid).push();
        String statusId = statusRef.getKey();
        if (statusId == null) {
            toast("Failed to add status. Try again.");
            return;
        }

        Map<String, Object> status = new HashMap<>();
        status.put("id",           statusId);
        status.put("type",         "reel_clip");            // WhatsApp-style status type
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

        statusRef.setValue(status).addOnCompleteListener(task -> {
            if (!isAdded()) return;
            if (task.isSuccessful()) {
                incrementShareCount();
                toast("Added to your Status! ✓ Visible to contacts for 24h");
                dismiss();
            } else {
                toast("Failed to add status. Try again.");
            }
        });
    }

    private void openRepostWithCaption() {
        if (!allowRepost) {
            toast("This creator has disabled sharing of this reel.");
            return;
        }
        Intent i = new Intent(requireContext(), RepostWithCaptionActivity.class);
        i.putExtra(RepostWithCaptionActivity.EXTRA_REEL_ID,    reelId);
        i.putExtra(RepostWithCaptionActivity.EXTRA_OWNER_UID,  ownerUid != null ? ownerUid : "");
        i.putExtra(RepostWithCaptionActivity.EXTRA_OWNER_NAME, "");
        i.putExtra(RepostWithCaptionActivity.EXTRA_THUMB_URL,  thumbUrl != null ? thumbUrl : "");
        i.putExtra(RepostWithCaptionActivity.EXTRA_VIDEO_URL,  videoUrl != null ? videoUrl : "");
        i.putExtra(RepostWithCaptionActivity.EXTRA_CAPTION,    caption  != null ? caption  : "");
        startActivity(i);
        dismiss();
    }

    // ── Firebase ───────────────────────────────────────────────────────────
    private void incrementShareCount() {
        if (reelId == null) return;
        DatabaseReference countRef =
            FirebaseUtils.getReelsRef().child(reelId).child("sharesCount");
        countRef.runTransaction(new Transaction.Handler() {
            @NonNull @Override
            public Transaction.Result doTransaction(@NonNull MutableData d) {
                Integer c = d.getValue(Integer.class);
                d.setValue(c != null ? c + 1 : 1);
                return Transaction.success(d);
            }
            @Override
            public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
        });
    }

    // ── Util ───────────────────────────────────────────────────────────────
    private void toast(String msg) {
        if (getContext() != null)
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
