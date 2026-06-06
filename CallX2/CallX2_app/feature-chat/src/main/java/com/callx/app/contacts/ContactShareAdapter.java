package com.callx.app.contacts;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;
import com.callx.app.models.User;

import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

public class ContactShareAdapter
        extends RecyclerView.Adapter<ContactShareAdapter.ContactVH> {

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
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(h.itemView.getContext())
                .load(avatarUrl)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        h.itemView.setOnClickListener(v -> listener.onShareToContact(contact));
    }

    @Override public int getItemCount() { return contacts.size(); }

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
