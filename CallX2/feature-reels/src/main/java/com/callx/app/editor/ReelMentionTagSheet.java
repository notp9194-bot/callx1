package com.callx.app.editor;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ReelMentionTagSheet — Instagram-style "Tag People" bottom sheet for the Reel editor.
 *
 * Flow:
 *  1. Opens from the "Tag" tool button in ReelEditorActivity.
 *  2. Searches users from Firebase "contacts" + name prefix match.
 *  3. Tap a user → selected chip appears, callback fires to add draggable overlay on video.
 *  4. Tap "Done" → sheet dismisses; all tagged users committed.
 *
 * Callback: {@link OnTaggedUsersChanged#onTagged(ReelMentionUserAdapter.MentionUser)} is
 * fired on each toggle so the editor can immediately add/remove the name-tag overlay.
 */
public class ReelMentionTagSheet extends BottomSheetDialogFragment {

    public interface OnTaggedUsersChanged {
        /** Called when a user is toggled (added or removed). */
        void onToggle(ReelMentionUserAdapter.MentionUser user, boolean added);
    }

    private OnTaggedUsersChanged callback;
    private List<String>         preTaggedUids = new ArrayList<>();

    private EditText    etSearch;
    private ProgressBar progress;
    private RecyclerView rvUsers;
    private TextView    tvEmpty, btnDone;
    private HorizontalScrollView hsvChips;
    private LinearLayout         llChips;

    private ReelMentionUserAdapter adapter;

    /** uid → MentionUser for currently tagged people (ordered). */
    private final Map<String, ReelMentionUserAdapter.MentionUser> tagged = new LinkedHashMap<>();

    private final List<ReelMentionUserAdapter.MentionUser> allUsers      = new ArrayList<>();
    private final List<ReelMentionUserAdapter.MentionUser> displayedUsers = new ArrayList<>();

    private String myUid = "";

    // ── Factory ───────────────────────────────────────────────────────────

    public static ReelMentionTagSheet newInstance() { return new ReelMentionTagSheet(); }

    public ReelMentionTagSheet setCallback(OnTaggedUsersChanged cb) {
        this.callback = cb; return this;
    }

    public ReelMentionTagSheet setPreTaggedUids(List<String> uids) {
        if (uids != null) preTaggedUids = uids;
        return this;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.ReelMediaSheetTheme);
        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception ignored) {}
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inf.inflate(R.layout.sheet_reel_mention_tag, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindViews(view);
        setupAdapter();
        setupSearch();
        expandSheet();
        loadUsers();
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private void bindViews(View root) {
        etSearch  = root.findViewById(R.id.et_mention_search);
        progress  = root.findViewById(R.id.progress_mention_search);
        rvUsers   = root.findViewById(R.id.rv_mention_users);
        tvEmpty   = root.findViewById(R.id.tv_mention_empty);
        btnDone   = root.findViewById(R.id.btn_mention_done);
        hsvChips  = root.findViewById(R.id.hsv_tagged_chips);
        llChips   = root.findViewById(R.id.ll_tag_chips);

        btnDone.setOnClickListener(v -> dismissAllowingStateLoss());
    }

    private void setupAdapter() {
        adapter = new ReelMentionUserAdapter(user -> toggleUser(user), true);
        rvUsers.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvUsers.setAdapter(adapter);
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                filterList(s.toString().toLowerCase().trim());
            }
        });
    }

    private void expandSheet() {
        View parent = (View) requireView().getParent();
        if (parent != null) {
            BottomSheetBehavior<View> bsb = BottomSheetBehavior.from(parent);
            bsb.setState(BottomSheetBehavior.STATE_EXPANDED);
            bsb.setSkipCollapsed(true);
        }
    }

    // ── Load users from Firebase contacts ─────────────────────────────────

    private void loadUsers() {
        if (myUid.isEmpty()) { showEmpty(); return; }
        progress.setVisibility(View.VISIBLE);

        FirebaseUtils.getContactsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded()) return;
                    List<String> uids = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) uids.add(s.getKey());
                    if (uids.isEmpty()) { progress.setVisibility(View.GONE); showEmpty(); return; }
                    fetchUserProfiles(uids);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isAdded()) return;
                    progress.setVisibility(View.GONE); showEmpty();
                }
            });
    }

    private void fetchUserProfiles(List<String> uids) {
        final int total = uids.size();
        final int[] done = {0};
        for (String uid : uids) {
            if (uid.equals(myUid)) { done[0]++; checkDone(done, total); continue; }
            FirebaseUtils.getUserRef(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        if (!isAdded()) return;
                        String name  = str(s, "name");
                        String uname = str(s, "username");
                        String photo = str(s, "profileImageUrl");
                        if (photo.isEmpty()) photo = str(s, "photoUrl");
                        if (!name.isEmpty() || !uname.isEmpty()) {
                            allUsers.add(new ReelMentionUserAdapter.MentionUser(uid, name, uname, photo));
                        }
                        done[0]++;
                        checkDone(done, total);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        done[0]++;
                        checkDone(done, total);
                    }
                });
        }
    }

    private void checkDone(int[] done, int total) {
        if (done[0] < total || !isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            progress.setVisibility(View.GONE);
            filterList(etSearch.getText() != null ? etSearch.getText().toString().toLowerCase().trim() : "");
            // Pre-select any pre-tagged UIDs
            for (String uid : preTaggedUids) {
                for (ReelMentionUserAdapter.MentionUser u : allUsers) {
                    if (u.uid.equals(uid)) { tagged.put(uid, u); break; }
                }
            }
            refreshChips();
            adapter.setSelected(tagged.keySet());
        });
    }

    private void filterList(String query) {
        displayedUsers.clear();
        for (ReelMentionUserAdapter.MentionUser u : allUsers) {
            if (query.isEmpty()
                || u.displayName.toLowerCase().contains(query)
                || u.username.toLowerCase().contains(query)) {
                displayedUsers.add(u);
            }
        }
        adapter.setItems(displayedUsers);
        if (displayedUsers.isEmpty() && progress.getVisibility() != View.VISIBLE) showEmpty();
        else { tvEmpty.setVisibility(View.GONE); rvUsers.setVisibility(View.VISIBLE); }
    }

    private void toggleUser(ReelMentionUserAdapter.MentionUser user) {
        boolean wasTagged = tagged.containsKey(user.uid);
        if (wasTagged) {
            tagged.remove(user.uid);
        } else {
            if (tagged.size() >= 10) {
                android.widget.Toast.makeText(getContext(),
                    "Maximum 10 people can be tagged", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            tagged.put(user.uid, user);
        }
        adapter.setSelected(tagged.keySet());
        refreshChips();
        if (callback != null) callback.onToggle(user, !wasTagged);
    }

    private void refreshChips() {
        if (llChips == null) return;
        llChips.removeAllViews();
        hsvChips.setVisibility(tagged.isEmpty() ? View.GONE : View.VISIBLE);
        int dp = (int) getResources().getDisplayMetrics().density;
        for (ReelMentionUserAdapter.MentionUser u : tagged.values()) {
            TextView chip = new TextView(requireContext());
            chip.setText("@" + (u.username.isEmpty() ? u.displayName : u.username) + " ✕");
            chip.setTextColor(0xFFFFFFFF);
            chip.setTextSize(12);
            chip.setBackground(
                androidx.core.content.ContextCompat.getDrawable(
                    requireContext(), R.drawable.bg_mention_tag_chip));
            chip.setPadding(16 * dp, 6 * dp, 16 * dp, 6 * dp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(8 * dp);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> toggleUser(u));
            llChips.addView(chip);
        }
    }

    private void showEmpty() {
        rvUsers.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.VISIBLE);
    }

    private String str(DataSnapshot s, String key) {
        String v = s.child(key).getValue(String.class);
        return v != null ? v : "";
    }
}
