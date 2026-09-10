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
import com.callx.app.corelite.RenderActionClient;

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
 *
 * ★ NEW — multi-select "Send to": tapping an avatar in the grid now checks it
 * (blue checkmark, IG/WhatsApp-style) instead of sending immediately. Once
 * one or more contacts are checked, ll_selection_actions appears with an
 * optional message box plus:
 *   • Send separately — pushes the reel as a normal 1:1 chat message to each
 *     checked contact individually (same message shape onShareToContact used
 *     to build inline; now sendReelToContact()).
 *   • Send to new group chat — only shown once 2+ contacts are checked;
 *     creates a brand-new group (same Firebase shape NewGroupActivity writes:
 *     groups/{id} + userGroups/{uid}/{id} fan-out) with everyone checked +
 *     self, then drops the reel share as the group's first message.
 * A search box above the grid narrows it live via ReelContactShareAdapter#filter().
 */
public class ReelShareSheetFragment extends BottomSheetDialogFragment {

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
    private View         btnCopyLink, btnShareExternal, btnShareWhatsapp;
    private View         btnAddToStory, btnShareToStatus, btnRepostWithCaption;

    // ★ NEW: multi-select "Send to" — search box, message input, and the
    // send-bar that appears once 1+ contacts are checked in the grid.
    private android.widget.EditText etSearch, etMessage;
    private View                    llSelectionActions;
    private android.widget.Button   btnSendSeparately, btnSendToGroup;

    // ★ FIX: pehle "peek clip" pe depend karte the (match_parent root +
    // weight=1 RecyclerView) — jo asal bug tha (see layout XML comment).
    // Ab dono views (grid + button row) ke beech height explicitly
    // ValueAnimator se grow/shrink hoti hai, state-change (drag khatam hone)
    // par — peek-clip pe koi dependency nahi.
    private View  llButtonRow;
    private int   buttonRowOriginalHeightPx = -1;
    private android.animation.ValueAnimator sizeAnimator;
    private boolean contentExpanded = false;
    private static final int CONTACT_GRID_SPAN_COUNT = 3;
    // ★ FIX #2: pehle RV_EXPANDED_DP ek HARDCODED 560dp tha, chahe contacts
    // kitne bhi hon — kam contacts (e.g. 1-2 rows) ke saath RecyclerView
    // forcefully 560dp tak khali white space ke saath expand hota tha, jisse
    // poori sheet zaroorat se bahut upar chali jaati thi (neeche khaali area,
    // reel peek karti hui). Ab dono collapsed/expanded targets
    // computeGridContentHeightPx() se ACTUAL contact-count ke hisaab se clamp
    // hote hain — sirf utni hi height leta hai jitni asal content ko chahiye
    // (max cap ke andar); zyada contacts hone par cap pe ruk ke andar-hi-andar
    // scroll karta hai (RecyclerView apna scroll khud handle karta hai).
    private static final int RV_ROW_HEIGHT_DP     = 92; // ek grid-row ki approx height (avatar+text+padding)
    private static final int RV_TOP_PADDING_DP    = 4;  // RecyclerView ka apna paddingTop
    private static final int RV_COLLAPSED_MAX_DP  = 230; // peek me max itni hi height (~2 rows)
    private static final int RV_EXPANDED_MAX_DP   = 560; // full-expand me max itni hi height (baaki scroll)
    private static final int SIZE_ANIM_MS         = 260;

    /** Actual content height for the current contact count — n rows worth, no more. */
    private int computeGridContentHeightPx() {
        int n = contacts.size();
        int rows = Math.max(1, (n + CONTACT_GRID_SPAN_COUNT - 1) / CONTACT_GRID_SPAN_COUNT);
        return dp(RV_TOP_PADDING_DP) + rows * dp(RV_ROW_HEIGHT_DP);
    }

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


    // ★ ULTRA-OPTIMIZED: held as a field + guarded by sheetCallbackAttached so
    // onStart() — which the DialogFragment lifecycle can invoke more than
    // once for the same dialog instance (e.g. app backgrounded/foregrounded
    // while the sheet is open) — never registers a second duplicate
    // BottomSheetCallback. A duplicate would double-run animateSheetContent()
    // on every state settle (wasted layout passes + animator churn)
    // and hold an extra long-lived reference for the dialog's lifetime.
    private BottomSheetBehavior.BottomSheetCallback sheetCallback;
    private boolean sheetCallbackAttached = false;

