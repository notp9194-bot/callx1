package com.callx.app.social;

import com.callx.app.feed.HomeFragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import java.io.File;
import java.io.FileOutputStream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.ViewTreeObserver;
import android.util.TypedValue;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import android.widget.FrameLayout;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import com.callx.app.reels.R;
import com.callx.app.social.ReelContactShareAdapter;
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
    public static final String ARG_REEL_ID        = "share_reel_id";
    public static final String ARG_VIDEO_URL      = "share_video_url";
    public static final String ARG_THUMB_URL      = "share_thumb_url";
    public static final String ARG_CAPTION        = "share_caption";
    public static final String ARG_OWNER_UID      = "share_owner_uid";
    public static final String ARG_OWNER_USERNAME = "share_owner_username";
    public static final String ARG_OWNER_PHOTO    = "share_owner_photo";
    public static final String ARG_ALLOW_REPOST   = "share_allow_repost";

    private static final String DEEP_LINK_PREFIX = Constants.DEEP_LINK_BASE_URL + "/reel/";

    // ── Views ──────────────────────────────────────────────────────────────
    private RecyclerView rvContacts;
    private ProgressBar  progressBar;
    private View         btnCopyLink, btnShareExternal;
    private View         btnAddToStory, btnShareToStatus, btnRepostWithCaption;

    // ★ Horizontal action-icon row (Copy Link / Share via / Story / Status / Repost).
    // Height + alpha is driven live off BottomSheetBehavior's slide offset — see
    // updateButtonRowForSlide() — so it smoothly shrinks away as the user drags the
    // sheet up, handing that freed space to the avatar grid above it.
    private View  llButtonRow;
    private int   buttonRowOriginalHeightPx = -1;
    // Row is fully hidden once the sheet has expanded this fraction of the way
    // from peek (0f) to fully-expanded (1f) — kept small so it disappears early,
    // well before the grid finishes growing.
    private static final float BUTTON_ROW_HIDE_FRACTION = 0.35f;
    private static final int   CONTACT_GRID_SPAN_COUNT   = 3;

    // ── Data ───────────────────────────────────────────────────────────────
    private ReelContactShareAdapter adapter;
    private final List<User>        contacts = new ArrayList<>();

    private String  reelId;
    private String  videoUrl;
    private String  thumbUrl;
    private String  caption;
    private String  myUid;
    private String  ownerUid;
    private String  ownerUsername;
    private String  ownerPhoto;
    private boolean allowRepost;

    // ── Factory ────────────────────────────────────────────────────────────
    public static ReelShareSheetFragment newInstance(
            String reelId, String videoUrl, String thumbUrl,
            String caption, String ownerUid, String ownerUsername,
            String ownerPhoto, boolean allowRepost) {

        Bundle args = new Bundle();
        args.putString(ARG_REEL_ID,        reelId);
        args.putString(ARG_VIDEO_URL,      videoUrl);
        args.putString(ARG_THUMB_URL,      thumbUrl);
        args.putString(ARG_CAPTION,        caption);
        args.putString(ARG_OWNER_UID,      ownerUid);
        args.putString(ARG_OWNER_USERNAME, ownerUsername);
        args.putString(ARG_OWNER_PHOTO,    ownerPhoto);
        args.putBoolean(ARG_ALLOW_REPOST,  allowRepost);

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
            reelId        = getArguments().getString(ARG_REEL_ID);
            videoUrl      = getArguments().getString(ARG_VIDEO_URL);
            thumbUrl      = getArguments().getString(ARG_THUMB_URL);
            caption       = getArguments().getString(ARG_CAPTION);
            ownerUid      = getArguments().getString(ARG_OWNER_UID);
            ownerUsername = getArguments().getString(ARG_OWNER_USERNAME);
            ownerPhoto    = getArguments().getString(ARG_OWNER_PHOTO);
            allowRepost   = getArguments().getBoolean(ARG_ALLOW_REPOST, true);
        }

        try {
            myUid = FirebaseUtils.getCurrentUid();
        } catch (Exception e) {
            dismiss();
        }

        if (reelId == null) dismiss();
    }


    @Override
    public void onStart() {
        super.onStart();
        BottomSheetDialog d = (BottomSheetDialog) getDialog();
        if (d == null) return;
        FrameLayout bs = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bs == null) return;
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bs);
        behavior.setHideable(true);
        // ★ UPGRADE: skipCollapsed ab false — peek/collapsed state hi wo initial view hai
        // jisme thode avatars + button row dikhte hain (screenshot 1). fitToContents(false)
        // isliye taaki root view (jo XML me match_parent hai) hamesha FULL sheet height
        // (screen - expandedOffset) pe layout ho — peek me sirf uska upar wala hissa clip
        // hoke dikhta hai, aur upar kheenchne par baaki reveal hota hai (screenshot 2).
        behavior.setSkipCollapsed(false);
        behavior.setFitToContents(false);
        behavior.setDraggable(true);
        behavior.setExpandedOffset(dp(24));
        behavior.setPeekHeight(dp(430));

        behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                // Drag chhod dene ke baad final state pe row ko exact 0 ya 1 pe snap
                // kar dete hain, taaki chhoti si beech-adhoori state na ruk jaye.
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    updateButtonRowForSlide(1f);
                } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    updateButtonRowForSlide(0f);
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                // fitToContents(false) ke saath slideOffset peek(0) se expanded(1) tak
                // jaata hai; hidden ki taraf jaane par negative bhi ho sakta hai — us
                // hisse me row ko poora visible hi rakhte hain.
                updateButtonRowForSlide(Math.max(0f, slideOffset));
            }
        });
    }

    /**
     * ★ Button-row shrink/reveal animation, live-tied to the sheet's drag position.
     * progress 0f = fully visible (peek state) → 1f = fully collapsed away (expanded state).
     * Directly mutating the row's own LayoutParams.height (rather than the RecyclerView's)
     * is what makes the grid above it grow: it sits in a weight=1 slot in the same
     * LinearLayout, so any height the row gives up is automatically claimed by the grid
     * on the very next layout pass — no manual recycler resizing needed.
     */
    private void updateButtonRowForSlide(float rawProgress) {
        if (llButtonRow == null) return;
        if (buttonRowOriginalHeightPx <= 0) return; // not measured yet

        float progress = Math.min(1f, rawProgress / BUTTON_ROW_HIDE_FRACTION);
        int newHeight = Math.round(buttonRowOriginalHeightPx * (1f - progress));

        ViewGroup.LayoutParams lp = llButtonRow.getLayoutParams();
        if (lp.height != newHeight) {
            lp.height = newHeight;
            llButtonRow.setLayoutParams(lp);
        }
        llButtonRow.setAlpha(1f - progress);
        llButtonRow.setVisibility(newHeight <= 0 ? View.GONE : View.VISIBLE);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
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
        llButtonRow          = view.findViewById(R.id.ll_share_button_row);

        // Close button
        View btnClose = view.findViewById(R.id.btn_share_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> dismiss());

        // ★ UPGRADE: horizontal row → GRID (Instagram-style, 3 avatars per row).
        adapter = new ReelContactShareAdapter(contacts, this);
        rvContacts.setLayoutManager(new GridLayoutManager(requireContext(), CONTACT_GRID_SPAN_COUNT));
        rvContacts.setAdapter(adapter);

        // ★ Capture the button row's natural (XML-defined) height once it's actually
        // measured, then start it fully visible (progress 0 == peek state default).
        if (llButtonRow != null) {
            llButtonRow.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (buttonRowOriginalHeightPx <= 0 && llButtonRow.getHeight() > 0) {
                            buttonRowOriginalHeightPx = llButtonRow.getHeight();
                            llButtonRow.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                    }
                });
        }

        // Buttons
        btnCopyLink.setOnClickListener(v -> copyLink());
        btnShareExternal.setOnClickListener(v -> shareExternal());

        // ★ Add to Story — Instagram-style gradient story in Reels home
        if (btnAddToStory != null)
            btnAddToStory.setOnClickListener(v -> addToStory());

        // ★ Add to Status — WhatsApp-style status tab
        if (btnShareToStatus != null)
            btnShareToStatus.setOnClickListener(v -> addToStatus());

        if (btnRepostWithCaption != null)
            btnRepostWithCaption.setOnClickListener(v -> openRepostWithCaption());

        // ★ UPGRADE: velocity-based prefetch — same pattern FollowConnectionsActivity
        // uses for FollowAvatarBinder (fast fling skips prefetch, slow/deliberate
        // scroll warms rows ahead via DiskCacheStrategy.DATA). GridLayoutManager
        // extends LinearLayoutManager so findLastVisibleItemPosition() works as-is.
        rvContacts.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private long lastTimeMs = 0L;

            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                GridLayoutManager lm = (GridLayoutManager) recyclerView.getLayoutManager();
                if (lm == null) return;
                int lastVisible = lm.findLastVisibleItemPosition();
                if (lastVisible < 0) return;

                long now = android.os.SystemClock.elapsedRealtime();
                long dt = lastTimeMs == 0L ? 0L : (now - lastTimeMs);
                float velocity = (dt > 0) ? Math.abs(dy) / (float) dt : 0f;
                lastTimeMs = now;

                com.callx.app.followers.FollowAvatarBinder.prefetch(
                    requireContext(), adapter.avatarSource(), lastVisible + 1, velocity);
            }
        });

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
                    // ★ ULTRA-OPTIMIZED: online/offline snapshot computed ONCE here
                    // (single `now` timestamp for the whole batch), never inside
                    // onBindViewHolder — see ReelContactShareAdapter's class doc.
                    if (adapter != null) adapter.refreshOnlineSnapshot();
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
        String msgKey = msgRef.getKey();
        Map<String, Object> msg  = new HashMap<>();
        msg.put("senderId",        myUid);
        msg.put("text",            text);
        msg.put("type",            "reel_share");
        msg.put("reelId",          reelId);
        msg.put("reelShareUrl",        DEEP_LINK_PREFIX + reelId);
        msg.put("reelShareThumb",      thumbUrl      != null ? thumbUrl      : "");
        msg.put("reelShareCaption",    caption       != null ? caption       : "");
        msg.put("reelShareUsername",   ownerUsername != null && !ownerUsername.isEmpty()
                                        ? ownerUsername : (ownerUid != null ? ownerUid : ""));
        msg.put("reelShareOwnerPhoto", ownerPhoto    != null ? ownerPhoto    : "");
        msg.put("timestamp",       System.currentTimeMillis());
        msgRef.setValue(msg).addOnSuccessListener(unused -> {
            // ── FCM push — receiver ko background/killed notification mile ──
            String myName = "";
            try { myName = FirebaseUtils.getCurrentName(); } catch (Exception ignored) {}
            if (myName == null) myName = "";
            com.callx.app.utils.PushNotify.notifyMessage(
                contact.uid,       // toUid
                myUid,             // fromUid
                myName,            // fromName
                chatId,            // chatId
                msgKey != null ? msgKey : "",  // messageId
                "🎬 Reel",         // preview text
                "reel_share",      // type
                thumbUrl != null ? thumbUrl : ""  // mediaUrl (thumb for notification)
            );
        });

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
        final String link = DEEP_LINK_PREFIX + reelId;
        final String shareText = (caption != null && !caption.isEmpty())
                ? caption + "\n" + link
                : link;

        // Thumbnail available hai — Glide se download karke image+text share karo
        if (thumbUrl != null && !thumbUrl.isEmpty() && isAdded() && getContext() != null) {
            Glide.with(requireContext())
                    .asBitmap()
                    .load(thumbUrl)
                    .override(480, 853)
                    .into(new CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@NonNull Bitmap bitmap,
                                                    @Nullable Transition<? super Bitmap> transition) {
                            if (!isAdded() || getContext() == null) return;
                            try {
                                // Cache dir mein thumbnail save karo
                                File cacheDir = new File(requireContext().getCacheDir(), "reel_shares");
                                //noinspection ResultOfMethodCallIgnored
                                cacheDir.mkdirs();
                                File imgFile = new File(cacheDir, "reel_thumb_" + reelId + ".jpg");
                                FileOutputStream fos = new FileOutputStream(imgFile);
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                                fos.flush();
                                fos.close();

                                // FileProvider content:// URI (file:// Android 7+ pe block hai)
                                Uri imageUri = FileProvider.getUriForFile(
                                        requireContext(),
                                        requireContext().getPackageName() + ".fileprovider",
                                        imgFile);

                                Intent intent = new Intent(Intent.ACTION_SEND);
                                intent.setType("image/jpeg");
                                intent.putExtra(Intent.EXTRA_STREAM, imageUri);
                                intent.putExtra(Intent.EXTRA_TEXT, shareText);
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(Intent.createChooser(intent, "Share Reel via…"));
                                incrementShareCount();
                                dismiss();
                            } catch (Exception e) {
                                shareExternalTextOnly(shareText);
                            }
                        }

                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholder) {}

                        @Override
                        public void onLoadFailed(@Nullable Drawable errorDrawable) {
                            shareExternalTextOnly(shareText);
                        }
                    });
        } else {
            shareExternalTextOnly(shareText);
        }
    }

    /** Fallback: sirf text+link share (no image) */
    private void shareExternalTextOnly(String shareText) {
        if (!isAdded()) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(intent, "Share Reel via…"));
        incrementShareCount();
        dismiss();
    }

    /**
     * ★ Add to Story (Instagram-style)
     *
     * Directly pushes a "reel_story" entry to status/{myUid}.
     * HomeFragment's collectStoryEntries() checks for type=="reel_story"
     * and renders it with a gradient ring in the stories bar at the top
     * of the Reels home feed — visible to all followers for 24 hours.
     */
    private void addToStory() {
        if (!allowRepost) {
            toast("This creator has disabled sharing of this reel.");
            return;
        }

        String myName = "";
        try {
            myName = FirebaseUtils.getCurrentName();
            if (myName == null) myName = "";
        } catch (Exception ignored) {}

        long now      = System.currentTimeMillis();
        long expiresAt = now + 86_400_000L; // 24 hours

        DatabaseReference storyRef =
            FirebaseUtils.db().getReference("status").child(myUid).push();
        String storyId = storyRef.getKey();
        if (storyId == null) {
            toast("Failed to add story. Try again.");
            return;
        }

        Map<String, Object> story = new HashMap<>();
        story.put("id",           storyId);
        story.put("type",         "reel_story");           // ★ gradient ring trigger
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
        story.put("isReelStory",  true);                   // explicit flag for adapters

        storyRef.setValue(story).addOnCompleteListener(task -> {
            if (!isAdded()) return;
            if (task.isSuccessful()) {
                incrementShareCount();
                toast("Added to your Story! ✨ Visible to followers for 24h");
                dismiss();
            } else {
                toast("Failed to add story. Try again.");
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
