package com.callx.app.adapters;

import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.R;
import com.callx.app.activities.CallActivity;
import com.callx.app.activities.ChatActivity;
import com.callx.app.models.User;
import de.hdodenhof.circleimageview.CircleImageView;

import java.text.SimpleDateFormat;
import java.util.*;

public class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.VH> {

    public interface SelectionListener {
        void onSelectionStarted();
        void onSelectionChanged();
        void onSelectionCleared();
    }

    private final List<User> contacts;
    private final SelectionListener selectionListener;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private Set<String> specialRequestSenders = new HashSet<>();

    // Selection state
    private boolean isSelecting = false;
    private final Set<String> selectedUids = new HashSet<>();

    public ChatListAdapter(List<User> contacts, SelectionListener listener) {
        this.contacts          = contacts;
        this.selectionListener = listener;
    }

    public void setSpecialRequestSenders(Set<String> set) {
        this.specialRequestSenders = set == null ? new HashSet<>() : set;
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_chat, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        User u = contacts.get(pos);
        Context ctx = h.itemView.getContext();

        h.tvName.setText(u.name == null ? "User" : u.name);

        if (u.photoUrl != null && !u.photoUrl.isEmpty()) {
            Glide.with(ctx)
                .load(u.photoUrl)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        if (u.lastMessage != null && !u.lastMessage.isEmpty())
            h.tvLastMessage.setText(u.lastMessage);
        else
            h.tvLastMessage.setText("Tap karke chat karo");

        Long when = u.lastMessageAt != null ? u.lastMessageAt : u.lastSeen;
        h.tvTime.setText((when != null && when > 0) ? fmt.format(new Date(when)) : "");

        long unread = u.unread == null ? 0 : u.unread;
        if (unread > 0) {
            h.tvUnread.setText(unread > 99 ? "99+" : String.valueOf(unread));
            h.tvUnread.setVisibility(View.VISIBLE);
            h.tvName.setTextColor(ctx.getResources().getColor(R.color.text_primary));
            h.tvLastMessage.setTextColor(ctx.getResources().getColor(R.color.text_primary));
        } else {
            h.tvUnread.setVisibility(View.GONE);
            h.tvLastMessage.setTextColor(ctx.getResources().getColor(R.color.text_secondary));
        }

        boolean isSpecial = u.uid != null && specialRequestSenders.contains(u.uid);
        if (isSpecial) {
            h.tvLastMessage.setText("⭐ Special unblock request");
            h.tvLastMessage.setTextColor(0xFFFF8F00);
            h.tvName.setTextColor(ctx.getResources().getColor(R.color.text_primary));
        }

        // Selection highlight
        boolean selected = u.uid != null && selectedUids.contains(u.uid);
        h.itemView.setBackgroundColor(selected ? 0x335B5BF6 : (isSpecial ? 0x33FFC107 : 0x00000000));

        // Avatar click → open chat (not profile, in list view)
        h.ivAvatar.setOnClickListener(v -> {
            if (isSelecting) toggleSelection(h.getAdapterPosition());
            else openChat(ctx, u);
        });

        // Item click
        h.itemView.setOnClickListener(v -> {
            if (isSelecting) toggleSelection(h.getAdapterPosition());
            else openChat(ctx, u);
        });

        // Long press → start selection
        h.itemView.setOnLongClickListener(v -> {
            if (!isSelecting) {
                isSelecting = true;
                if (u.uid != null) selectedUids.add(u.uid);
                notifyDataSetChanged();
                if (selectionListener != null) selectionListener.onSelectionStarted();
            } else {
                toggleSelection(h.getAdapterPosition());
            }
            return true;
        });

        // Voice call button
        h.btnCall.setOnClickListener(v -> {
            if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
            Intent i = new Intent(ctx, CallActivity.class);
            i.putExtra("partnerUid", u.uid);
            i.putExtra("partnerName", u.name);
            i.putExtra("isCaller", true);
            i.putExtra("video", false);
            ctx.startActivity(i);
        });

        // Video call button
        if (h.btnVideoCall != null) {
            h.btnVideoCall.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                Intent i = new Intent(ctx, CallActivity.class);
                i.putExtra("partnerUid", u.uid);
                i.putExtra("partnerName", u.name);
                i.putExtra("isCaller", true);
                i.putExtra("video", true);
                ctx.startActivity(i);
            });
        }
    }

    private void openChat(Context ctx, User u) {
        Intent i = new Intent(ctx, ChatActivity.class);
        i.putExtra("partnerUid",  u.uid);
        i.putExtra("partnerName", u.name);
        ctx.startActivity(i);
    }

    private void toggleSelection(int pos) {
        if (pos < 0 || pos >= contacts.size()) return;
        User u = contacts.get(pos);
        if (u.uid == null) return;
        if (selectedUids.contains(u.uid)) selectedUids.remove(u.uid);
        else selectedUids.add(u.uid);
        notifyItemChanged(pos);
        if (selectedUids.isEmpty()) {
            isSelecting = false;
            if (selectionListener != null) selectionListener.onSelectionCleared();
        } else {
            if (selectionListener != null) selectionListener.onSelectionChanged();
        }
    }

    public void selectAll() {
        for (User u : contacts) if (u.uid != null) selectedUids.add(u.uid);
        notifyDataSetChanged();
        if (selectionListener != null) selectionListener.onSelectionChanged();
    }

    public void clearSelection() {
        isSelecting = false;
        selectedUids.clear();
        notifyDataSetChanged();
        if (selectionListener != null) selectionListener.onSelectionCleared();
    }

    public int getSelectedCount() { return selectedUids.size(); }

    public List<User> getSelectedItems() {
        List<User> sel = new ArrayList<>();
        for (User u : contacts)
            if (u.uid != null && selectedUids.contains(u.uid)) sel.add(u);
        return sel;
    }

    @Override public int getItemCount() { return contacts.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvLastMessage, tvTime, tvUnread;
        CircleImageView ivAvatar;
        ImageButton btnCall, btnVideoCall;
        VH(View v) {
            super(v);
            tvName        = v.findViewById(R.id.tv_name);
            tvLastMessage = v.findViewById(R.id.tv_last_message);
            tvTime        = v.findViewById(R.id.tv_time);
            tvUnread      = v.findViewById(R.id.tv_unread_badge);
            ivAvatar      = v.findViewById(R.id.iv_avatar);
            btnCall       = v.findViewById(R.id.btn_call);
            btnVideoCall  = v.findViewById(R.id.btn_video_call);
        }
    }
}