    @Override
    public void onStart() {
        super.onStart();
        BottomSheetDialog d = (BottomSheetDialog) getDialog();
        if (d == null) return;
        FrameLayout bs = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bs == null) return;
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bs);
        behavior.setHideable(true);
        // ★ FIX: fitToContents ab TRUE hai (false wala approach hi bug tha —
        // wo view ko hamesha ~poori screen jitna tall force karta tha, jisse
        // button row content ke bottom pe, peek window ke bahar chala jaata
        // tha). Root ab wrap_content hai, to peekHeight = content ki natural
        // (chhoti) height ke barabar set kiya hai — collapsed state me KUCH
        // clip nahi hota, sab kuch (avatars + button row) as-designed dikhta
        // hai. Grow/shrink ab animateSheetContent() explicitly karta hai.
        behavior.setSkipCollapsed(false);
        behavior.setFitToContents(true);
        behavior.setDraggable(true);
        behavior.setPeekHeight(dp(460));

        if (!sheetCallbackAttached) {
            sheetCallback = new BottomSheetBehavior.BottomSheetCallback() {
                @Override
                public void onStateChanged(@NonNull View bottomSheet, int newState) {
                    if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                        animateSheetContent(true);
                    } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                        animateSheetContent(false);
                    }
                }

                @Override
                public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                    // Grow/shrink ab state-settle par animate hota hai (see
                    // onStateChanged), continuous drag-offset se nahi — isliye
                    // yahan kuch nahi karna.
                }
            };
            behavior.addBottomSheetCallback(sheetCallback);
            sheetCallbackAttached = true;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Drop the reference once the sheet's view is torn down — nothing
        // left to animate, and this lets the callback (and anything it
        // closes over) be collected instead of lingering on a dead fragment.
        sheetCallback = null;
        sheetCallbackAttached = false;
        if (sizeAnimator != null) {
            sizeAnimator.cancel();
            sizeAnimator = null;
        }
    }

    /**
     * ★ FIX: replaces the old drag-clip-based reveal. Smoothly grows/shrinks
     * the avatar grid AND the button row together, right after the sheet
     * settles into STATE_EXPANDED / STATE_COLLAPSED — RecyclerView height
     * animates {@code RV_COLLAPSED_MAX_DP → RV_EXPANDED_MAX_DP} (clamped by
     * actual contact count — see computeGridContentHeightPx()), revealing more rows
     * while the button row's height + alpha animate the opposite way (shrinking
     * to 0, handing its space to the grid). Both are driven by ONE ValueAnimator
     * so they move in perfect lockstep and never visually desync.
     */
    private void animateSheetContent(boolean expand) {
        if (expand == contentExpanded) return; // already there — avoid a redundant re-animate
        contentExpanded = expand;
        if (rvContacts == null) return;

        if (sizeAnimator != null) sizeAnimator.cancel();

        // ★ FIX: dono targets ab actual content (contacts.size()) se clamp
        // hote hain — chahe RV_*_MAX_DP kitna bhi bada ho, agar utni content
        // hi nahi hai to utni height bhi nahi li jaati (no wasted empty space).
        int contentPx = computeGridContentHeightPx();
        int rvFrom = rvContacts.getLayoutParams().height;
        int rvTo   = expand
            ? Math.min(dp(RV_EXPANDED_MAX_DP), contentPx)
            : Math.min(dp(RV_COLLAPSED_MAX_DP), contentPx);

        int rowFrom = (llButtonRow != null) ? llButtonRow.getLayoutParams().height : 0;
        int rowTo;
        if (buttonRowOriginalHeightPx > 0) {
            rowTo = expand ? 0 : buttonRowOriginalHeightPx;
        } else {
            rowTo = expand ? 0 : rowFrom;
        }

        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(SIZE_ANIM_MS);
        va.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();

            ViewGroup.LayoutParams rvLp = rvContacts.getLayoutParams();
            rvLp.height = Math.round(rvFrom + (rvTo - rvFrom) * t);
            rvContacts.setLayoutParams(rvLp);

            if (llButtonRow != null) {
                ViewGroup.LayoutParams rowLp = llButtonRow.getLayoutParams();
                rowLp.height = Math.round(rowFrom + (rowTo - rowFrom) * t);
                llButtonRow.setLayoutParams(rowLp);
                float rowMax = buttonRowOriginalHeightPx > 0 ? buttonRowOriginalHeightPx : Math.max(rowFrom, 1);
                float alpha = rowLp.height / rowMax;
                llButtonRow.setAlpha(Math.max(0f, Math.min(1f, alpha)));
                llButtonRow.setVisibility(rowLp.height <= 0 ? View.GONE : View.VISIBLE);
            }
        });
        va.start();
        sizeAnimator = va;
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
        btnShareWhatsapp     = view.findViewById(R.id.btn_share_whatsapp);
        btnAddToStory        = view.findViewById(R.id.btn_add_to_story);
        btnShareToStatus     = view.findViewById(R.id.btn_share_to_status);
        btnRepostWithCaption = view.findViewById(R.id.btn_repost_with_caption);
        llButtonRow          = view.findViewById(R.id.ll_share_button_row);

        etSearch            = view.findViewById(R.id.et_share_search);
        etMessage           = view.findViewById(R.id.et_share_message);
        llSelectionActions  = view.findViewById(R.id.ll_selection_actions);
        btnSendSeparately   = view.findViewById(R.id.btn_send_separately);
        btnSendToGroup      = view.findViewById(R.id.btn_send_to_group);

        // Close button
        View btnClose = view.findViewById(R.id.btn_share_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> dismiss());

        // ★ UPGRADE: horizontal row → GRID (Instagram-style, 3 avatars per row).
        adapter = new ReelContactShareAdapter(contacts);
        rvContacts.setLayoutManager(new GridLayoutManager(requireContext(), CONTACT_GRID_SPAN_COUNT));
        rvContacts.setAdapter(adapter);

        // ★ NEW: search box narrows the grid live.
        if (etSearch != null) {
            etSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    if (adapter != null) adapter.filter(s.toString());
                }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
        }

        // ★ NEW: multi-select send bar reacts to checkmarks toggled in the grid.
        adapter.setOnSelectionChangedListener(this::onSelectionChanged);

        if (btnSendSeparately != null) btnSendSeparately.setOnClickListener(v -> sendSeparately());
        if (btnSendToGroup != null) btnSendToGroup.setOnClickListener(v -> sendToNewGroupChat());

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
        if (btnShareWhatsapp != null) btnShareWhatsapp.setOnClickListener(v -> shareWhatsApp());

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
        // ★ ULTRA-OPTIMIZED: was a raw FirebaseUtils.getContactsRef(myUid)
        // full-node read + per-child User deserialization on EVERY single
        // sheet open. Now routed through ReelShareContactsCache (same
        // session-scoped TTL pattern as MutualFollowersCache) — a repeat
        // open within the TTL window costs zero Firebase reads and zero
        // re-parsing; the grid can paint on the very next frame.
        com.callx.app.cache.ReelShareContactsCache.getInstance().getContacts(myUid, cachedContacts -> {
            if (!isAdded()) return;
            contacts.clear();
            contacts.addAll(cachedContacts);
            // Online/offline snapshot is time-sensitive — always recomputed
            // fresh against "now" on every open, even on a cache hit.
            if (adapter != null) adapter.refreshOnlineSnapshot();
            // refreshDisplayed() (not a raw notifyDataSetChanged()) so a
            // search query already typed before contacts finished loading
            // is still respected once they arrive.
            if (adapter != null) adapter.refreshDisplayed();
            progressBar.setVisibility(View.GONE);

            // ★ FIX: initial (pre-drag) height bhi clamp karo — XML ka default
            // 230dp tab tak reh jaata agar kabhi drag na ho, chahe 2-3 hi
            // contacts hon. Ab load hote hi actual content ke hisaab se
            // resize ho jaata hai, taaki peek state se hi koi khaali space
            // na dikhe.
            if (rvContacts != null) {
                ViewGroup.LayoutParams lp = rvContacts.getLayoutParams();
                lp.height = Math.min(dp(RV_COLLAPSED_MAX_DP), computeGridContentHeightPx());
                rvContacts.setLayoutParams(lp);
            }
        });
    }

    // ── Multi-select send bar ─────────────────────────────────────────────
    /**
     * ReelContactShareAdapter.OnSelectionChangedListener callback — shows/hides
     * ll_selection_actions and updates button labels/visibility. Deliberately
     * leaves llButtonRow (Copy Link / Share via / Story / Status / Repost)
     * untouched so animateSheetContent()'s expand/collapse height animation
     * on that row is never disturbed by selection state.
     */
    private void onSelectionChanged(List<User> selectedContacts) {
        if (llSelectionActions == null) return;
        boolean hasSelection = !selectedContacts.isEmpty();
        llSelectionActions.setVisibility(hasSelection ? View.VISIBLE : View.GONE);
        if (!hasSelection) return;

        if (btnSendSeparately != null) {
            btnSendSeparately.setText(selectedContacts.size() == 1
                ? "Send"
                : "Send separately (" + selectedContacts.size() + ")");
        }
        if (btnSendToGroup != null) {
            boolean showGroupOption = selectedContacts.size() >= 2;
            btnSendToGroup.setVisibility(showGroupOption ? View.VISIBLE : View.GONE);
            if (showGroupOption) {
                btnSendToGroup.setText("Send to new group chat (" + selectedContacts.size() + ")");
            }
        }
    }

    /** "Send separately" — pushes the reel as an individual 1:1 message to every checked contact. */
    private void sendSeparately() {
        if (adapter == null) return;
        List<User> selectedContacts = adapter.getSelectedContacts();
        if (selectedContacts.isEmpty()) return;
        if (!allowRepost) {
            toast("This creator has disabled sharing of this reel.");
            return;
        }
        String customText = (etMessage != null && etMessage.getText() != null)
            ? etMessage.getText().toString().trim() : "";

        for (User contact : selectedContacts) {
            sendReelToContact(contact, customText);
        }

        incrementShareCount();
        toast(selectedContacts.size() == 1
            ? "Shared with " + selectedContacts.get(0).name
            : "Sent to " + selectedContacts.size() + " people");
        dismiss();
    }

    /** Pushes one reel_share message into a single contact's 1:1 chat + notifies them. */
    private void sendReelToContact(User contact, String customText) {
        if (contact == null || contact.uid == null) return;
        String chatId = FirebaseUtils.getChatId(myUid, contact.uid);
        String link   = DEEP_LINK_PREFIX + reelId;
        String text   = (customText != null && !customText.isEmpty())
            ? customText + "\n" + link
            : (caption != null && !caption.isEmpty()
                ? "🎬 " + caption + "\n" + link
                : "🎬 Check out this reel!\n" + link);

        DatabaseReference msgRef = FirebaseUtils.getMessagesRef(chatId).push();
        String msgKey = msgRef.getKey();
        Map<String, Object> msg  = new HashMap<>();
        msg.put("senderId",        myUid);
        msg.put("text",            text);
        msg.put("type",            "reel_share");
        msg.put("reelId",          reelId);
        msg.put("reelShareUrl",        link);
        msg.put("reelShareThumb",      thumbUrl      != null ? thumbUrl      : ""); // fallback / older clients, or if the embed below fails
        msg.put("reelShareCaption",    caption       != null ? caption       : "");
        msg.put("reelShareUsername",   ownerUsername != null && !ownerUsername.isEmpty()
                                        ? ownerUsername : (ownerUid != null ? ownerUid : ""));
        msg.put("reelShareOwnerPhoto", ownerPhoto    != null ? ownerPhoto    : "");
        msg.put("timestamp",       System.currentTimeMillis());
        // WhatsApp-level fix: embed a self-contained copy of the thumbnail
        // (reelShareThumbBase64) so this card keeps rendering even if the
        // original reel is later deleted or thumbUrl's CDN link changes.
        // See ThumbnailEmbedder (core) for the shared download/crop/compress
        // logic — same helper status-reply/status-seen/reel-seen use.
        com.callx.app.utils.ThumbnailEmbedder.embed(thumbUrl, base64 -> {
            if (base64 != null) msg.put("reelShareThumbBase64", base64);
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
        });
    }

    /**
     * "Send to new group chat" — only enabled once 2+ contacts are checked.
     * Creates a brand-new group using the same Firebase shape NewGroupActivity
     * writes (groups/{id} + userGroups/{uid}/{id} fan-out) with everyone
     * checked + self as members/self as admin, then drops the reel share as
     * the group's very first message.
     */
    private void sendToNewGroupChat() {
        if (adapter == null || btnSendToGroup == null) return;
        List<User> selectedContacts = adapter.getSelectedContacts();
        if (selectedContacts.size() < 2) return;
        if (!allowRepost) {
            toast("This creator has disabled sharing of this reel.");
            return;
        }

        btnSendToGroup.setEnabled(false);
        final String customText = (etMessage != null && etMessage.getText() != null)
            ? etMessage.getText().toString().trim() : "";

        DatabaseReference groupRef = FirebaseUtils.getGroupsRef().push();
        final String groupId = groupRef.getKey();
        if (groupId == null) {
            toast("Failed to create group. Try again.");
            btnSendToGroup.setEnabled(true);
            return;
        }

        String myNameLookup = "";
        try {
            myNameLookup = FirebaseUtils.getCurrentName();
            if (myNameLookup == null) myNameLookup = "";
        } catch (Exception ignored) {}
        final String myName = myNameLookup;

        // Group name: "Alice, Bob, Carol" style, capped at 3 names.
        StringBuilder nameBuilder = new StringBuilder();
        int shown = 0;
        for (User u : selectedContacts) {
            if (shown >= 3) { nameBuilder.append(" & others"); break; }
            if (shown > 0) nameBuilder.append(", ");
            nameBuilder.append(u.name != null && !u.name.isEmpty() ? u.name : "User");
            shown++;
        }
        final String groupName = nameBuilder.toString();

        Map<String, Object> g = new HashMap<>();
        g.put("id",             groupId);
        g.put("name",           groupName);
        g.put("createdBy",      myUid);
        g.put("adminUid",       myUid);
        g.put("createdAt",      System.currentTimeMillis());
        g.put("lastMessage",    "🎬 Reel");
        g.put("lastSenderName", myName);
        g.put("lastMessageAt",  System.currentTimeMillis());

        Map<String, Boolean> members = new HashMap<>();
        members.put(myUid, true);
        final List<String> memberUids = new ArrayList<>();
        for (User u : selectedContacts) {
            if (u.uid == null) continue;
            members.put(u.uid, true);
            memberUids.add(u.uid);
        }
        g.put("members", members);
        Map<String, Boolean> admins = new HashMap<>();
        admins.put(myUid, true);
        g.put("admins", admins);
        Map<String, Object> unread = new HashMap<>();
        for (String uid : members.keySet()) unread.put(uid, uid.equals(myUid) ? 0L : 1L);
        g.put("unread", unread);

        groupRef.setValue(g).addOnSuccessListener(unused -> {
            for (String uid : members.keySet()) {
                FirebaseUtils.getUserGroupsRef(uid).child(groupId).setValue(true);
            }

            String link = DEEP_LINK_PREFIX + reelId;
            String text = (customText != null && !customText.isEmpty())
                ? customText + "\n" + link
                : (caption != null && !caption.isEmpty()
                    ? "🎬 " + caption + "\n" + link
                    : "🎬 Check out this reel!\n" + link);

            DatabaseReference msgRef = FirebaseUtils.getGroupMessagesRef(groupId).push();
            Map<String, Object> msg = new HashMap<>();
            msg.put("senderId",            myUid);
            msg.put("senderName",          myName);
            msg.put("text",                text);
            msg.put("type",                "reel_share");
            msg.put("reelId",              reelId);
            msg.put("reelShareUrl",        link);
            msg.put("reelShareThumb",      thumbUrl      != null ? thumbUrl      : ""); // fallback / older clients, or if the embed below fails
            msg.put("reelShareCaption",    caption       != null ? caption       : "");
            msg.put("reelShareUsername",   ownerUsername != null && !ownerUsername.isEmpty()
                                            ? ownerUsername : (ownerUid != null ? ownerUid : ""));
            msg.put("reelShareOwnerPhoto", ownerPhoto    != null ? ownerPhoto    : "");
            msg.put("timestamp",           System.currentTimeMillis());
            msg.put("status",              "sent");

            // WhatsApp-level fix — same as sendReelToContact above: embed a
            // self-contained thumbnail copy so the card survives the
            // original reel later being deleted or thumbUrl changing.
            com.callx.app.utils.ThumbnailEmbedder.embed(thumbUrl, base64 -> {
                if (base64 != null) msg.put("reelShareThumbBase64", base64);
                msgRef.setValue(msg).addOnSuccessListener(unused2 ->
                    FirebaseUtils.sendGroupPushNotification(
                        groupId, memberUids, myUid,
                        myName.isEmpty() ? "New group" : myName,
                        "🎬 Shared a reel in " + groupName,
                        null));

                incrementShareCount();
                toast("Sent to new group with " + selectedContacts.size() + " people");
                dismiss();
            });
        }).addOnFailureListener(e -> {
            btnSendToGroup.setEnabled(true);
            toast("Failed to create group. Try again.");
        });
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

    /**
     * Explicit WhatsApp route used by the milestone rules. A generic Android
     * chooser cannot prove which app was selected, so only this direct action
     * records a WhatsApp share event.
     */
    private void shareWhatsApp() {
        if (!isAdded() || getContext() == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.setPackage("com.whatsapp");
        String link = DEEP_LINK_PREFIX + reelId;
        intent.putExtra(Intent.EXTRA_TEXT, (caption != null && !caption.isEmpty()
            ? caption + "\n" : "") + link);
        try {
            startActivity(intent);
            Map<String, Object> request = new HashMap<>();
            request.put("action", "recordWhatsappShare");
            Map<String, Object> payload = new HashMap<>();
            payload.put("reelId", reelId);
            request.put("payload", payload);
            RenderActionClient.post("/milestone-earnings/action", "recordWhatsappShare", payload);
            toast("Shared on WhatsApp. Milestone progress updated.");
            dismiss();
        } catch (Exception e) {
            toast("WhatsApp is not installed on this device.");
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
