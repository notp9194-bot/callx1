package com.callx.app.history;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.calls.R;
import com.callx.app.call.CallActivity;

import com.callx.app.models.CallLog;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.VerifiedBadgeUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import com.callx.app.cache.StatusCacheManager;
import com.callx.app.cache.StoryRingRegistry;
// PERF (deep avatar pipeline parity — see CallAvatarBinder): tiered/
// versioned URL + L2/L3 reuse + lifecycle-aware cancel + velocity prefetch,
// same shape as ChatAvatarBinder/FollowAvatarBinder elsewhere in the app.
import com.callx.app.cache.CallAvatarBinder;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * v951 — hot-path rewrite of the Calls row (see UPGRADE_NOTES_v951_CallsRowPerf.md):
 *  - all 7 click listeners created ONCE per ViewHolder (bind allocates none)
 *  - style table (colour / label / icon) instead of parseColor / toLowerCase / setColorFilter per bind
 *  - meta text cached per CallLog (no Date / SimpleDateFormat / String.format / concat per bind)
 *  - every setter guarded by "last bound" value (no needless relayout / invalidate / re-record)
 *  - selection = setActivated() + state-list bg (ripple kept), payload rebinds (not notifyDataSetChanged)
 *  - story ring: ONE status lookup, background only set on change, registry only while attached
 *  - avatar: no per-row Firebase read — missing photos are de-duplicated + batched
 */
public class CallHistoryAdapter extends RecyclerView.Adapter<CallHistoryAdapter.VH> {

    public interface SelectionListener {
        void onSelectionStarted();
        void onSelectionChanged();
        void onSelectionCleared();
    }

    /** Callback so Fragment/Activity can open the contact bottom sheet */
    public interface OnContactClickListener {
        void onContactClick(CallLog log, String resolvedPhotoUrl);
    }

    private static final Object PAYLOAD_SELECTION = new Object();
    private static final Object PAYLOAD_AVATAR    = new Object();

    // ── Style table: kind = 0 no_answer, 1 missed, 2 incoming, 3 outgoing ──────────────────────────
    private static final int[] NAME_COLOR = {0xFFF97316, 0xFFEF4444, 0xFFF59E0B, 0xFF22C55E};
    private static final int[] META_COLOR = {0xFFF97316, 0xFFEF4444, 0xFFF59E0B, 0xFF64748B};
    private static final String[] LABEL_AUDIO = {
        "No Answer", "Missed Voice Call", "Incoming Voice Call", "Outgoing Voice Call"};
    private static final String[] LABEL_VIDEO = {
        "No Answer (Video)", "Missed Video Call", "Incoming Video Call", "Outgoing Video Call"};

    private final List<CallLog> logs;
    private final SelectionListener selectionListener;
    private OnContactClickListener contactClickListener;

