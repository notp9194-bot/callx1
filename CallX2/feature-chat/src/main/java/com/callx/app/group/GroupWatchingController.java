package com.callx.app.group;

import android.app.Activity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.SecurityManager;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Group-chat counterpart of {@code ChatPresenceController}'s "watching
 * banner". Multiple group members can have the chat screen open at once,
 * so instead of one fixed partner this watches the WHOLE
 * chatPresence/{groupId}/* node and renders however many people are
 * currently active:
 *
 *   1 watcher  → "Asha aapko dekh rha hai"               (1 avatar)
 *   2 watchers → "Asha, Ravi dekh rahe hain"              (2 overlapping avatars)
 *   3+ watchers→ "Asha, Ravi aur 2 others dekh rahe hain" (2 avatars + "+N" badge)
 *
 * Reuses the exact same chatPresence/{id}/{uid}=true node and banner views
 * (ll_watching_banner / iv_watching_avatar / tv_watching_name) as the 1:1
 * flow — {id} is just the groupId here instead of a 1:1 chatId, and the
 * two never collide (see FirebaseUtils.getChatPresenceRef). The group-only
 * views (iv_watching_avatar2, tv_watching_more) stay gone for 1:1 chats,
 * so ChatPresenceController's behaviour is completely unchanged.
 */
public class GroupWatchingController {

    private static final long PRESENCE_OFF_DEBOUNCE_MS = 1500;

    /** Delegate exposed by GroupChatActivity — kept minimal on purpose. */
    public interface Delegate {
        Activity getActivity();
        ActivityChatBinding getBinding();
        String getGroupId();
        String getCurrentUid();
        /** uid -> display name (populated by the group members listener). */
        Map<String, String> getMemberNames();
        /** uid -> photoUrl (populated alongside member last-seen lookups). */
        Map<String, String> getMemberPhotos();
        /** Adapter backing the message list — used to push per-message
         *  "currently being viewed" dots. */
        com.callx.app.conversation.MessagePagingAdapter getPagingAdapter();
        /** Scroll-and-highlight to the given messageId ("jump to their
         *  position"), loading it from local DB first if it's not in the
         *  currently-paged-in adapter window. No-op / toasts internally if
         *  the message can't be found at all. */
        void navigateToMessage(String messageId);
    }

    private final Delegate delegate;
    private ValueEventListener presenceListener;

    private final android.os.Handler presenceDebounceHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingOffWrite;

    public GroupWatchingController(Delegate delegate) {
        this.delegate = delegate;
    }

    public void init() {
        watchGroupPresence();
        watchGroupViewingMessages();
        setupBannerTap();
    }

    // ── Per-message viewing ("seen-this-bubble" dot), group version ────────
    // chatViewing/{groupId}/{uid} = messageId. Aggregated across every other
    // member so a bubble's dot lights up if ANY of them currently has it
    // scrolled into view.

    private ValueEventListener viewingListener;
    private final android.os.Handler viewingDebounceHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingViewingWrite;
    private static final long VIEWING_DEBOUNCE_MS = 400;
    private String lastPublishedViewingId;
    /** uid -> messageId, raw per-member snapshot (unlike the aggregated dot
     *  set pushed to the adapter). Lets a single watcher's avatar/row jump
     *  to exactly what THAT person is looking at, not just "someone is". */
    private final Map<String, String> lastViewingByUid = new java.util.HashMap<>();
    /** Watcher count from the previous banner render — lets us tell a
     *  genuinely NEW watcher joining (count went up) apart from a refresh
     *  of who's already there, so the join "pulse" + haptic only fires
     *  once per arrival instead of on every Firebase tick. */
    private int lastWatcherCount = 0;

    /** Call from the Activity's RecyclerView scroll-idle callback with the
     *  messageId of the topmost (or otherwise "focused") visible message. */
    public void publishViewingMessage(String messageId) {
        if (pendingViewingWrite != null) {
            viewingDebounceHandler.removeCallbacks(pendingViewingWrite);
            pendingViewingWrite = null;
        }
        if (messageId != null && messageId.equals(lastPublishedViewingId)) return;
        pendingViewingWrite = () -> {
            writeViewingMessage(messageId);
            pendingViewingWrite = null;
        };
        viewingDebounceHandler.postDelayed(pendingViewingWrite, VIEWING_DEBOUNCE_MS);
    }

