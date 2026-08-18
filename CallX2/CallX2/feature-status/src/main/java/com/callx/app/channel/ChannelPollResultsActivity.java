package com.callx.app.channel;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.status.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.firebase.database.*;
import java.util.*;

/**
 * ChannelPollResultsActivity — full poll results screen with anonymous mode (v5).
 *
 * v5 additions:
 *   ✓ NEW: Anonymous voting mode — when poll.pollAnonymous == true, voter names/icons
 *     are NOT shown; only the total vote count per option is displayed
 *   ✓ Anonymous mode notice banner: "This poll is anonymous. Voter identities are hidden."
 *   ✓ Existing: option % bars, voter avatars (non-anonymous), total vote count, expiry
 *   ✓ Multi-select badge on multi-select polls
 *   ✓ "You voted for: X" indicator for the current user's vote
 *   ✓ Live real-time updates via Firebase ValueEventListener
 */
public class ChannelPollResultsActivity extends AppCompatActivity {
    public static final String EXTRA_POLL_QUESTION = "pollQuestion";
    public static final String EXTRA_POLL_OPTIONS = "pollOptions";
    public static final String EXTRA_POLL_MULTI = "pollMulti";
    public static final String EXTRA_POLL_EXPIRES = "pollExpires";
    public static final String EXTRA_IS_ADMIN = "isAdmin";


    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_POST_ID      = "postId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";
    public static final String EXTRA_IS_ANONYMOUS = "isAnonymous";

    private String channelId, postId;
    private boolean isAnonymous = false;
    private String myUid;

    private DatabaseReference pollRef;
    private ValueEventListener pollListener;

