package com.callx.app.bottomsheet;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.status.R;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.*;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * StatusSeenByBottomSheet v26 — FIX: Added search bar to filter viewers.
 * Also shows reaction breakdown summary and proper avatars.
 */
public class StatusSeenByBottomSheet extends BottomSheetDialogFragment {
    private static final String ARG_OWNER_UID  = "ownerUid";
    private static final String ARG_STATUS_ID  = "statusId";
    private static final String ARG_STATUS_OBJ = "statusJson";

    private StatusItem statusItem;
    private final List<ViewerRow> allViewers   = new ArrayList<>();
    private final List<ViewerRow> filteredList = new ArrayList<>();
    private ViewerAdapter adapter;

    static class ViewerRow {
        String uid, name, avatarUrl, reaction, seenAt;
    }

    public static StatusSeenByBottomSheet newInstance(String ownerUid, String statusId, StatusItem item) {
        StatusSeenByBottomSheet f = new StatusSeenByBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_OWNER_UID, ownerUid);
        args.putString(ARG_STATUS_ID, statusId);
        f.setArguments(args);
        f.statusItem = item;
        return f;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        LinearLayout root = new LinearLayout(getContext()); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(32));

        // Handle
        View handle = new View(getContext());
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(48), dp(4));
        hlp.gravity = android.view.Gravity.CENTER_HORIZONTAL; hlp.setMargins(0,0,0,dp(12));
        handle.setLayoutParams(hlp); handle.setBackgroundColor(0x33000000); root.addView(handle);

        // Title row
        LinearLayout titleRow = new LinearLayout(getContext()); titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView tvTitle = tv("👁 Seen By", 17, true);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(tvTitle);
        TextView tvTotal = tv("0 viewers", 13, false); tvTotal.setTextColor(0xFF888888);
        titleRow.addView(tvTotal); root.addView(titleRow);

        // Reaction summary
        TextView tvReactionSummary = tv("", 13, false); tvReactionSummary.setPadding(0,dp(4),0,dp(12));
        tvReactionSummary.setTextColor(0xFF888888); root.addView(tvReactionSummary);

        // FIX: Search bar — was missing
        LinearLayout.LayoutParams etlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etlp.setMargins(0, 0, 0, dp(8));
        EditText etSearch = new EditText(getContext()); etSearch.setHint("🔍 Search viewers…");
        etSearch.setSingleLine(true); etSearch.setLayoutParams(etlp);
        root.addView(etSearch);

        ProgressBar pb = new ProgressBar(getContext());
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pblp.gravity = android.view.Gravity.CENTER_HORIZONTAL; pb.setLayoutParams(pblp); root.addView(pb);

        RecyclerView rv = new RecyclerView(getContext());
        LinearLayout.LayoutParams rvlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(400));
        rv.setLayoutParams(rvlp); rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ViewerAdapter(filteredList); rv.setAdapter(adapter); root.addView(rv);

        TextView tvEmpty = tv("No viewers yet", 14, false);
        tvEmpty.setGravity(android.view.Gravity.CENTER); tvEmpty.setTextColor(0xFF888888);
        tvEmpty.setPadding(0, dp(40), 0, dp(40)); tvEmpty.setVisibility(View.GONE); root.addView(tvEmpty);

        // FIX: Search filter logic
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {
                filterViewers(s.toString().toLowerCase());
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Load viewers from Firebase
        if (statusItem != null && statusItem.seenBy != null) {
            pb.setVisibility(View.GONE);
            buildViewerRows(statusItem.seenBy, statusItem.reactions, tvTotal, tvReactionSummary, tvEmpty, rv);
        } else {
            String ownerUid = getArguments() != null ? getArguments().getString(ARG_OWNER_UID) : null;
            String statusId = getArguments() != null ? getArguments().getString(ARG_STATUS_ID) : null;
            if (ownerUid != null && statusId != null) {
                FirebaseUtils.getStatusRef().child(ownerUid).child(statusId)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot snap) {
                            pb.setVisibility(View.GONE);
                            StatusItem item = snap.getValue(StatusItem.class);
                            if (item != null) {
                                buildViewerRows(item.seenBy, item.reactions, tvTotal, tvReactionSummary, tvEmpty, rv);
                            }
                        }
                        @Override public void onCancelled(DatabaseError e) { pb.setVisibility(View.GONE); }
                    });
            } else { pb.setVisibility(View.GONE); }
        }

        return root;
    }

    private void buildViewerRows(Map<String,Object> seenBy, Map<String,String> reactions,
                                  TextView tvTotal, TextView tvSummary, TextView tvEmpty, RecyclerView rv) {
        if (seenBy == null || seenBy.isEmpty()) { tvEmpty.setVisibility(View.VISIBLE); return; }
        tvTotal.setText(seenBy.size() + " viewer" + (seenBy.size() != 1 ? "s" : ""));
        // Build reaction summary
        Map<String,Integer> rBreak = new LinkedHashMap<>();
        if (reactions != null) for (String e : reactions.values()) rBreak.merge(e, 1, Integer::sum);
        if (!rBreak.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String,Integer> e : rBreak.entrySet()) sb.append(e.getKey()).append(" ").append(e.getValue()).append("  ");
            tvSummary.setText(sb.toString().trim());
        }
        // Resolve viewer UIDs
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        allViewers.clear();
        int[] remaining = {seenBy.size()};
        for (String uid : seenBy.keySet()) {
            Object val = seenBy.get(uid);
            long ts = 0;
            if (val instanceof Long) ts = (Long) val;
            else if (val instanceof Map) { Object t = ((Map<?,?>)val).get("t"); if (t instanceof Long) ts = (Long)t; }
            final long seenTs = ts;
            final String reaction = (reactions != null) ? reactions.get(uid) : null;
            FirebaseUtils.db().getReference("users").child(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    ViewerRow row = new ViewerRow();
                    row.uid      = uid;
                    row.name     = snap.child("name").getValue(String.class);
                    if (row.name == null) row.name = "User";
                    row.avatarUrl = snap.child("photoUrl").getValue(String.class);
                    row.reaction  = reaction;
                    row.seenAt    = seenTs > 0 ? "Seen at " + sdf.format(new Date(seenTs)) : "Seen";
                    allViewers.add(row);
                    if (--remaining[0] <= 0) {
                        allViewers.sort((a2,b2) -> a2.name.compareToIgnoreCase(b2.name));
                        filteredList.clear(); filteredList.addAll(allViewers);
                        if (getActivity() != null) getActivity().runOnUiThread(() -> {
                            adapter.notifyDataSetChanged();
                            tvEmpty.setVisibility(filteredList.isEmpty() ? View.VISIBLE : View.GONE);
                            rv.setVisibility(filteredList.isEmpty() ? View.GONE : View.VISIBLE);
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) { if (--remaining[0] <= 0) { filteredList.addAll(allViewers); if (getActivity()!=null) getActivity().runOnUiThread(() -> adapter.notifyDataSetChanged()); } }
            });
        }
    }

    private void filterViewers(String query) {
        filteredList.clear();
        if (query.isEmpty()) { filteredList.addAll(allViewers); return; }
        for (ViewerRow r : allViewers) {
            if (r.name != null && r.name.toLowerCase().contains(query)) filteredList.add(r);
        }
    }

    class ViewerAdapter extends RecyclerView.Adapter<ViewerAdapter.VH> {
        final List<ViewerRow> list;
        ViewerAdapter(List<ViewerRow> l){ list=l; }
        @Override public int getItemCount(){ return list.size(); }
        @Override public VH onCreateViewHolder(ViewGroup p, int t){
            LinearLayout row = new LinearLayout(p.getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL); row.setPadding(0,dp(10),0,dp(10));
            return new VH(row);
        }
        @Override public void onBindViewHolder(VH h, int pos){
            ViewerRow r = list.get(pos); LinearLayout row = (LinearLayout) h.itemView; row.removeAllViews();
            de.hdodenhof.circleimageview.CircleImageView av = new de.hdodenhof.circleimageview.CircleImageView(row.getContext());
            LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(dp(44),dp(44)); av.setLayoutParams(avlp);
            if (r.avatarUrl != null && !r.avatarUrl.isEmpty()) Glide.with(row.getContext()).load(r.avatarUrl).circleCrop().into(av);
            else av.setBackgroundColor(0xFF888888);
            row.addView(av);
            LinearLayout info = new LinearLayout(row.getContext()); info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            ilp.setMarginStart(dp(12)); info.setLayoutParams(ilp);
            info.addView(tv(r.name, 14, true)); info.addView(tvSmall(r.seenAt));
            row.addView(info);
            if (r.reaction != null && !r.reaction.isEmpty()) row.addView(tv(r.reaction, 22, false));
        }
        class VH extends RecyclerView.ViewHolder{ VH(View v){super(v);} }
    }

    private TextView tv(String t, int sz, boolean bold) {
        TextView v = new TextView(getContext()); v.setText(t); v.setTextSize(sz);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD); return v;
    }
    private TextView tvSmall(String t) {
        TextView v = new TextView(getContext()); v.setText(t); v.setTextSize(12);
        v.setTextColor(0xFF888888); return v;
    }
    private int dp(int v){ return Math.round(v * getResources().getDisplayMetrics().density); }
}
