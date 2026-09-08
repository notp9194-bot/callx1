package com.callx.app.social;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.followers.FollowAvatarBinder;
import com.callx.app.reels.R;
import com.callx.app.models.User;
import com.callx.app.utils.Constants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

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
 * {@code Set<String>} uid lookup me store hota hai — onBindViewHolder sirf ek
 * hashset-contains check karta hai.
 *
 * ★ ULTRA-OPTIMIZED (prefetch source): avatarSource() pehle onScrolled() ke HAR
 * single frame pe ek naya anonymous AvatarSource object allocate karta tha — fast
 * scroll me ye sainkdon short-lived allocations/sec ban jaate (GC churn). Ab ek
 * hi instance banake final field me cache kiya hai, reused hota hai.
 *
 * ★ NEW (multi-select + search): grid ab Instagram/WhatsApp-style multi-select
 * support karta hai — tap ek contact ko check/uncheck karta hai (immediate send
 * nahi karta), checked contacts blue checkmark badge + dark scrim dikhate hain
 * (see item_reel_share_contact_grid.xml). Selection state {@link #selected}
 * (LinkedHashMap, insertion-order preserved for a stable "Send separately"
 * order) is fully owned by THIS adapter; ReelShareSheetFragment only listens
 * via {@link OnSelectionChangedListener} to show/hide its message box + Send
 * buttons and read the final list back via {@link #getSelectedContacts()}.
 *
 * A parallel {@link #masterContacts} (full list, same reference the fragment
 * fills in loadContacts()) vs {@link #displayed} (post-{@link #filter}
 * subset actually shown) split lets the search box narrow the grid without
 * ever touching the fragment's underlying contact list or the online-status
 * snapshot, which stays keyed by uid so it's correct against either list.
 */
public class ReelContactShareAdapter
        extends RecyclerView.Adapter<ReelContactShareAdapter.ContactVH> {

    /** Fired on every check/uncheck so the fragment can update its send bar. */
    public interface OnSelectionChangedListener {
        void onSelectionChanged(List<User> selected);
    }

    private final List<User> masterContacts; // full, unfiltered — same reference fragment owns
    private final List<User> displayed = new ArrayList<>(); // what's actually bound/shown

    private OnSelectionChangedListener selectionListener;
    private String currentQuery = "";

    // uid -> User, insertion order = tap order (drives "Send separately" order)
    private final LinkedHashMap<String, User> selected = new LinkedHashMap<>();

    // Precomputed online snapshot, keyed by uid (not position) so it stays
    // correct across filter()'s changing subset — see refreshOnlineSnapshot().
    private Set<String> onlineUids = new HashSet<>();

    // Reused across every onScrolled() frame instead of allocating a fresh
    // anonymous AvatarSource each time — see class doc. Reads from `displayed`
    // since that's what's actually laid out on screen.
    private final FollowAvatarBinder.AvatarSource avatarSourceView =
        new FollowAvatarBinder.AvatarSource() {
            @Override public String photo(int index) {
                User u = displayed.get(index);
                return (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
            }
            @Override public long avatarVersion(int index) { return displayed.get(index).avatarVersion; }
            @Override public int size() { return displayed.size(); }
        };

    public ReelContactShareAdapter(List<User> contacts) {
        this.masterContacts = contacts;
        this.displayed.addAll(contacts);
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionListener = listener;
    }

    /**
     * Recompute the online/offline snapshot for every contact currently in
     * `masterContacts`, against a SINGLE {@code now} timestamp shared by the
     * whole batch (one syscall instead of one per row). Call this once,
     * right after `masterContacts` is filled/replaced — e.g. loadContacts()'s
     * onDataChange, before filter() — never from onBindViewHolder.
     */
    public void refreshOnlineSnapshot() {
        Set<String> uids = new HashSet<>();
        long now = System.currentTimeMillis();
        for (User u : masterContacts) {
            if (u.uid == null) continue;
            Long lastSeen = u.lastSeen;
            if (lastSeen != null && (now - lastSeen) < Constants.ONLINE_WINDOW_MS) uids.add(u.uid);
        }
        onlineUids = uids;
    }

    /**
     * Narrows `displayed` to entries of `masterContacts` whose name contains
     * {@code query} (case-insensitive); empty/null query shows everything.
     * Selection state is untouched by filtering — a contact checked before a
     * search still counts once the search box is cleared again.
     */
    public void filter(String query) {
        currentQuery = query != null ? query : "";
        String q = currentQuery.trim().toLowerCase(Locale.getDefault());
        displayed.clear();
        if (q.isEmpty()) {
            displayed.addAll(masterContacts);
        } else {
            for (User u : masterContacts) {
                if (u.name != null && u.name.toLowerCase(Locale.getDefault()).contains(q)) {
                    displayed.add(u);
                }
            }
        }
        notifyDataSetChanged();
    }

    /** Re-applies the current search query — call after masterContacts is refilled. */
    public void refreshDisplayed() {
        filter(currentQuery);
    }

    public List<User> getSelectedContacts() {
        return new ArrayList<>(selected.values());
    }

    public void clearSelection() {
        if (selected.isEmpty()) return;
        selected.clear();
        notifyDataSetChanged();
        if (selectionListener != null) selectionListener.onSelectionChanged(getSelectedContacts());
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
        User contact = displayed.get(pos);
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

        boolean isSelected = contact.uid != null && selected.containsKey(contact.uid);
        h.selectionScrim.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        h.selectionCheck.setVisibility(isSelected ? View.VISIBLE : View.GONE);

        // Online dot and the selection checkmark share the same bottom-end
        // corner — a selected contact shows the checkmark instead, never both.
        boolean online = contact.uid != null && onlineUids.contains(contact.uid);
        h.onlineDot.setVisibility((!isSelected && online) ? View.VISIBLE : View.GONE);

        // Same gradient/seen/hidden story ring HomeFragment's feed post avatar
        // and Stories tray already use — see StoryRingApplier.
        com.callx.app.utils.StoryRingApplier.applyWithClick(h.itemView.getContext(), h.ivStoryRing, contact.uid);

        h.itemView.setOnClickListener(v -> {
            if (contact.uid == null) return;
            if (selected.containsKey(contact.uid)) {
                selected.remove(contact.uid);
            } else {
                selected.put(contact.uid, contact);
            }
            notifyItemChanged(h.getBindingAdapterPosition());
            if (selectionListener != null) selectionListener.onSelectionChanged(getSelectedContacts());
        });
    }

    @Override
    public void onViewRecycled(@NonNull ContactVH h) {
        super.onViewRecycled(h);
        // FIX (lifecycle-aware cancel): stop an in-flight request for a row that
        // just scrolled off screen, same as FollowConnectionsActivity's UserListAdapter.
        FollowAvatarBinder.cancel(h.itemView.getContext(), h.ivAvatar);
    }

    @Override public int getItemCount() { return displayed.size(); }

    /** Reused AvatarSource view over `displayed`, for FollowAvatarBinder.prefetch() — see class doc. */
    public FollowAvatarBinder.AvatarSource avatarSource() {
        return avatarSourceView;
    }

    static class ContactVH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        ImageView       ivStoryRing;
        TextView        tvName;
        View            onlineDot;
        View            selectionScrim;
        ImageView       selectionCheck;

        ContactVH(View v) {
            super(v);
            ivAvatar       = v.findViewById(R.id.iv_share_contact_avatar);
            ivStoryRing    = v.findViewById(R.id.iv_story_ring);
            tvName         = v.findViewById(R.id.tv_share_contact_name);
            onlineDot      = v.findViewById(R.id.online_dot);
            selectionScrim = v.findViewById(R.id.view_selection_scrim);
            selectionCheck = v.findViewById(R.id.iv_selection_check);
        }
    }
}