    private ResultsAdapter adapter;
    private final List<OptionResult> results = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_poll_results);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        postId      = getIntent().getStringExtra(EXTRA_POST_ID);
        isAnonymous = getIntent().getBooleanExtra(EXTRA_IS_ANONYMOUS, false);
        if (channelId == null || postId == null) { finish(); return; }

        myUid = FirebaseUtils.getMyUid();

        Toolbar toolbar = findViewById(R.id.toolbar_poll_results);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Poll results");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // ── Anonymous mode banner ─────────────────────────────────────
        View anonymousBanner = findViewById(R.id.layout_anonymous_banner);
        if (anonymousBanner != null)
            anonymousBanner.setVisibility(isAnonymous ? View.VISIBLE : View.GONE);

        // ── Multi-select badge ────────────────────────────────────────
        View multiSelectBadge = findViewById(R.id.badge_multi_select);
        if (multiSelectBadge != null) {
            boolean isMulti = getIntent().getBooleanExtra("isMultiSelect", false);
            multiSelectBadge.setVisibility(isMulti ? View.VISIBLE : View.GONE);
        }

        // RecyclerView
        RecyclerView rv = findViewById(R.id.rv_poll_results);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ResultsAdapter();
        rv.setAdapter(adapter);

        pollRef = FirebaseUtils.db()
            .getReference("channelPosts").child(channelId).child(postId);

        startListening();
    }

    // ── Firebase listener ─────────────────────────────────────────────────

    private void startListening() {
        pollListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                results.clear();

                // Load poll options
                List<String> options = new ArrayList<>();
                DataSnapshot optSnap = snap.child("pollOptions");
                for (DataSnapshot o : optSnap.getChildren()) {
                    Object v = o.getValue(); options.add(v != null ? v.toString() : "");
                }

                // Tally votes per option
                Map<Integer, Integer>       voteCounts     = new LinkedHashMap<>();
                Map<Integer, List<String>>  voterNames     = new LinkedHashMap<>();
                String pollQuestion  = getStrOrDefault(snap, "pollQuestion",  "Poll");
                boolean multiSelect  = Boolean.TRUE.equals(snap.child("pollMultiSelect").getValue(Boolean.class));
                boolean anonymous    = Boolean.TRUE.equals(snap.child("pollAnonymous").getValue(Boolean.class));
                // Override local flag with Firebase value in case activity was launched without it
                isAnonymous = anonymous;

                for (int i = 0; i < options.size(); i++) {
                    voteCounts.put(i, 0);
                    voterNames.put(i, new ArrayList<>());
                }

                DataSnapshot votesSnap = snap.child("pollVotes");
                for (DataSnapshot voterSnap : votesSnap.getChildren()) {
                    // Format: pollVotes/{uid}/choices/{index} = true
                    DataSnapshot choices = voterSnap.child("choices");
                    if (choices.getChildrenCount() > 0) {
                        for (DataSnapshot choiceSnap : choices.getChildren()) {
                            try {
                                int idx = Integer.parseInt(choiceSnap.getKey() != null ? choiceSnap.getKey() : "-1");
                                if (voteCounts.containsKey(idx)) {
                                    voteCounts.put(idx, voteCounts.get(idx) + 1);
                                    if (!anonymous) {
                                        Object name = voterSnap.child("voterName").getValue();
                                        if (name != null) voterNames.get(idx).add(name.toString());
                                    }
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    } else {
                        // Legacy format: pollVotes/{uid} = optionIndex int
                        Object choiceObj = voterSnap.getValue();
                        if (choiceObj instanceof Number) {
                            int idx = ((Number) choiceObj).intValue();
                            if (voteCounts.containsKey(idx)) {
                                voteCounts.put(idx, voteCounts.get(idx) + 1);
                                if (!anonymous) {
                                    Object name = voterSnap.child("voterName").getValue();
                                    if (name != null) voterNames.get(idx).add(name.toString());
                                }
                            }
                        }
                    }
                }

                // Total votes
                int total = 0;
                for (int c : voteCounts.values()) total += c;

                // Find current user's vote
                Set<Integer> myVotes = new HashSet<>();
                if (myUid != null) {
                    DataSnapshot myVoteSnap = votesSnap.child(myUid);
                    DataSnapshot myCh = myVoteSnap.child("choices");
                    if (myCh.getChildrenCount() > 0) {
                        for (DataSnapshot cs : myCh.getChildren()) {
                            try { myVotes.add(Integer.parseInt(cs.getKey() != null ? cs.getKey() : "-1")); }
                            catch (NumberFormatException ignored) {}
                        }
                    } else {
                        Object v = myVoteSnap.getValue();
                        if (v instanceof Number) myVotes.add(((Number) v).intValue());
                    }
                }

                // Build OptionResult list
                for (int i = 0; i < options.size(); i++) {
                    OptionResult r = new OptionResult();
                    r.option      = options.get(i);
                    r.votes       = voteCounts.getOrDefault(i, 0);
                    r.percent     = total > 0 ? (r.votes * 100f / total) : 0f;
                    r.voters      = voterNames.getOrDefault(i, new ArrayList<>());
                    r.isMyVote    = myVotes.contains(i);
                    r.isAnonymous = anonymous;
                    results.add(r);
                }

                // Update total label
                TextView tvTotal = findViewById(R.id.tv_poll_total_votes);
                if (tvTotal != null) tvTotal.setText(total + " vote" + (total != 1 ? "s" : ""));

                // Update question label
                TextView tvQuestion = findViewById(R.id.tv_poll_question);
                if (tvQuestion != null) tvQuestion.setText(pollQuestion);

                // Update anonymous banner visibility (in case isAnonymous changed)
                View banner = findViewById(R.id.layout_anonymous_banner);
                if (banner != null) banner.setVisibility(anonymous ? View.VISIBLE : View.GONE);

                adapter.setData(results, anonymous);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        pollRef.addValueEventListener(pollListener);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (pollRef != null && pollListener != null) pollRef.removeEventListener(pollListener);
    }

    // ── ResultsAdapter ────────────────────────────────────────────────────

    static class ResultsAdapter extends RecyclerView.Adapter<ResultsAdapter.VH> {
        private final List<OptionResult> data = new ArrayList<>();
        private boolean anonymous = false;

        void setData(List<OptionResult> d, boolean anon) {
            data.clear(); data.addAll(d); anonymous = anon; notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_poll_result_option, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            OptionResult r = data.get(pos);
            if (h.tvOption  != null) h.tvOption.setText(r.option);
            if (h.tvPercent != null) h.tvPercent.setText(String.format("%.0f%%", r.percent));
            if (h.tvVotes   != null) h.tvVotes.setText(r.votes + " vote" + (r.votes != 1 ? "s" : ""));
            if (h.progressBar != null) h.progressBar.setProgress((int) r.percent);

            // My vote indicator
            if (h.ivMyVote  != null) h.ivMyVote.setVisibility(r.isMyVote ? View.VISIBLE : View.GONE);

            // ── Anonymous mode: hide voter names ─────────────────────────
            if (h.tvVoters != null) {
                if (anonymous || r.isAnonymous || r.voters.isEmpty()) {
                    h.tvVoters.setVisibility(View.GONE);
                } else {
                    String names = String.join(", ", r.voters.subList(0, Math.min(3, r.voters.size())));
                    if (r.voters.size() > 3) names += " + " + (r.voters.size() - 3) + " more";
                    h.tvVoters.setText(names);
                    h.tvVoters.setVisibility(View.VISIBLE);
                }
            }
        }

        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvOption, tvPercent, tvVotes, tvVoters;
            LinearProgressIndicator progressBar;
            ImageView ivMyVote;
            VH(View v) {
                super(v);
                tvOption    = v.findViewById(R.id.tv_poll_option_text);
                tvPercent   = v.findViewById(R.id.tv_poll_option_percent);
                tvVotes     = v.findViewById(R.id.tv_poll_option_votes);
                tvVoters    = v.findViewById(R.id.tv_poll_option_voters);
                progressBar = v.findViewById(R.id.progress_poll_option);
                ivMyVote    = v.findViewById(R.id.iv_poll_my_vote);
            }
        }
    }

    static class OptionResult {
        String option;
        int    votes;
        float  percent;
        List<String> voters;
        boolean isMyVote;
        boolean isAnonymous;
    }

    private String getStrOrDefault(DataSnapshot snap, String key, String def) {
        Object v = snap.child(key).getValue(); return v != null ? v.toString() : def;
    }
}