    public void clearViewingMessage() {
        if (pendingViewingWrite != null) {
            viewingDebounceHandler.removeCallbacks(pendingViewingWrite);
            pendingViewingWrite = null;
        }
        writeViewingMessage(null);
    }

    private void writeViewingMessage(String messageId) {
        String groupId = delegate.getGroupId();
        String uid = delegate.getCurrentUid();
        if (groupId == null || uid == null) return;

        if (messageId != null && delegate.getActivity() != null) {
            SecurityManager secMgr = SecurityManager.get(delegate.getActivity());
            if (!secMgr.isWatchingPresenceEnabled()) messageId = null;
        }

        DatabaseReference ref = FirebaseUtils.getChatViewingRef(groupId).child(uid);
        lastPublishedViewingId = messageId;
        if (messageId == null) {
            ref.removeValue();
            ref.onDisconnect().cancel();
        } else {
            ref.setValue(messageId);
            ref.onDisconnect().removeValue();
        }
    }

    private void watchGroupViewingMessages() {
        String groupId = delegate.getGroupId();
        if (groupId == null) return;

        viewingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                java.util.Set<String> ids = new java.util.HashSet<>();
                lastViewingByUid.clear();
                for (DataSnapshot child : s.getChildren()) {
                    String uid = child.getKey();
                    if (uid == null || uid.equals(delegate.getCurrentUid())) continue;
                    String mid = child.getValue(String.class);
                    if (mid != null) { ids.add(mid); lastViewingByUid.put(uid, mid); }
                }
                if (delegate.getPagingAdapter() != null) {
                    delegate.getPagingAdapter().setViewingMessageIds(ids);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getChatViewingRef(groupId).addValueEventListener(viewingListener);
    }

    // ── Tap banner → full watchers list bottom sheet ────────────────────────
    // Especially useful once 3+ members are watching at once and the strip
    // collapses to "Asha, Ravi aur 2 others" — tap to see everyone by name.

    private List<String> lastWatcherUids = new ArrayList<>();

    private void setupBannerTap() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding.llWatchingBanner == null) return;
        binding.llWatchingBanner.setClickable(true);
        binding.llWatchingBanner.setFocusable(true);
        binding.llWatchingBanner.setOnClickListener(v -> showWatchersSheet());

