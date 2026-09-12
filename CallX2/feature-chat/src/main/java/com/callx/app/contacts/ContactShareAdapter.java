package com.callx.app.contacts;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.cache.ChatAvatarBinder;
import com.callx.app.chat.R;
import com.callx.app.models.User;
import com.callx.app.utils.AvatarSizeTier;

import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

public class ContactShareAdapter
        extends RecyclerView.Adapter<ContactShareAdapter.ContactVH> {

    // FIX (avatar-optimization — Forward-to-contact picker): row avatar
    // (48dp, item_contact_share.xml) now shares ChatAvatarBinder's
    // ChatAvatarL2Cache/L3 + CDN analytics with the SAME partner's chat
    // list row instead of a separate flat, un-tiered decode per open.
    private static final AvatarSizeTier AVATAR_TIER = AvatarSizeTier.forViewSizeDp(48);

    public interface OnContactShareListener {
        void onShareToContact(User contact);
    }

    private final List<User>              contacts;
    private final OnContactShareListener  listener;

    public ContactShareAdapter(List<User> contacts, OnContactShareListener listener) {
        this.contacts = contacts;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ContactVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_contact_share, parent, false);
        return new ContactVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ContactVH h, int pos) {
        User contact = contacts.get(pos);
        h.tvName.setText(contact.name != null ? contact.name : "User");
        String avatarUrl = (contact.thumbUrl != null && !contact.thumbUrl.isEmpty())
            ? contact.thumbUrl : contact.photoUrl;
        ChatAvatarBinder.bind(h.itemView.getContext(), h.ivAvatar, avatarUrl,
                contact.avatarVersion, R.drawable.ic_person, AVATAR_TIER);

        h.itemView.setOnClickListener(v -> listener.onShareToContact(contact));
    }

    @Override public int getItemCount() { return contacts.size(); }

    @Override public void onViewRecycled(@NonNull ContactVH h) {
        super.onViewRecycled(h);
        ChatAvatarBinder.cancel(h.ivAvatar.getContext(), h.ivAvatar);
    }

    static class ContactVH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        TextView        tvName;

        ContactVH(View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_share_contact_avatar);
            tvName   = v.findViewById(R.id.tv_share_contact_name);
        }
    }
}
