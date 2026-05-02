package com.callx.app.adapters;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.activities.CallActivity;
import com.callx.app.activities.ChatActivity;
import com.callx.app.models.User;
import de.hdodenhof.circleimageview.CircleImageView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
public class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.VH> {
    private final List<User> contacts;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    // Feature 18/19: jin senders ne mujhe special request bheji unka set
    private java.util.Set<String> specialRequestSenders =
        new java.util.HashSet<>();
    public ChatListAdapter(List<User> contacts) { this.contacts = contacts; }
    public void setSpecialRequestSenders(java.util.Set<String> set) {
        this.specialRequestSenders = set == null
            ? new java.util.HashSet<>() : set;
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
            Glide.with(ctx).load(u.photoUrl).into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }
        // Last message preview (falls back to last-seen time text)
        if (u.lastMessage != null && !u.lastMessage.isEmpty()) {
            h.tvLastMessage.setText(u.lastMessage);
        } else {
            h.tvLastMessage.setText("Tap karke chat karo");
        }
        // Time: prefer lastMessageAt, then lastSeen
        Long when = u.lastMessageAt != null ? u.lastMessageAt : u.lastSeen;
        if (when != null && when > 0) {
            h.tvTime.setText(fmt.format(new Date(when)));
        } else {
            h.tvTime.setText("");
        }
        // WhatsApp style unread badge
        long unread = u.unread == null ? 0 : u.unread;
        if (unread > 0) {
            h.tvUnread.setText(unread > 99 ? "99+" : String.valueOf(unread));
            h.tvUnread.setVisibility(View.VISIBLE);
            h.tvName.setTextColor(
                ctx.getResources().getColor(R.color.text_primary));
            h.tvLastMessage.setTextColor(
                ctx.getResources().getColor(R.color.text_primary));
        } else {
            h.tvUnread.setVisibility(View.GONE);
            h.tvLastMessage.setTextColor(
                ctx.getResources().getColor(R.color.text_secondary));
        }
        // Feature 18/19: highlighted entry — special request sender
        // chat list me sabse upar amber background ke saath dikhe.
        boolean isSpecial = u.uid != null
            && specialRequestSenders.contains(u.uid);
        if (isSpecial) {
            h.itemView.setBackgroundColor(0x33FFC107); // amber wash
            h.tvLastMessage.setText("⭐ Special unblock request");
            h.tvLastMessage.setTextColor(0xFFFF8F00);
            h.tvName.setTextColor(
                ctx.getResources().getColor(R.color.text_primary));
        } else {
            h.itemView.setBackgroundColor(0x00000000);
        }
        h.itemView.setOnClickListener(v -> {
            Intent i = new Intent(ctx, ChatActivity.class);
            i.putExtra("partnerUid", u.uid);
            i.putExtra("partnerName", u.name);
            ctx.startActivity(i);
        });
        h.btnCall.setOnClickListener(v -> {
            Intent i = new Intent(ctx, CallActivity.class);
            i.putExtra("partnerUid", u.uid);
            i.putExtra("partnerName", u.name);
            i.putExtra("isCaller", true);
            ctx.startActivity(i);
        });
    }
    @Override public int getItemCount() { return contacts.size(); }
    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvLastMessage, tvTime, tvUnread;
        CircleImageView ivAvatar;
        ImageButton btnCall;
        VH(View v) {
            super(v);
            tvName        = v.findViewById(R.id.tv_name);
            tvLastMessage = v.findViewById(R.id.tv_last_message);
            tvTime        = v.findViewById(R.id.tv_time);
            tvUnread      = v.findViewById(R.id.tv_unread_badge);
            ivAvatar      = v.findViewById(R.id.iv_avatar);
            btnCall       = v.findViewById(R.id.btn_call);
        }
    }
}
