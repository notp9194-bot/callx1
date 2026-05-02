package com.callx.app.adapters;

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
import com.callx.app.R;
import com.callx.app.activities.CallActivity;
import com.callx.app.activities.ChatActivity;
import com.callx.app.models.CallLog;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import de.hdodenhof.circleimageview.CircleImageView;

import java.text.SimpleDateFormat;
import java.util.*;

public class CallHistoryAdapter extends RecyclerView.Adapter<CallHistoryAdapter.VH> {

    public interface SelectionListener {
        void onSelectionStarted();
        void onSelectionChanged();
        void onSelectionCleared();
    }

    private final List<CallLog> logs;
    private final SelectionListener selectionListener;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());

    // Selection state
    private boolean isSelecting = false;
    private final Set<String> selectedIds = new HashSet<>();

    public CallHistoryAdapter(List<CallLog> logs, SelectionListener listener) {
        this.logs = logs;
        this.selectionListener = listener;
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

        // Build meta text with call type label
        String dir    = l.direction == null ? "" : l.direction.toLowerCase();
        String typeLabel;
        int    nameColor;
        int    dirIconRes;
        int    dirIconColor;

        if (dir.contains("missed")) {
            typeLabel    = isVideo ? "Missed Video Call" : "Missed Voice Call";
            nameColor    = Color.parseColor("#EF4444");
            dirIconRes   = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone_off;
            dirIconColor = Color.parseColor("#EF4444");
        } else if (dir.contains("incoming") || dir.contains("in")) {
            typeLabel    = isVideo ? "Incoming Video Call" : "Incoming Voice Call";
            nameColor    = Color.parseColor("#22C55E");
            dirIconRes   = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
            dirIconColor = Color.parseColor("#22C55E");
        } else {
            typeLabel    = isVideo ? "Outgoing Video Call" : "Outgoing Voice Call";
            nameColor    = Color.parseColor("#5B5BF6");
            dirIconRes   = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
            dirIconColor = Color.parseColor("#5B5BF6");
        }

        h.tvName.setTextColor(nameColor);
        h.ivDirection.setColorFilter(dirIconColor);
        h.ivDirection.setImageResource(dirIconRes);

        String meta = typeLabel + "  •  " + when + (dur.isEmpty() ? "" : "  •  " + dur);
        h.tvMeta.setText(meta);
        h.tvMeta.setTextColor(dir.contains("missed") ? Color.parseColor("#EF4444") : Color.parseColor("#64748B"));

        // Load partner avatar from Firebase
        if (l.partnerUid != null && !l.partnerUid.isEmpty()) {
            FirebaseUtils.getUserRef(l.partnerUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        String photo = snap.child("photoUrl").getValue(String.class);
                        if (photo != null && !photo.isEmpty() && ctx != null) {
                            Glide.with(ctx)
                                .load(photo)
                                .apply(RequestOptions.circleCropTransform())
                                .placeholder(R.drawable.ic_person)
                                .error(R.drawable.ic_person)
                                .into(h.ivAvatar);
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        }

        // Selection highlight
        boolean selected = l.id != null && selectedIds.contains(l.id);
        h.itemView.setBackgroundColor(selected ? 0x335B5BF6 : 0x00000000);
        h.tvName.setAlpha(selected ? 0.7f : 1.0f);

        // Avatar click → open ChatActivity (or toggle selection)
        h.ivAvatar.setOnClickListener(v -> {
            if (isSelecting) toggleSelection(h.getAdapterPosition());
            else openChat(ctx, l);
        });
        h.tvName.setOnClickListener(v -> {
            if (isSelecting) toggleSelection(h.getAdapterPosition());
            else openChat(ctx, l);
        });
        h.tvName.setClickable(true);
        h.tvName.setFocusable(true);

        // Item click
        h.itemView.setOnClickListener(v -> {
            if (isSelecting) toggleSelection(h.getAdapterPosition());
        });

        // Long press to start selection
        h.itemView.setOnLongClickListener(v -> {
            if (!isSelecting) {
                isSelecting = true;
                if (l.id != null) selectedIds.add(l.id);
                notifyDataSetChanged();
                if (selectionListener != null) selectionListener.onSelectionStarted();
            } else {
                toggleSelection(h.getAdapterPosition());
            }
            return true;
        });

        // Voice call back
        h.btnCallBack.setOnClickListener(v -> {
            if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
            Intent i = new Intent(ctx, CallActivity.class);
            i.putExtra("partnerUid", l.partnerUid);
            i.putExtra("partnerName", l.partnerName);
            i.putExtra("isCaller", true);
            i.putExtra("video", false);
            ctx.startActivity(i);
        });

        // Video call back
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
        isSelecting = false;
        selectedIds.clear();
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
        if (l.partnerUid == null || l.partnerUid.isEmpty()) return;
        Intent i = new Intent(ctx, ChatActivity.class);
        i.putExtra("partnerUid",  l.partnerUid);
        i.putExtra("partnerName", l.partnerName != null ? l.partnerName : "");
        ctx.startActivity(i);
    }

    @Override public int getItemCount() { return logs.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvMeta;
        ImageButton btnCallBack, btnVideoCall;
        ImageView ivDirection;
        CircleImageView ivAvatar;
        VH(View v) {
            super(v);
            tvName       = v.findViewById(R.id.tv_name);
            tvMeta       = v.findViewById(R.id.tv_meta);
            btnCallBack  = v.findViewById(R.id.btn_call_back);
            btnVideoCall = v.findViewById(R.id.btn_video_call);
            ivDirection  = v.findViewById(R.id.iv_direction);
            ivAvatar     = v.findViewById(R.id.iv_avatar);
        }
    }
}