        // Avatars are clickable in their own right (consumes the tap before
        // it bubbles to llWatchingBanner above), so tapping a specific face
        // jumps straight to THAT person's current position — no need to
        // open the sheet first. The "+N" badge still opens the sheet since
        // it represents multiple people at once.
        if (binding.ivWatchingAvatar != null) {
            binding.ivWatchingAvatar.setOnClickListener(v -> jumpToWatcherAt(0));
        }
        if (binding.ivWatchingAvatar2 != null) {
            binding.ivWatchingAvatar2.setOnClickListener(v -> jumpToWatcherAt(1));
        }
        if (binding.tvWatchingMore != null) {
            binding.tvWatchingMore.setOnClickListener(v -> fanOutThenShowSheet());
        }
    }

    // ── "+N" badge tap → quick fan-out flourish, then bottom sheet ─────────
    // Purely cosmetic pre-roll: the 3 overlapping avatar views nudge apart
    // (translate + slight rotate + scale) so the stack visibly "opens up"
    // right before the sheet slides in, instead of the sheet just appearing
    // out of nowhere. Views snap back to their resting (overlapped) position
    // once the sheet is showing, so the banner looks untouched if the user
    // dismisses the sheet and the banner stays/refreshes later.

    private static final long FAN_OUT_DURATION_MS = 170;
    private static final long FAN_OUT_SHEET_DELAY_MS = 130;
    private boolean fanOutInProgress = false;

    private void fanOutThenShowSheet() {
        if (fanOutInProgress) return; // guard against double-tap re-trigger

        ActivityChatBinding binding = delegate.getBinding();
        if (binding.llWatchingBanner == null || lastWatcherUids.isEmpty()) {
            showWatchersSheet();
            return;
        }

        Activity activity = delegate.getActivity();
        float density = activity != null ? activity.getResources().getDisplayMetrics().density : 1f;
        float spread = 10f * density;

        de.hdodenhof.circleimageview.CircleImageView a1 = binding.ivWatchingAvatar;
        de.hdodenhof.circleimageview.CircleImageView a2 = binding.ivWatchingAvatar2;
        android.widget.TextView badge = binding.tvWatchingMore;

        boolean a2Visible = a2 != null && a2.getVisibility() == View.VISIBLE;
        boolean badgeVisible = badge != null && badge.getVisibility() == View.VISIBLE;

        fanOutInProgress = true;
        android.view.animation.DecelerateInterpolator outInterp =
                new android.view.animation.DecelerateInterpolator();

        // First avatar drifts back-left and tilts slightly counter-clockwise.
        if (a1 != null) {
            a1.animate().cancel();
            a1.animate()
                    .translationX(-spread).translationY(-spread * 0.4f)
                    .rotation(-8f)
                    .setDuration(FAN_OUT_DURATION_MS)
                    .setInterpolator(outInterp)
                    .start();
        }
        // Second avatar stays roughly centered but lifts slightly — reads as
        // the "middle" card of the fan.
        if (a2Visible) {
            a2.animate().cancel();
            a2.animate()
                    .translationY(-spread * 0.7f)
                    .scaleX(1.05f).scaleY(1.05f)
                    .setDuration(FAN_OUT_DURATION_MS)
                    .setInterpolator(outInterp)
                    .start();
        }
        // "+N" badge drifts forward-right and tilts clockwise — the badge
        // itself is what got tapped, so it gets the most pronounced motion.
        if (badgeVisible) {
            badge.animate().cancel();
            badge.animate()
                    .translationX(spread * 1.3f).translationY(-spread * 0.2f)
                    .rotation(10f)
                    .scaleX(1.12f).scaleY(1.12f)
                    .setDuration(FAN_OUT_DURATION_MS)
                    .setInterpolator(outInterp)
                    .start();
        }

        binding.llWatchingBanner.postDelayed(() -> {
            showWatchersSheet();
            snapFanBackToRest();
        }, FAN_OUT_SHEET_DELAY_MS);
    }

    /** Restores the avatar stack to its normal overlapped resting position.
     *  Called right after the sheet is launched — by the time it's visible
     *  the user's attention is on the sheet, so this resolves invisibly
     *  underneath it with no jarring snap-back on screen. */
    private void snapFanBackToRest() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding == null || binding.llWatchingBanner == null) {
            fanOutInProgress = false;
            return;
        }
        android.view.animation.DecelerateInterpolator backInterp =
                new android.view.animation.DecelerateInterpolator();

        if (binding.ivWatchingAvatar != null) {
            binding.ivWatchingAvatar.animate().cancel();
            binding.ivWatchingAvatar.animate()
                    .translationX(0f).translationY(0f).rotation(0f)
                    .setDuration(FAN_OUT_DURATION_MS).setInterpolator(backInterp).start();
        }
        if (binding.ivWatchingAvatar2 != null) {
            binding.ivWatchingAvatar2.animate().cancel();
            binding.ivWatchingAvatar2.animate()
                    .translationX(0f).translationY(0f)
                    .scaleX(1f).scaleY(1f)
                    .setDuration(FAN_OUT_DURATION_MS).setInterpolator(backInterp).start();
        }
        if (binding.tvWatchingMore != null) {
            binding.tvWatchingMore.animate().cancel();
            binding.tvWatchingMore.animate()
                    .translationX(0f).translationY(0f).rotation(0f)
                    .scaleX(1f).scaleY(1f)
                    .setDuration(FAN_OUT_DURATION_MS).setInterpolator(backInterp)
                    .withEndAction(() -> fanOutInProgress = false)
                    .start();
        } else {
            fanOutInProgress = false;
        }
    }

    /** Jump to whatever message the Nth currently-shown watcher avatar
     *  (0 = first/main avatar, 1 = second overlapping avatar) is looking
     *  at. Falls back to a toast if that person hasn't settled on a
     *  specific bubble yet. */
    private void jumpToWatcherAt(int index) {
        if (index < 0 || index >= lastWatcherUids.size()) return;
        jumpToWatcher(lastWatcherUids.get(index));
    }

    private void jumpToWatcher(String uid) {
        Activity activity = delegate.getActivity();
        String messageId = lastViewingByUid.get(uid);
        if (messageId == null || messageId.isEmpty()) {
            if (activity == null) return;
            String name = delegate.getMemberNames().getOrDefault(uid, "They");
            android.widget.Toast.makeText(activity,
                    name + " has the chat open but isn't on a specific message yet",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        delegate.navigateToMessage(messageId);
    }

    private void showWatchersSheet() {
        Activity activity = delegate.getActivity();
        if (activity == null || lastWatcherUids.isEmpty()) return;

        Map<String, String> names  = delegate.getMemberNames();
        Map<String, String> photos = delegate.getMemberPhotos();

        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(activity,
                        com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);

        android.widget.LinearLayout root = new android.widget.LinearLayout(activity);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        android.widget.TextView title = new android.widget.TextView(activity);
        title.setText("Currently viewing this chat");
        title.setTextSize(16);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, pad);
        root.addView(title);

        for (String uid : lastWatcherUids) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(activity);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, pad / 2, 0, pad / 2);
            row.setClickable(true);
            row.setFocusable(true);
            android.util.TypedValue outValue = new android.util.TypedValue();
            activity.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, outValue, true);
            row.setForeground(activity.getDrawable(outValue.resourceId));

            de.hdodenhof.circleimageview.CircleImageView avatar =
                    new de.hdodenhof.circleimageview.CircleImageView(activity);
            int size = (int) (40 * activity.getResources().getDisplayMetrics().density);
            android.widget.LinearLayout.LayoutParams avatarParams =
                    new android.widget.LinearLayout.LayoutParams(size, size);
            avatarParams.setMarginEnd(pad);
            avatar.setLayoutParams(avatarParams);
            loadAvatar(avatar, photos.get(uid));
            row.addView(avatar);

            android.widget.LinearLayout textCol = new android.widget.LinearLayout(activity);
            textCol.setOrientation(android.widget.LinearLayout.VERTICAL);

            android.widget.TextView name = new android.widget.TextView(activity);
            name.setText(names.getOrDefault(uid, "Member"));
            name.setTextSize(15);
            textCol.addView(name);

            boolean hasTarget = lastViewingByUid.get(uid) != null;
            android.widget.TextView subtitle = new android.widget.TextView(activity);
            subtitle.setText(hasTarget ? "Viewing a message \u00b7 tap to jump" : "Has the chat open");
            subtitle.setTextSize(12);
            subtitle.setAlpha(0.6f);
            textCol.addView(subtitle);

            row.addView(textCol);
            row.setOnClickListener(v -> { sheet.dismiss(); jumpToWatcher(uid); });

            root.addView(row);
        }

        sheet.setContentView(root);
        sheet.show();
    }

    // ── Publish our own in-chat-screen presence ─────────────────────────────
    // Identical debounce behaviour to the 1:1 controller: quick away-and-back
    // navigation (rotation, brief app-switch) won't flicker the banner for
    // other members.

    public void setOurInChatScreen(boolean active) {
        // Watching presence disabled — no-op
    }

    private void writePresence(boolean active) {
        String groupId = delegate.getGroupId();
        String uid = delegate.getCurrentUid();
        if (groupId == null || uid == null) return;

        // Respect the same "Chat Activity Status" privacy toggle used for 1:1 chats.
        if (active && delegate.getActivity() != null) {
            SecurityManager secMgr = SecurityManager.get(delegate.getActivity());
            if (!secMgr.isWatchingPresenceEnabled()) active = false;
        }

        DatabaseReference ref = FirebaseUtils.getChatPresenceRef(groupId).child(uid);
        ref.setValue(active);
        if (active) {
            // Safety net: app/connection dies without onPause firing.
            ref.onDisconnect().setValue(false);
        } else {
            ref.onDisconnect().cancel();
        }
    }

    // ── Watch everyone else's presence on this group ────────────────────────

    private void watchGroupPresence() {
        // Watching banner disabled — no-op
    }

    // ── "Active Xm ago" grace window — last watcher(s) just left ───────────

    private static final long JUST_LEFT_GRACE_MS = 90_000;
    private long lastSeenActiveAt = 0L;
    private final android.os.Handler justLeftTickHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable justLeftTickRunnable;

    private void beginJustLeftWindow() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding.llWatchingBanner == null) return;
        if (binding.llWatchingBanner.getVisibility() != View.VISIBLE) return;

        cancelJustLeftWindow();
        final long leftAt = lastSeenActiveAt > 0 ? lastSeenActiveAt : System.currentTimeMillis();
        binding.llWatchingBanner.animate().alpha(0.6f).setDuration(250).start();

        justLeftTickRunnable = new Runnable() {
            @Override public void run() {
                long elapsed = System.currentTimeMillis() - leftAt;
                if (elapsed >= JUST_LEFT_GRACE_MS) { hideWatchingBanner(); return; }
                ActivityChatBinding b = delegate.getBinding();
                if (b.llWatchingBanner == null || b.llWatchingBanner.getVisibility() != View.VISIBLE) return;
                long mins = elapsed / 60_000L;
                b.tvWatchingName.setText(mins < 1 ? "abhi tak active tha" : ("active " + mins + "m pehle"));
                justLeftTickHandler.postDelayed(this, 20_000);
            }
        };
        justLeftTickHandler.post(justLeftTickRunnable);
    }

    private void cancelJustLeftWindow() {
        if (justLeftTickRunnable != null) {
            justLeftTickHandler.removeCallbacks(justLeftTickRunnable);
            justLeftTickRunnable = null;
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────

    private void showWatchingBanner(List<String> watcherUids) {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding.llWatchingBanner == null) return;

        Map<String, String> names  = delegate.getMemberNames();
        Map<String, String> photos = delegate.getMemberPhotos();
        int total = watcherUids.size();
        boolean alreadyShowing = binding.llWatchingBanner.getVisibility() == View.VISIBLE;
        if (!alreadyShowing) {
            // Fresh pop-in only — if already showing, leave whatever
            // alpha/scale BannerPriorityCoordinator currently has it at.
            binding.llWatchingBanner.setAlpha(1f);
            binding.llWatchingBanner.setScaleX(1f);
            binding.llWatchingBanner.setScaleY(1f);
        }

        String name1 = names.getOrDefault(watcherUids.get(0), "Member");
        loadAvatar(binding.ivWatchingAvatar, photos.get(watcherUids.get(0)));
        binding.ivWatchingAvatar.setVisibility(View.VISIBLE);

        String label;
        if (total == 1) {
            label = name1 + " aapko dekh rha hai";
            binding.ivWatchingAvatar2.setVisibility(View.GONE);
            binding.tvWatchingMore.setVisibility(View.GONE);
        } else {
            String name2 = names.getOrDefault(watcherUids.get(1), "Member");
            loadAvatar(binding.ivWatchingAvatar2, photos.get(watcherUids.get(1)));
            binding.ivWatchingAvatar2.setVisibility(View.VISIBLE);

            if (total == 2) {
                label = name1 + ", " + name2 + " dekh rahe hain";
                binding.tvWatchingMore.setVisibility(View.GONE);
            } else {
                int more = total - 2;
                label = name1 + ", " + name2 + " aur " + more + " others dekh rahe hain";
                binding.tvWatchingMore.setText("+" + more);
                binding.tvWatchingMore.setVisibility(View.VISIBLE);
            }
        }
        binding.tvWatchingName.setText(label);

        boolean isNewJoin = total > lastWatcherCount;
        lastWatcherCount = total;

        if (binding.llWatchingBanner.getVisibility() == View.VISIBLE) {
            // Already showing — content/avatars above are refreshed in place.
            // If headcount went UP (someone new joined mid-session) give a
            // quick pulse + haptic tick so it doesn't pass unnoticed; if it's
            // just a refresh of the same people, stay silent.
            if (isNewJoin) {
                binding.llWatchingBanner.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                OvershootInterpolator pulseInterp = new OvershootInterpolator(2.2f);
                binding.ivWatchingAvatar.animate().cancel();
                binding.ivWatchingAvatar.setScaleX(0.85f);
                binding.ivWatchingAvatar.setScaleY(0.85f);
                binding.ivWatchingAvatar.animate().scaleX(1f).scaleY(1f)
                        .setDuration(260).setInterpolator(pulseInterp).start();
            }
            return;
        }

        binding.ivWatchingAvatar.setScaleX(0f);
        binding.ivWatchingAvatar.setScaleY(0f);
        binding.ivWatchingAvatar2.setScaleX(0f);
        binding.ivWatchingAvatar2.setScaleY(0f);
        binding.tvWatchingMore.setScaleX(0f);
        binding.tvWatchingMore.setScaleY(0f);
        binding.llWatchingBanner.setVisibility(View.VISIBLE);
        binding.llWatchingBanner.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);

        OvershootInterpolator interp = new OvershootInterpolator(2.2f);
        binding.ivWatchingAvatar.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(interp).start();
        binding.ivWatchingAvatar2.animate().scaleX(1f).scaleY(1f).setDuration(380).setStartDelay(40).setInterpolator(interp).start();
        binding.tvWatchingMore.animate().scaleX(1f).scaleY(1f).setDuration(380).setStartDelay(80).setInterpolator(interp).start();

        // New arrival — immediately yield to typing if it's already showing,
        // instead of waiting for the next typing Firebase tick.
        com.callx.app.chat.ui.BannerPriorityCoordinator.applyCurrentPriority(
                binding.llWatchingBanner, binding.llTypingStrip);
    }

    private void loadAvatar(de.hdodenhof.circleimageview.CircleImageView iv, String photoUrl) {
        Activity activity = delegate.getActivity();
        if (activity == null) return;
        if (photoUrl != null && !photoUrl.isEmpty()) {
            Glide.with(activity).load(photoUrl).placeholder(R.drawable.ic_person).into(iv);
        } else {
            iv.setImageResource(R.drawable.ic_person);
        }
    }

    private void hideWatchingBanner() {
        cancelJustLeftWindow();
        ActivityChatBinding binding = delegate.getBinding();
        lastWatcherCount = 0;
        if (binding.llWatchingBanner == null) return;
        if (binding.llWatchingBanner.getVisibility() != View.VISIBLE) return;

        AccelerateInterpolator interp = new AccelerateInterpolator();
        binding.ivWatchingAvatar.animate().scaleX(0f).scaleY(0f).setDuration(200).setInterpolator(interp).start();
        binding.ivWatchingAvatar2.animate().scaleX(0f).scaleY(0f).setDuration(200).setInterpolator(interp).start();
        binding.tvWatchingMore.animate().scaleX(0f).scaleY(0f).setDuration(200).setInterpolator(interp)
                .withEndAction(() -> {
                    binding.llWatchingBanner.setVisibility(View.GONE);
                    binding.llWatchingBanner.setAlpha(1f);
                })
                .start();
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    public void release() {
        String groupId = delegate.getGroupId();
        if (presenceListener != null && groupId != null) {
            FirebaseUtils.getChatPresenceRef(groupId).removeEventListener(presenceListener);
        }
        if (viewingListener != null && groupId != null) {
            FirebaseUtils.getChatViewingRef(groupId).removeEventListener(viewingListener);
        }
        cancelJustLeftWindow();
        if (pendingOffWrite != null) {
            presenceDebounceHandler.removeCallbacks(pendingOffWrite);
            pendingOffWrite = null;
        }
        ActivityChatBinding binding = delegate.getBinding();
        if (binding != null && binding.llWatchingBanner != null) {
            binding.llWatchingBanner.removeCallbacks(null); // cancels pending fan-out → sheet delay
        }
        fanOutInProgress = false;
        lastViewingByUid.clear();
        lastWatcherCount = 0;
        clearViewingMessage();
        writePresence(false);
    }
}
