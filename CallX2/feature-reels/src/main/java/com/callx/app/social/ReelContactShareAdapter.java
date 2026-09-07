package com.callx.app.social;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.followers.FollowAvatarBinder;
import com.callx.app.reels.R;
import com.callx.app.models.User;
import com.callx.app.utils.Constants;

import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * ★ UPGRADE: avatar loading ab reused/connected hai us hi deep avatar-optimization
 * pipeline se jo FollowConnectionsActivity (FollowAvatarBinder), reel comments
 * (ReelCommentAvatarBinder), Home stories tray (HomeStoryAvatarBinder) etc. use
 * karte hain — L2 memory cache + L3 disk tier reuse (ReelsAvatarL2Cache), shared
 * AvatarSizeTier bucketing + responsive WebP/AVIF URL (AvatarUrlBuilder), density-aware
 * decode size, aur velocity-based scroll prefetch. Pehle ye adapter isse bypass karke
 * seedha flat {@code Glide.load(photo).circleCropTransform()} karta tha — koi tiering,
 * cache-bust, ya L2/L3 reuse nahi tha (har baar cold re-download).
 *
 * Same module (feature-reels) me hone ki wajah se FollowAvatarBinder ko seedha reuse
 * kiya — ek naya parallel cache khada karne ki zaroorat nahi.
 *
 * ★ ULTRA-OPTIMIZED (online dot): naya "har bind pe timestamp math" hata diya.
 * onBindViewHolder RecyclerView ke fast-fling ke dauraan HUNDREDS of times/second
 * chal sakta hai jab wahi handful views recycle hote rehte hain — har baar
 * {@code System.currentTimeMillis()} + subtraction + null-check karna waste hai
 * jab data khud snapshot-based hai aur beech me change hi nahi hota. Ab poora
 * online/offline snapshot EK BAAR compute hota hai (refreshOnlineSnapshot(),
 * loadContacts() se turant contacts fill hone ke baad call hota hai) aur ek
 * primitive {@code boolean[]} me store hota hai (autoboxed List<Boolean> se bhi
 * bachaya) — onBindViewHolder sirf ek array-index read karta hai, zero arithmetic,
 * zero allocation, branch-free.
 *
 * ★ ULTRA-OPTIMIZED (prefetch source): avatarSource() pehle onScrolled() ke HAR
 * single frame pe ek naya anonymous AvatarSource object allocate karta tha — fast
 * scroll me ye sainkdon short-lived allocations/sec ban jaate (GC churn). Ab ek
 * hi instance banake final field me cache kiya hai, reused hota hai.
 */
public class ReelContactShareAdapter
        extends RecyclerView.Adapter<ReelContactShareAdapter.ContactVH> {

    public interface OnContactShareListener {
        void onShareToContact(User contact);
    }

    private final List<User>             contacts;
    private final OnContactShareListener listener;

    // Precomputed online/offline snapshot — index-aligned with `contacts`,
    // primitive boolean[] (no autoboxing) so onBindViewHolder is a pure O(1)
    // array read instead of per-bind timestamp arithmetic. Sized/filled by
    // refreshOnlineSnapshot(), called once right after `contacts` is populated.
    private boolean[] onlineSnapshot = new boolean[0];

    // Reused across every onScrolled() frame instead of allocating a fresh
    // anonymous AvatarSource each time — see class doc.
    private final FollowAvatarBinder.AvatarSource avatarSourceView =
        new FollowAvatarBinder.AvatarSource() {
            @Override public String photo(int index) {
                User u = contacts.get(index);
                return (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
            }
            @Override public long avatarVersion(int index) { return contacts.get(index).avatarVersion; }
            @Override public int size() { return contacts.size(); }
        };

    public ReelContactShareAdapter(List<User> contacts, OnContactShareListener listener) {
        this.contacts = contacts;
        this.listener = listener;
    }

    /**
     * Recompute the online/offline snapshot for every contact currently in
     * `contacts`, against a SINGLE {@code now} timestamp shared by the whole
     * batch (one syscall instead of one per row). Call this once, right after
     * `contacts` is filled/replaced — e.g. loadContacts()'s onDataChange,
     * before notifyDataSetChanged() — never from onBindViewHolder.
     */
    public void refreshOnlineSnapshot() {
        int n = contacts.size();
        if (onlineSnapshot.length != n) onlineSnapshot = new boolean[n];
        long now = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            Long lastSeen = contacts.get(i).lastSeen;
            onlineSnapshot[i] = lastSeen != null && (now - lastSeen) < Constants.ONLINE_WINDOW_MS;
        }
    }

    @NonNull
    @Override
    public ContactVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // ★ UPGRADE: dedicated grid-cell layout (see item_reel_share_contact_grid.xml)
        // instead of the shared item_contact_share.xml — this adapter now feeds a
        // GridLayoutManager (3 columns), and match_parent-width cells would otherwise
        // have broken the other two callers of item_contact_share.xml.
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_reel_share_contact_grid, parent, false);
        return new ContactVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ContactVH h, int pos) {
        User contact = contacts.get(pos);
        h.tvName.setText(contact.name != null ? contact.name : "User");

        // ★ UPGRADE: same tiered/cached bind() FollowConnectionsActivity uses —
        // L2 memory hit paints instantly, otherwise a density-aware RESOURCE-cached
        // decode that writes back into L2+L3 for the next bind. thumbUrl (100x100,
        // already-optimized chat/notification variant) preferred over the raw
        // photoUrl when present, same preference the old inline load had.
        String avatarSource = (contact.thumbUrl != null && !contact.thumbUrl.isEmpty())
            ? contact.thumbUrl : contact.photoUrl;
        FollowAvatarBinder.bind(
            h.itemView.getContext(), h.ivAvatar, avatarSource, contact.avatarVersion, R.drawable.ic_person);

        // ★ ULTRA-OPTIMIZED: precomputed array read — zero timestamp math on the
        // scroll/bind hot path (see refreshOnlineSnapshot()). Bounds-checked
        // fallback (false) in case a row binds before the snapshot's first fill.
        boolean online = pos < onlineSnapshot.length && onlineSnapshot[pos];
        h.onlineDot.setVisibility(online ? View.VISIBLE : View.GONE);

        h.itemView.setOnClickListener(v -> listener.onShareToContact(contact));
    }

    @Override
    public void onViewRecycled(@NonNull ContactVH h) {
        super.onViewRecycled(h);
        // FIX (lifecycle-aware cancel): stop an in-flight request for a row that
        // just scrolled off screen, same as FollowConnectionsActivity's UserListAdapter.
        FollowAvatarBinder.cancel(h.itemView.getContext(), h.ivAvatar);
    }

    @Override public int getItemCount() { return contacts.size(); }

    /** Reused AvatarSource view over `contacts`, for FollowAvatarBinder.prefetch() — see class doc. */
    public FollowAvatarBinder.AvatarSource avatarSource() {
        return avatarSourceView;
    }

    static class ContactVH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        TextView        tvName;
        View            onlineDot;

        ContactVH(View v) {
            super(v);
            ivAvatar  = v.findViewById(R.id.iv_share_contact_avatar);
            tvName    = v.findViewById(R.id.tv_share_contact_name);
            onlineDot = v.findViewById(R.id.online_dot);
        }
    }
}
