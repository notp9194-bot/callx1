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
 * Activity se convert kiya gaya — ab koi nayi Activity create nahi hoti,
 * ExoPlayer pause nahi hota, back stack affect nahi hota.
 * Rubber feel + backdrop Material ka built-in hai — manually kuch set nahi karna.
 *
 * Launch karo:
 *   ReelShareSheetFragment.newInstance(reelId, videoUrl, thumbUrl, caption, ownerUid, allowRepost)
 *       .show(getChildFragmentManager(), "share");
 */
public class ReelShareSheetFragment extends BottomSheetDialogFragment
        implements ReelContactShareAdapter.OnContactShareListener {

    // ── Argument keys (same as purani Activity ke EXTRA_ constants) ─────────
    public static final String ARG_REEL_ID      = "share_reel_id";
    public static final String ARG_VIDEO_URL    = "share_video_url";
    public static final String ARG_THUMB_URL    = "share_thumb_url";
    public static final String ARG_CAPTION      = "share_caption";
    public static final String ARG_OWNER_UID    = "share_owner_uid";
    public static final String ARG_ALLOW_REPOST = "share_allow_repost";

    private static final String DEEP_LINK_PREFIX = Constants.DEEP_LINK_BASE_URL + "/reel/";

    // ── Views ────────────────────────────────────────────────────────────────
    private RecyclerView rvContacts;
    private ProgressBar  progressBar;
    private View         btnCopyLink, btnShareExternal, btnShareToStatus, btnRepostWithCaption;

    // ── Data ─────────────────────────────────────────────────────────────────
    private ReelContactShareAdapter adapter;
    private final List<User>        contacts = new ArrayList<>();

    private String  reelId;
    private String  videoUrl;
    private String  thumbUrl;
    private String  caption;
    private String  myUid;
    private String  ownerUid;
    private boolean allowRepost;

    // ── Factory ──────────────────────────────────────────────────────────────
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // DayNight — dark mode mein auto dark sheet milegi
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
        // Same layout reuse — sirf CoordinatorLayout wrapper aur backdrop hata diya
        return inflater.inflate(R.layout.fragment_reel_share_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvContacts           = view.findViewById(R.id.rv_share_contacts);
        progressBar          = view.findViewById(R.id.progress_share);
        btnCopyLink          = view.findViewById(R.id.btn_copy_link);
        btnShareExternal     = view.findViewById(R.id.btn_share_external);
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
        btnShareToStatus.setOnClickListener(v -> shareToStatus());
        if (btnRepostWithCaption != null)
            btnRepostWithCaption.setOnClickListener(v -> openRepostWithCaption());

        loadContacts();
    }

    // ── Contacts ──────────────────────────────────────────────────────────────
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

    // ── Share actions ─────────────────────────────────────────────────────────
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

    private void shareToStatus() {
        if (!allowRepost) {
            toast("This creator has disabled sharing of this reel.");
            return;
        }
        String ownerName = "";
        try {
            ownerName = FirebaseUtils.getCurrentName();
            if (ownerName == null) ownerName = "";
        } catch (Exception ignored) {}

        Intent i = new Intent(requireContext(), ReelShareToStoryActivity.class);
        i.putExtra(ReelShareToStoryActivity.EXTRA_REEL_ID,        reelId);
        i.putExtra(ReelShareToStoryActivity.EXTRA_REEL_URL,        videoUrl);
        i.putExtra(ReelShareToStoryActivity.EXTRA_REEL_OWNER_NAME, ownerName);
        incrementShareCount();
        startActivity(i);
        dismiss();
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

    // ── Firebase ──────────────────────────────────────────────────────────────
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

    // ── Util ──────────────────────────────────────────────────────────────────
    private void toast(String msg) {
        if (getContext() != null)
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