    // 12-hour format with AM/PM
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());
    private final Date tmpDate = new Date();
    /** CallLog → "label  •  when  •  dur". Weak identity keys: entries die with the CallLog objects. */
    private final Map<CallLog, String> metaCache = new WeakHashMap<>();

    private boolean isSelecting = false;
    private final Set<String> selectedIds = new HashSet<>();

    // Cache resolved photo URLs so bottom sheet can use them instantly
    private final Map<String, String> photoCache = new HashMap<>();

    // Missing-photo resolver: one read per uid per session, batched (was: one read per ROW per bind)
    private final Set<String> photoRequested = new HashSet<>();
    private final List<String> photoPending = new ArrayList<>();
    private boolean photoFlushScheduled;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable photoFlush = new Runnable() {
        @Override public void run() {
            photoFlushScheduled = false;
            List<String> batch = new ArrayList<>(photoPending);
            photoPending.clear();
            for (String uid : batch) fetchPhoto(uid);
        }
    };

    // Single AvatarSource for prefetch (was a new anonymous object per onScrolled)
    private final CallAvatarBinder.AvatarSource avatarSource = new CallAvatarBinder.AvatarSource() {
        @Override public String photo(int index) {
            CallLog l = logs.get(index);
            if (l.partnerPhoto != null && !l.partnerPhoto.isEmpty()) return l.partnerPhoto;
            return l.partnerUid != null ? photoCache.get(l.partnerUid) : null;
        }
        @Override public long avatarVersion(int index) { return 0; } // CallLog carries no avatarVersion today
        @Override public int size() { return logs.size(); }
    };

    public CallHistoryAdapter(List<CallLog> logs, SelectionListener listener) {
        this.logs = logs;
        this.selectionListener = listener;
    }

    public void setOnContactClickListener(OnContactClickListener l) {
        this.contactClickListener = l;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_call_history, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        bindFull(h, logs.get(pos));
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) { bindFull(h, logs.get(pos)); return; }
        CallLog l = logs.get(pos);
        boolean full = false;
        for (Object p : payloads) {
            if (p == PAYLOAD_SELECTION) bindSelection(h, l);
            else if (p == PAYLOAD_AVATAR) bindAvatar(h, l);
            else full = true;
        }
        if (full) bindFull(h, l);
    }

    private void bindFull(VH h, CallLog l) {
        h.uid = l.partnerUid;

        String name = l.partnerName == null ? "Unknown" : l.partnerName;
        if (!name.equals(h.name)) { h.name = name; h.tvName.setText(name); }

        VerifiedBadgeUtils.bindForUid(h.tvName, h.verifiedSetter, l.partnerUid);

        final boolean isVideo = "video".equals(l.mediaType);
        final int kind = kindOf(l.direction);
        final int styleKey = kind * 2 + (isVideo ? 1 : 0);
        if (styleKey != h.styleKey) {
            h.styleKey = styleKey;
            h.tvName.setTextColor(NAME_COLOR[kind]);
            h.tvMeta.setTextColor(META_COLOR[kind]);
            h.tvMeta.setCompoundDrawablesRelative(h.icon(styleKey, kind, isVideo), null, null, null);
        }

        String meta = metaFor(l, kind, isVideo);
        if (!meta.equals(h.meta)) { h.meta = meta; h.tvMeta.setText(meta); }

        bindAvatar(h, l);

        h.freshBind = true;
        applyRing(h, h.uid == null ? 0 : ringStateFor(h.itemView.getContext(), h.uid));

        bindSelection(h, l);
    }

    private void bindSelection(VH h, CallLog l) {
        h.itemView.setActivated(l.id != null && selectedIds.contains(l.id)); // no-op when unchanged
    }

    // ── kind / meta ────────────────────────────────────────────────────────────────────────────────
    private static int kindOf(String direction) {
        if (direction == null) return 3;
        if (containsIC(direction, "no_answer")) return 0;
        if (containsIC(direction, "missed"))    return 1;
        if (containsIC(direction, "incoming"))  return 2;
        return 3;
    }

    private static boolean containsIC(String s, String sub) {
        int n = s.length(), m = sub.length();
        for (int i = 0; i <= n - m; i++) if (s.regionMatches(true, i, sub, 0, m)) return true;
        return false;
    }

    private String metaFor(CallLog l, int kind, boolean isVideo) {
        String cached = metaCache.get(l);
        if (cached != null) return cached;
        StringBuilder sb = new StringBuilder(64);
        sb.append(isVideo ? LABEL_VIDEO[kind] : LABEL_AUDIO[kind]).append("  •  ");
        if (l.timestamp != null) { tmpDate.setTime(l.timestamp); sb.append(fmt.format(tmpDate)); }
        if (l.duration != null && l.duration > 0) {
            long total = l.duration / 1000, m = total / 60, s = total % 60;   // same as "%d:%02d"
            sb.append("  •  ").append(m).append(':');
            if (s < 10) sb.append('0');
            sb.append(s);
        }
        String out = sb.toString();
        metaCache.put(l, out);
        return out;
    }

    // ── avatar ─────────────────────────────────────────────────────────────────────────────────────
    private void bindAvatar(VH h, CallLog l) {
        String uid = l.partnerUid;
        if (uid == null || uid.isEmpty()) {
            if (h.avatarPhoto != null) resetAvatar(h);
            return;
        }
        String photo = photoCache.get(uid);                        // Priority 1: in-memory (same session)
        if (photo == null && l.partnerPhoto != null && !l.partnerPhoto.isEmpty()) {
            photo = l.partnerPhoto;                                 // Priority 2: Room cache — instant
            photoCache.put(uid, photo);
        }
        if (photo != null && !photo.isEmpty()) {
            if (!photo.equals(h.avatarPhoto)) {                     // same photo already bound → nothing to do
                h.avatarPhoto = photo;
                CallAvatarBinder.bind(h.itemView.getContext(), h.ivAvatar, photo, 0, R.drawable.ic_person);
            }
        } else {
            if (h.avatarPhoto != null) resetAvatar(h);
            requestPhoto(uid);                                      // Priority 3: de-duplicated, batched
        }
    }

    private void resetAvatar(VH h) {
        h.avatarPhoto = null;
        CallAvatarBinder.cancel(h.itemView.getContext(), h.ivAvatar);
        h.ivAvatar.setImageResource(R.drawable.ic_person);
    }

    private void requestPhoto(String uid) {
        if (!photoRequested.add(uid)) return;
        photoPending.add(uid);
        if (!photoFlushScheduled) {
            photoFlushScheduled = true;
            handler.postDelayed(photoFlush, 80L);
        }
    }

    private void fetchPhoto(final String uid) {
        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String photo = snap.child("photoUrl").getValue(String.class);
                String thumb = snap.child("thumbUrl").getValue(String.class);
                String av = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                if (av == null || av.isEmpty()) return;
                photoCache.put(uid, av);
                for (int i = 0; i < logs.size(); i++) {
                    CallLog l = logs.get(i);
                    if (uid.equals(l.partnerUid)) {
                        l.partnerPhoto = av;                        // next bind uses Room cache too
                        notifyItemChanged(i, PAYLOAD_AVATAR);       // avatar only — no full row rebind
                    }
                }
            }
            @Override public void onCancelled(DatabaseError e) { photoRequested.remove(uid); }
        });
    }

    // ── story ring ─────────────────────────────────────────────────────────────────────────────────
    /** 0 none, 1 unseen, 2 seen — ONE lookup (was hasUnseen/hasStatus 2x each per bind). */
    private static int ringStateFor(Context ctx, String uid) {
        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        if (scm.hasUnseen(uid)) return 1;
        return scm.hasStatus(uid) ? 2 : 0;
    }

    /** Background/clickable only touched when the ring state actually changes (no re-record otherwise). */
    private void applyRing(VH h, int state) {
        if (state == h.ringState) return;
        h.ringState = state;
        if (state == 0) {
            h.avatarHost.setBackground(null);
        } else {
            h.avatarHost.setBackgroundResource(
                state == 1 ? R.drawable.circle_status_unseen : R.drawable.circle_status_seen);
        }
        h.avatarHost.setClickable(state != 0);   // ring covers the avatar → ring tap (same as the old overlay)
        h.ivAvatar.setClickable(state == 0);
    }

    @Override public void onViewAttachedToWindow(@NonNull VH h) {
        super.onViewAttachedToWindow(h);
        // Registered only while attached: refreshAll() touches visible rows only, and no registry leak.
        StoryRingRegistry.register(h.itemView.getContext(), h.avatarHost, h.ringRefresh);
        if (!h.freshBind) h.ringRefresh.run();   // came back from the view cache without a rebind → re-check
        h.freshBind = false;
    }

    @Override public void onViewDetachedFromWindow(@NonNull VH h) {
        super.onViewDetachedFromWindow(h);
        StoryRingRegistry.unregister(h.avatarHost);
    }

    // ── actions ────────────────────────────────────────────────────────────────────────────────────
    private void fireContactClick(CallLog l) {
        if (contactClickListener != null) {
            String resolvedPhoto = l.partnerUid != null ? photoCache.get(l.partnerUid) : null;
            contactClickListener.onContactClick(l, resolvedPhoto);
        }
    }

    private void toggleSelection(int pos) {
        if (pos < 0 || pos >= logs.size()) return;
        CallLog l = logs.get(pos);
        if (l.id == null) return;
        if (selectedIds.contains(l.id)) selectedIds.remove(l.id);
        else selectedIds.add(l.id);
        notifyItemChanged(pos, PAYLOAD_SELECTION);
        if (selectedIds.isEmpty()) {
            isSelecting = false;
            if (selectionListener != null) selectionListener.onSelectionCleared();
        } else {
            if (selectionListener != null) selectionListener.onSelectionChanged();
        }
    }

    public void selectAll() {
        for (CallLog l : logs) if (l.id != null) selectedIds.add(l.id);
        notifyItemRangeChanged(0, logs.size(), PAYLOAD_SELECTION);
        if (selectionListener != null) selectionListener.onSelectionChanged();
    }

    public void clearSelection() {
        isSelecting = false; selectedIds.clear();
        notifyItemRangeChanged(0, logs.size(), PAYLOAD_SELECTION);
        if (selectionListener != null) selectionListener.onSelectionCleared();
    }

    public int getSelectedCount() { return selectedIds.size(); }
    public List<CallLog> getSelectedItems() {
        List<CallLog> sel = new ArrayList<>();
        for (CallLog l : logs) if (l.id != null && selectedIds.contains(l.id)) sel.add(l);
        return sel;
    }

    private void openChat(Context ctx, CallLog l) {
        if (l.partnerUid == null) return;
        Intent i = new Intent().setClassName(ctx.getPackageName(), "com.callx.app.conversation.ChatActivity");
        i.putExtra("partnerUid",  l.partnerUid);
        i.putExtra("partnerName", l.partnerName != null ? l.partnerName : "");
        ctx.startActivity(i);
    }

    private void openStatusOrChat(Context ctx, CallLog l) {
        if (l.partnerUid == null) { openChat(ctx, l); return; }
        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        if (scm.hasUnseen(l.partnerUid) || scm.hasStatus(l.partnerUid)) {
            Intent si = new Intent().setClassName(ctx.getPackageName(),
                    "com.callx.app.viewer.StatusViewerActivity");
            si.putExtra("ownerUid",  l.partnerUid);
            si.putExtra("ownerName", l.partnerName != null ? l.partnerName : "");
            ctx.startActivity(si);
        } else {
            openChat(ctx, l);
        }
    }

    @Override public int getItemCount() { return logs.size(); }

    /**
     * FIX (lifecycle-aware cancel): stops a row's in-flight avatar request
     * the moment it's recycled off screen, instead of letting it keep
     * competing for bandwidth/decode time against whatever's now visible.
     * v951: skipped when nothing was bound (no Glide.with lookup for empty rows).
     */
    @Override public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        if (h.avatarPhoto != null) {
            CallAvatarBinder.cancel(h.ivAvatar.getContext(), h.ivAvatar);
            h.avatarPhoto = null;
        }
    }

    /**
     * FIX (velocity-based prefetch): call from CallsFragment's RecyclerView
     * scroll listener with the first visible row index and the current
     * fling velocity (px/ms) — fast fling skips prefetch entirely, slow
     * scroll warms several rows ahead, both into the SAME
     * CallAvatarBinder.prefetch() every other avatar list in the app uses.
     */
    public void prefetchAvatars(Context ctx, int fromIndex, float velocityPxPerMs) {
        CallAvatarBinder.prefetch(ctx, avatarSource, fromIndex, velocityPxPerMs);
    }

    class VH extends RecyclerView.ViewHolder {
        final TextView tvName, tvMeta;
        final ImageButton btnCallBack;
        final FrameLayout avatarHost;
        final CircleImageView ivAvatar;

        // last-bound values (guards for every setter)
        String name, meta, avatarPhoto, uid;
        int styleKey = -1, ringState = 0;
        boolean verified, freshBind;

        final VerifiedBadgeUtils.BadgeSetter verifiedSetter;
        final Runnable ringRefresh;
        private final Drawable[] icons = new Drawable[8];
        private Drawable verifiedDrawable;
        private final int iconPx;

        VH(View v) {
            super(v);
            final Context ctx = v.getContext();
            tvName      = v.findViewById(R.id.tv_name);
            tvMeta      = v.findViewById(R.id.tv_meta);
            btnCallBack = v.findViewById(R.id.btn_call_back);
            avatarHost  = v.findViewById(R.id.avatar_host);
            ivAvatar    = v.findViewById(R.id.iv_avatar);
            iconPx = (int) (13 * ctx.getResources().getDisplayMetrics().density + 0.5f);

            verifiedSetter = isVerified -> {
                if (isVerified == verified) return;
                verified = isVerified;
                if (isVerified && verifiedDrawable == null) {
                    verifiedDrawable = ContextCompat.getDrawable(ctx,
                        com.callx.app.core.R.drawable.ic_verified_pink);
                    if (verifiedDrawable != null) verifiedDrawable.setBounds(0, 0, iconPx, iconPx);
                }
                tvName.setCompoundDrawablesRelative(null, null, isVerified ? verifiedDrawable : null, null);
            };

            ringRefresh = () -> applyRing(this, uid == null ? 0 : ringStateFor(ctx, uid));

            // ── listeners: created ONCE per holder, resolve the row by position at click time ──
            v.setOnClickListener(x -> {
                int p = getAdapterPosition();
                if (p == RecyclerView.NO_POSITION || p >= logs.size()) return;
                if (isSelecting) toggleSelection(p);
                else openChat(ctx, logs.get(p));      // Row click still goes to chat (keeps original behavior)
            });
            v.setOnLongClickListener(x -> {
                int p = getAdapterPosition();
                if (p == RecyclerView.NO_POSITION || p >= logs.size()) return false;
                if (!isSelecting) {
                    isSelecting = true;
                    CallLog l = logs.get(p);
                    if (l.id != null) selectedIds.add(l.id);
                    notifyItemChanged(p, PAYLOAD_SELECTION);   // only this row changes visually
                    if (selectionListener != null) selectionListener.onSelectionStarted();
                } else toggleSelection(p);
                return true;
            });
            View.OnClickListener contactClick = x -> {
                int p = getAdapterPosition();
                if (p == RecyclerView.NO_POSITION || p >= logs.size()) return;
                if (isSelecting) toggleSelection(p);
                else fireContactClick(logs.get(p));       // Avatar / name → ContactBottomSheet
            };
            ivAvatar.setOnClickListener(contactClick);
            tvName.setOnClickListener(contactClick);
            avatarHost.setOnClickListener(x -> {
                int p = getAdapterPosition();
                if (p == RecyclerView.NO_POSITION || p >= logs.size()) return;
                if (isSelecting) toggleSelection(p);
                else openStatusOrChat(ctx, logs.get(p));
            });
            avatarHost.setClickable(false);            // clickable only while the ring is visible
            btnCallBack.setOnClickListener(x -> {
                int p = getAdapterPosition();
                if (p == RecyclerView.NO_POSITION || p >= logs.size()) return;
                if (isSelecting) { toggleSelection(p); return; }
                CallLog l = logs.get(p);
                Intent i = new Intent(ctx, CallActivity.class);
                i.putExtra("partnerUid", l.partnerUid);
                i.putExtra("partnerName", l.partnerName);
                i.putExtra("isCaller", true);
                i.putExtra("video", false);
                ctx.startActivity(i);
            });
        }

        /** Direction icon, tinted once per (holder, style) — not setImageResource + new ColorFilter per bind. */
        Drawable icon(int styleKey, int kind, boolean isVideo) {
            Drawable d = icons[styleKey];
            if (d == null) {
                d = ContextCompat.getDrawable(itemView.getContext(),
                    isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone);
                if (d != null) {
                    d = d.mutate();
                    d.setTint(NAME_COLOR[kind]);
                    d.setBounds(0, 0, iconPx, iconPx);
                }
                icons[styleKey] = d;
            }
            return d;
        }
    }
}
