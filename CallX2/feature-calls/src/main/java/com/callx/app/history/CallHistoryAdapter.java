package com.callx.app.history;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.*;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.calls.R;
import com.callx.app.call.CallActivity;

import com.callx.app.models.CallLog;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import com.callx.app.cache.StatusCacheManager;

import java.text.SimpleDateFormat;
import java.util.*;

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

    private final List<CallLog> logs;
    private final SelectionListener selectionListener;
    private OnContactClickListener contactClickListener;

    // 12-hour format with AM/PM
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());

    private boolean isSelecting = false;
    private final Set<String> selectedIds = new HashSet<>();

    // Cache resolved photo URLs so bottom sheet can use them instantly
    private final Map<String, String> photoCache = new HashMap<>();

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
        CallLog l = logs.get(pos);
        Context ctx = h.itemView.getContext();

        h.tvName.setText(l.partnerName == null ? "Unknown" : l.partnerName);

        String when = l.timestamp != null ? fmt.format(new Date(l.timestamp)) : "";
        String dur  = (l.duration != null && l.duration > 0)
            ? FileUtils.formatDuration(l.duration) : "";
        boolean isVideo = "video".equals(l.mediaType);

        String dir = l.direction == null ? "" : l.direction.toLowerCase();
        String typeLabel;
        int nameColor, dirIconColor, dirIconRes;

        if (dir.contains("missed")) {
            // Red — missed call
            typeLabel    = isVideo ? "Missed Video Call" : "Missed Voice Call";
            nameColor    = Color.parseColor("#EF4444");
            dirIconRes   = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
            dirIconColor = Color.parseColor("#EF4444");
        } else if (dir.contains("incoming")) {
            // Yellow — incoming call
            typeLabel    = isVideo ? "Incoming Video Call" : "Incoming Voice Call";
            nameColor    = Color.parseColor("#F59E0B");
            dirIconRes   = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
            dirIconColor = Color.parseColor("#F59E0B");
        } else {
            // Green — outgoing call
            typeLabel    = isVideo ? "Outgoing Video Call" : "Outgoing Voice Call";
            nameColor    = Color.parseColor("#22C55E");
            dirIconRes   = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
            dirIconColor = Color.parseColor("#22C55E");
        }

        h.tvName.setTextColor(nameColor);
        if (h.ivDirection != null) {
            h.ivDirection.setImageResource(dirIconRes);
            h.ivDirection.setColorFilter(dirIconColor);
        }

        String meta = typeLabel + "  •  " + when + (dur.isEmpty() ? "" : "  •  " + dur);
        h.tvMeta.setText(meta);
        h.tvMeta.setTextColor(dir.contains("missed")
            ? Color.parseColor("#EF4444")
            : dir.contains("incoming")
                ? Color.parseColor("#F59E0B")
                : Color.parseColor("#64748B"));

        // Load avatar — also cache resolved URL for bottom sheet
        if (l.partnerUid != null && !l.partnerUid.isEmpty() && h.ivAvatar != null) {
            // Use cached if available
            if (photoCache.containsKey(l.partnerUid)) {
                String cached = photoCache.get(l.partnerUid);
                if (cached != null && !cached.isEmpty())
                    Glide.with(ctx).load(cached)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person)
                        .into(h.ivAvatar);
            } else {
                FirebaseUtils.getUserRef(l.partnerUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot snap) {
                            String photo = snap.child("photoUrl").getValue(String.class);
                            String thumb = snap.child("thumbUrl").getValue(String.class);
                            String callAvatar = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                            // Cache it
                            if (callAvatar != null) photoCache.put(l.partnerUid, callAvatar);
                            if (callAvatar != null && !callAvatar.isEmpty() && ctx != null)
                                Glide.with(ctx).load(callAvatar)
                                    .apply(RequestOptions.circleCropTransform())
                                    .placeholder(R.drawable.ic_person)
                                    .into(h.ivAvatar);
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    });
            }
        }

        // Story ring — unseen status indicator
        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        boolean hasStory = l.partnerUid != null && (scm.hasUnseen(l.partnerUid) || scm.hasStatus(l.partnerUid));

        if (h.ivStoryRing != null && l.partnerUid != null) {
            if (scm.hasUnseen(l.partnerUid)) {
                h.ivStoryRing.setBackgroundResource(R.drawable.circle_status_unseen);
                h.ivStoryRing.setVisibility(View.VISIBLE);
            } else if (scm.hasStatus(l.partnerUid)) {
                h.ivStoryRing.setBackgroundResource(R.drawable.circle_status_seen);
                h.ivStoryRing.setVisibility(View.VISIBLE);
            } else {
                h.ivStoryRing.setVisibility(View.GONE);
            }
            h.ivStoryRing.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                openStatusOrChat(ctx, l);
            });
        }

        // ── Avatar click → ContactBottomSheet (Reels-profile style) ──
        if (h.ivAvatar != null) {
            h.ivAvatar.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                fireContactClick(l);
            });
        }

        // ── Name click → ContactBottomSheet ──
        if (h.tvName != null) {
            h.tvName.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                fireContactClick(l);
            });
        }

        boolean selected = l.id != null && selectedIds.contains(l.id);
        h.itemView.setBackgroundColor(selected ? 0x335B5BF6 : 0x00000000);

        h.itemView.setOnClickListener(v -> {
            if (isSelecting) toggleSelection(h.getAdapterPosition());
            // Row click still goes to chat (keeps original behavior)
            else openChat(ctx, l);
        });

        h.itemView.setOnLongClickListener(v -> {
            if (!isSelecting) {
                isSelecting = true;
                if (l.id != null) selectedIds.add(l.id);
                notifyDataSetChanged();
                if (selectionListener != null) selectionListener.onSelectionStarted();
            } else toggleSelection(h.getAdapterPosition());
            return true;
        });

        if (h.btnCallBack != null) {
            h.btnCallBack.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                Intent i = new Intent(ctx, CallActivity.class);
                i.putExtra("partnerUid", l.partnerUid);
                i.putExtra("partnerName", l.partnerName);
                i.putExtra("isCaller", true);
                i.putExtra("video", false);
                ctx.startActivity(i);
            });
        }

        if (h.btnVideoCall != null) {
            h.btnVideoCall.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                Intent i = new Intent(ctx, CallActivity.class);
                i.putExtra("partnerUid", l.partnerUid);
                i.putExtra("partnerName", l.partnerName);
                i.putExtra("isCaller", true);
                i.putExtra("video", true);
                ctx.startActivity(i);
            });
        }
    }

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
        notifyItemChanged(pos);
        if (selectedIds.isEmpty()) {
            isSelecting = false;
            if (selectionListener != null) selectionListener.onSelectionCleared();
        } else {
            if (selectionListener != null) selectionListener.onSelectionChanged();
        }
    }

    public void selectAll() {
        for (CallLog l : logs) if (l.id != null) selectedIds.add(l.id);
        notifyDataSetChanged();
        if (selectionListener != null) selectionListener.onSelectionChanged();
    }

    public void clearSelection() {
        isSelecting = false; selectedIds.clear();
        notifyDataSetChanged();
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

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvMeta;
        ImageButton btnCallBack, btnVideoCall;
        ImageView ivDirection, ivStoryRing;
        CircleImageView ivAvatar;
        VH(View v) {
            super(v);
            tvName       = v.findViewById(R.id.tv_name);
            tvMeta       = v.findViewById(R.id.tv_meta);
            btnCallBack  = v.findViewById(R.id.btn_call_back);
            btnVideoCall = v.findViewById(R.id.btn_video_call);
            ivDirection  = v.findViewById(R.id.iv_direction);
            ivAvatar     = v.findViewById(R.id.iv_avatar);
            ivStoryRing  = v.findViewById(R.id.iv_story_ring);
        }
    }
}
