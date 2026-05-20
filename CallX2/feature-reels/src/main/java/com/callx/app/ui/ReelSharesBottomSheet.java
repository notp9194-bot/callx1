package com.callx.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.*;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.activities.UserReelsActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.database.*;

import java.util.*;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * ReelSharesBottomSheet
 *
 * Bottom sheet shown when user taps the shares count on a reel.
 * Mirrors ReelLikesBottomSheet structure exactly.
 *
 * Displays:
 *   - "Shares and reposts" header with share count + repost count
 *   - "Shared by" section
 *   - Search bar to filter users
 *   - RecyclerView: avatar + name + "Message" button per user
 *
 * Data source: reelReposts/{reelId}/{uid} = timestamp
 */
public class ReelSharesBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG           = "ReelSharesBottomSheet";
    public static final String ARG_REEL_ID   = "reel_id";
    public static final String ARG_SHARES    = "shares_count";
    public static final String ARG_REPOSTS   = "reposts_count";

    // ── Factory ────────────────────────────────────────────────────────────
    public static ReelSharesBottomSheet newInstance(String reelId, int sharesCount, int repostsCount) {
        ReelSharesBottomSheet f = new ReelSharesBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_REEL_ID,  reelId);
        args.putInt(ARG_SHARES,      sharesCount);
        args.putInt(ARG_REPOSTS,     repostsCount);
        f.setArguments(args);
        return f;
    }

    // ── Fields ─────────────────────────────────────────────────────────────
    private TextView     tvSharesCount, tvRepostsCount;
    private EditText     etSearch;
    private RecyclerView rv;
    private ProgressBar  progressBar;
    private TextView     tvEmpty;

    private SharersAdapter       adapter;
    private final List<UserItem> allItems      = new ArrayList<>();
    private final List<UserItem> filteredItems = new ArrayList<>();

    private String reelId;
    private int    sharesCount, repostsCount;

    // ── Lifecycle ──────────────────────────────────────────────────────────
    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_reel_shares, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        Bundle args = getArguments();
        if (args != null) {
            reelId       = args.getString(ARG_REEL_ID);
            sharesCount  = args.getInt(ARG_SHARES,  0);
            repostsCount = args.getInt(ARG_REPOSTS, 0);
        }

        tvSharesCount  = v.findViewById(R.id.tv_shares_count_header);
        tvRepostsCount = v.findViewById(R.id.tv_reposts_count_header);
        etSearch       = v.findViewById(R.id.et_search);
        rv             = v.findViewById(R.id.rv_sharers);
        progressBar    = v.findViewById(R.id.progress_bar);
        tvEmpty        = v.findViewById(R.id.tv_empty);

        tvSharesCount.setText(formatCount(sharesCount));
        tvRepostsCount.setText(formatCount(repostsCount));

        adapter = new SharersAdapter(filteredItems);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filterList(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        if (reelId != null) loadSharers();
        else showEmpty();
    }

    // ── Data loading ───────────────────────────────────────────────────────
    private void loadSharers() {
        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        // reelReposts/{reelId}/{uid} = timestamp  (long)
        FirebaseUtils.db().getReference("reelReposts").child(reelId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        List<String> uids = new ArrayList<>();
                        for (DataSnapshot child : snap.getChildren()) {
                            if (child.getKey() != null) uids.add(child.getKey());
                        }
                        if (uids.isEmpty()) {
                            if (isAdded()) { progressBar.setVisibility(View.GONE); showEmpty(); }
                            return;
                        }
                        fetchUsers(uids);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (isAdded()) { progressBar.setVisibility(View.GONE); showEmpty(); }
                    }
                });
    }

    private void fetchUsers(List<String> uids) {
        final int total = uids.size();
        final int[] done = {0};
        for (String uid : uids) {
            FirebaseUtils.getUserRef(uid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            String name  = s.child("name").getValue(String.class);
                            String photo = s.child("photoUrl").getValue(String.class);
                            String uid2  = s.getKey();
                            allItems.add(new UserItem(uid2,
                                    name  != null ? name  : "User",
                                    photo != null ? photo : ""));
                            done[0]++;
                            if (done[0] >= total) finishLoad();
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            done[0]++;
                            if (done[0] >= total) finishLoad();
                        }
                    });
        }
    }

    private void finishLoad() {
        if (!isAdded() || getContext() == null) return;
        progressBar.setVisibility(View.GONE);
        allItems.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        filteredItems.clear();
        filteredItems.addAll(allItems);
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void filterList(String query) {
        filteredItems.clear();
        if (query.isEmpty()) {
            filteredItems.addAll(allItems);
        } else {
            String q = query.toLowerCase(Locale.getDefault());
            for (UserItem u : allItems)
                if (u.name.toLowerCase(Locale.getDefault()).contains(q)) filteredItems.add(u);
        }
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showEmpty() {
        tvEmpty.setVisibility(View.VISIBLE);
        rv.setVisibility(View.GONE);
    }

    /** Launch cross-module activity by class name to avoid import dependency. */
    private void launchByClass(String className, String[] keys, String[] values) {
        try {
            Class<?> cls = Class.forName(className);
            Intent i = new Intent(requireContext(), cls);
            for (int x = 0; x < keys.length; x++) i.putExtra(keys[x], values[x]);
            startActivity(i);
        } catch (ClassNotFoundException e) {
            Toast.makeText(requireContext(), "Not available", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    private String formatCount(int n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    // ── Adapter ────────────────────────────────────────────────────────────
    class SharersAdapter extends RecyclerView.Adapter<SharersAdapter.VH> {
        final List<UserItem> data;
        SharersAdapter(List<UserItem> d) { data = d; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_reel_liker, parent, false);  // reuse same item layout
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            UserItem u = data.get(pos);
            h.tvName.setText(u.name);

            if (!u.photo.isEmpty())
                Glide.with(requireContext()).load(u.photo)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person)
                        .into(h.ivAvatar);
            else
                h.ivAvatar.setImageResource(R.drawable.ic_person);

            // Message button → open ChatActivity via reflection
            h.btnMessage.setOnClickListener(v ->
                launchByClass("com.callx.app.activities.ChatActivity",
                        new String[]{"partnerUid", "partnerName", "partnerPhoto"},
                        new String[]{u.uid, u.name, u.photo}));

            // Row tap → open profile
            h.itemView.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(requireContext(), UserReelsActivity.class);
                    i.putExtra(UserReelsActivity.EXTRA_UID,   u.uid);
                    i.putExtra(UserReelsActivity.EXTRA_NAME,  u.name);
                    i.putExtra(UserReelsActivity.EXTRA_PHOTO, u.photo);
                    startActivity(i);
                    dismiss();
                } catch (Exception ignored) {}
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView        tvName;
            Button          btnMessage;
            VH(@NonNull View v) {
                super(v);
                ivAvatar   = v.findViewById(R.id.iv_avatar);
                tvName     = v.findViewById(R.id.tv_name);
                btnMessage = v.findViewById(R.id.btn_message);
            }
        }
    }

    // ── Model ──────────────────────────────────────────────────────────────
    static class UserItem {
        String uid, name, photo;
        UserItem(String uid, String name, String photo) {
            this.uid = uid; this.name = name; this.photo = photo;
        }
    }
}
