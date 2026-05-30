package com.callx.app.adapters;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;

import com.callx.app.activities.ChatActivity;
import com.callx.app.models.User;
import de.hdodenhof.circleimageview.CircleImageView;
import com.callx.app.cache.StatusCacheManager;

import java.text.SimpleDateFormat;
import java.util.*;

public class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.VH> {

    public interface SelectionListener {
        void onSelectionStarted();
        void onSelectionChanged();
        void onSelectionCleared();
    }

    public interface OnAvatarClickListener {
        void onAvatarClick(User user);
    }

    private final List<User> contacts;
    private final SelectionListener selectionListener;
    private OnAvatarClickListener avatarClickListener;
    // Change 3: 12-hour format
    private final SimpleDateFormat fmt = new SimpleDateFormat("hh:mm a", Locale.getDefault());
    private Set<String> specialRequestSenders = new HashSet<>();

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

    public void setOnAvatarClickListener(OnAvatarClickListener listener) {
        this.avatarClickListener = listener;
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

        // thumbUrl → 100px WebP, fast load. Fallback: photoUrl
        String avatarUrl = (u.thumbUrl != null && !u.thumbUrl.isEmpty())
            ? u.thumbUrl : u.photoUrl;
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(ctx)
                .load(avatarUrl)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        // Story ring + avatar click logic
        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        boolean hasStory = u.uid != null && (scm.hasUnseen(u.uid) || scm.hasStatus(u.uid));

        if (h.ivStoryRing != null && u.uid != null) {
            if (scm.hasUnseen(u.uid)) {
                h.ivStoryRing.setBackgroundResource(R.drawable.circle_status_unseen);
                h.ivStoryRing.setVisibility(View.VISIBLE);
            } else if (scm.hasStatus(u.uid)) {
                h.ivStoryRing.setBackgroundResource(R.drawable.circle_status_seen);
                h.ivStoryRing.setVisibility(View.VISIBLE);
            } else {
                h.ivStoryRing.setVisibility(View.GONE);
            }
            h.ivStoryRing.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                openStatusOrChat(ctx, u);
            });
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
            h.tvLastMessage.setTextColor(ctx.getResources().getColor(R.color.text_primary));
        } else {
            h.tvUnread.setVisibility(View.GONE);
            h.tvLastMessage.setTextColor(ctx.getResources().getColor(R.color.text_secondary));
        }

        boolean isSpecial = u.uid != null && specialRequestSenders.contains(u.uid);
        if (isSpecial) {
            h.tvLastMessage.setText("⭐ Special unblock request");
            h.tvLastMessage.setTextColor(0xFFFF8F00);
        }

        boolean selected = u.uid != null && selectedUids.contains(u.uid);
        h.itemView.setBackgroundColor(selected ? 0x335B5BF6 : (isSpecial ? 0x33FFC107 : 0x00000000));

        // Story ring click → open StatusViewerActivity (only when ring is visible)
        // (already set above in ivStoryRing block)

        h.ivAvatar.setOnClickListener(v -> {
            if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
            if (avatarClickListener != null) {
                avatarClickListener.onAvatarClick(u);
            } else {
                if (hasStory) openStatusOrChat(ctx, u);
                else openChat(ctx, u);
            }
        });

        h.ivAvatar.setOnLongClickListener(v -> {
            showAvatarZoom(ctx, u.photoUrl, u.name);
            return true;
        });

        h.itemView.setOnClickListener(v -> {
            if (isSelecting) toggleSelection(h.getAdapterPosition());
            else openChat(ctx, u);
        });

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

        h.btnCall.setOnClickListener(v -> {
            if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
            Intent i = new Intent().setClassName(ctx.getPackageName(), "com.callx.app.activities.CallActivity");
            i.putExtra("partnerUid", u.uid);
            i.putExtra("partnerName", u.name);
            i.putExtra("isCaller", true);
            i.putExtra("video", false);
            ctx.startActivity(i);
        });

        if (h.btnVideoCall != null) {
            h.btnVideoCall.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                Intent i = new Intent().setClassName(ctx.getPackageName(), "com.callx.app.activities.CallActivity");
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
        i.putExtra("partnerUid",   u.uid);
        i.putExtra("partnerName",  u.name);
        i.putExtra("partnerPhoto", u.photoUrl != null ? u.photoUrl : "");
        i.putExtra("partnerThumb", u.thumbUrl != null ? u.thumbUrl : "");
        ctx.startActivity(i);
    }

    private void openStatusOrChat(Context ctx, User u) {
        if (u.uid == null) { openChat(ctx, u); return; }
        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        if (scm.hasUnseen(u.uid) || scm.hasStatus(u.uid)) {
            Intent si = new Intent().setClassName(ctx.getPackageName(),
                    "com.callx.app.activities.StatusViewerActivity");
            si.putExtra("ownerUid",  u.uid);
            si.putExtra("ownerName", u.name != null ? u.name : "");
            ctx.startActivity(si);
        } else {
            openChat(ctx, u);
        }
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
        isSelecting = false; selectedUids.clear();
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

    // ── Avatar Zoom Dialog ────────────────────────────────────────────────
    private void showAvatarZoom(Context ctx, String photoUrl, String name) {
        Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        android.widget.FrameLayout root = new android.widget.FrameLayout(ctx);
        root.setBackgroundColor(0xEE000000);

        // PhotoView — supports pinch-to-zoom natively
        com.github.chrisbanes.photoview.PhotoView photoView =
            new com.github.chrisbanes.photoview.PhotoView(ctx);
        android.widget.FrameLayout.LayoutParams ivLp = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        photoView.setLayoutParams(ivLp);
        photoView.setMinimumScale(1f);
        photoView.setMediumScale(2f);
        photoView.setMaximumScale(5f);
        photoView.setOnOutsidePhotoTapListener(v -> dialog.dismiss());
        photoView.setOnPhotoTapListener((v, x, y) -> { /* prevent dismiss on photo tap */ });

        // Close button top-right
        android.widget.ImageButton btnClose = new android.widget.ImageButton(ctx);
        int closeSizePx = (int)(40 * ctx.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout.LayoutParams closeLp =
            new android.widget.FrameLayout.LayoutParams(closeSizePx, closeSizePx);
        closeLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        closeLp.topMargin = (int)(40 * ctx.getResources().getDisplayMetrics().density);
        closeLp.rightMargin = (int)(16 * ctx.getResources().getDisplayMetrics().density);
        btnClose.setLayoutParams(closeLp);
        btnClose.setImageResource(R.drawable.ic_close);
        btnClose.setBackgroundColor(0x00000000);
        btnClose.setOnClickListener(v -> dialog.dismiss());

        // Name label bottom
        android.widget.TextView tvName = new android.widget.TextView(ctx);
        tvName.setText(name != null ? name : "");
        tvName.setTextColor(0xFFFFFFFF);
        tvName.setTextSize(15f);
        tvName.setGravity(android.view.Gravity.CENTER);
        tvName.setPadding(0, 0, 0, (int)(32 * ctx.getResources().getDisplayMetrics().density));
        android.widget.FrameLayout.LayoutParams nameLp =
            new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        nameLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        tvName.setLayoutParams(nameLp);

        if (photoUrl != null && !photoUrl.isEmpty()) {
            Glide.with(ctx).load(photoUrl)
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(photoView);
        } else {
            photoView.setImageResource(R.drawable.ic_person);
        }

        root.addView(photoView);
        root.addView(tvName);
        root.addView(btnClose);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) w.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT);
        dialog.show();
    }

    @Override public int getItemCount() { return contacts.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvLastMessage, tvTime, tvUnread;
        CircleImageView ivAvatar;
        android.widget.ImageView ivStoryRing;
        ImageButton btnCall, btnVideoCall;
        VH(View v) {
            super(v);
            tvName        = v.findViewById(R.id.tv_name);
            tvLastMessage = v.findViewById(R.id.tv_last_message);
            tvTime        = v.findViewById(R.id.tv_time);
            tvUnread      = v.findViewById(R.id.tv_unread_badge);
            ivAvatar      = v.findViewById(R.id.iv_avatar);
            ivStoryRing   = v.findViewById(R.id.iv_story_ring);
            btnCall       = v.findViewById(R.id.btn_call);
            btnVideoCall  = v.findViewById(R.id.btn_video_call);
        }
    }
}
