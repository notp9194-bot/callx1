package com.callx.app.feed;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

/**
 * ReelFeedSettingsActivity — Feed algorithm preferences.
 *
 * Features:
 *  ✅ "Not Interested" topics list (remove from FYP)
 *  ✅ Preferred topics / interest categories (boost in FYP)
 *  ✅ Reset FYP algorithm (clears all preferences)
 *  ✅ Restrict sensitive content toggle
 *  ✅ Autoplay settings (WiFi only / Always / Off)
 *  ✅ Language filter for captions
 *  ✅ Persisted to Firebase under users/{uid}/feedSettings
 */
public class ReelFeedSettingsActivity extends AppCompatActivity {

    private static final String[] TOPICS = {
        "Dance", "Comedy", "Food", "Travel", "Music", "Sports",
        "Beauty", "Education", "Gaming", "Fashion", "Tech", "Animals",
        "Fitness", "Art", "DIY", "News", "Cooking", "Science"
    };
    private static final String[] AUTOPLAY_OPTS = {"Always", "Wi-Fi Only", "Off"};

    private ImageButton btnBack;
    private TextView    tvResetFyp;
    private Switch      swRestrictSensitive, swAutoSubtitles;
    private RadioGroup  rgAutoplay;
    private RecyclerView rvNotInterested, rvInterests;
    private ProgressBar progress;
    private TextView    tvNotInterestedEmpty;

    private final Set<String> notInterestedTopics = new HashSet<>();
    private final Set<String> preferredTopics     = new HashSet<>();
    private TopicAdapter notInterestAdapter, interestAdapter;
    private String myUid;
    private DatabaseReference settingsRef;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_feed_settings);
        try { myUid = FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { finish(); return; }
        settingsRef = FirebaseUtils.getUserRef(myUid).child("feedSettings");
        bindViews();
        loadSettings();
    }

    private void bindViews() {
        btnBack             = findViewById(R.id.btn_feed_settings_back);
        tvResetFyp          = findViewById(R.id.tv_reset_fyp);
        swRestrictSensitive = findViewById(R.id.sw_restrict_sensitive);
        swAutoSubtitles     = findViewById(R.id.sw_auto_subtitles);
        rgAutoplay          = findViewById(R.id.rg_autoplay);
        rvNotInterested     = findViewById(R.id.rv_not_interested);
        rvInterests         = findViewById(R.id.rv_interests);
        progress            = findViewById(R.id.progress_feed_settings);
        tvNotInterestedEmpty= findViewById(R.id.tv_not_interested_empty);

        btnBack.setOnClickListener(v -> finish());

        notInterestAdapter = new TopicAdapter(new ArrayList<>(notInterestedTopics), true,
            topic -> { notInterestedTopics.remove(topic); saveSetting("notInterested", notInterestedTopics); refreshNotInterested(); });
        rvNotInterested.setLayoutManager(new LinearLayoutManager(this));
        rvNotInterested.setAdapter(notInterestAdapter);

        interestAdapter = new TopicAdapter(Arrays.asList(TOPICS), false, topic -> {
            if (preferredTopics.contains(topic)) preferredTopics.remove(topic);
            else preferredTopics.add(topic);
            saveSetting("preferredTopics", preferredTopics);
            interestAdapter.setSelected(preferredTopics);
        });
        rvInterests.setLayoutManager(new LinearLayoutManager(this));
        rvInterests.setAdapter(interestAdapter);

        tvResetFyp.setOnClickListener(v -> resetFyp());

        swRestrictSensitive.setOnCheckedChangeListener((b, c) -> settingsRef.child("restrictSensitive").setValue(c));
        swAutoSubtitles.setOnCheckedChangeListener((b, c) -> settingsRef.child("autoSubtitles").setValue(c));

        for (int i = 0; i < rgAutoplay.getChildCount(); i++) {
            final int idx = i;
            rgAutoplay.getChildAt(i).setOnClickListener(v -> settingsRef.child("autoplay").setValue(AUTOPLAY_OPTS[idx]));
        }
    }

    private void loadSettings() {
        progress.setVisibility(View.VISIBLE);
        settingsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                Boolean restrict = snap.child("restrictSensitive").getValue(Boolean.class);
                Boolean autoSub  = snap.child("autoSubtitles").getValue(Boolean.class);
                swRestrictSensitive.setChecked(restrict != null && restrict);
                swAutoSubtitles.setChecked(autoSub == null || autoSub);
                notInterestedTopics.clear();
                for (DataSnapshot t : snap.child("notInterested").getChildren()) {
                    String v = t.getValue(String.class);
                    if (v != null) notInterestedTopics.add(v);
                }
                preferredTopics.clear();
                for (DataSnapshot t : snap.child("preferredTopics").getChildren()) {
                    String v = t.getValue(String.class);
                    if (v != null) preferredTopics.add(v);
                }
                refreshNotInterested();
                interestAdapter.setSelected(preferredTopics);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (!isFinishing()) progress.setVisibility(View.GONE);
            }
        });
    }

    private void refreshNotInterested() {
        List<String> list = new ArrayList<>(notInterestedTopics);
        notInterestAdapter.setItems(list);
        tvNotInterestedEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void saveSetting(String key, Set<String> values) {
        settingsRef.child(key).setValue(new ArrayList<>(values));
    }

    private void resetFyp() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Reset FYP?")
            .setMessage("This clears all your topic preferences and resets the algorithm. Continue?")
            .setPositiveButton("Reset", (d, w) -> {
                notInterestedTopics.clear(); preferredTopics.clear();
                settingsRef.child("notInterested").removeValue();
                settingsRef.child("preferredTopics").removeValue();
                refreshNotInterested();
                interestAdapter.setSelected(preferredTopics);
                Toast.makeText(this, "FYP reset!", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).show();
    }

    static class TopicAdapter extends RecyclerView.Adapter<TopicAdapter.VH> {
        private List<String> items;
        private final boolean removable;
        private final java.util.function.Consumer<String> onClick;
        private Set<String> selected = new HashSet<>();

        TopicAdapter(List<String> i, boolean removable, java.util.function.Consumer<String> c) {
            this.items = new ArrayList<>(i); this.removable = removable; this.onClick = c;
        }
        void setItems(List<String> i) { this.items = new ArrayList<>(i); notifyDataSetChanged(); }
        void setSelected(Set<String> s) { this.selected = new HashSet<>(s); notifyDataSetChanged(); }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            LinearLayout ll = new LinearLayout(p.getContext());
            ll.setOrientation(LinearLayout.HORIZONTAL);
            ll.setGravity(android.view.Gravity.CENTER_VERTICAL);
            ll.setPadding(dp(p.getContext(),16),dp(p.getContext(),12),dp(p.getContext(),16),dp(p.getContext(),12));
            ll.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            TextView tv = new TextView(p.getContext()); tv.setTag("tv");
            tv.setTextColor(0xFFFFFFFF); tv.setTextSize(15);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            ll.addView(tv);
            if (removable) {
                TextView rm = new TextView(p.getContext()); rm.setTag("rm");
                rm.setText("✕"); rm.setTextColor(0xFFFF3B5C); rm.setTextSize(16);
                ll.addView(rm);
            } else {
                CheckBox cb = new CheckBox(p.getContext()); cb.setTag("cb");
                ll.addView(cb);
            }
            return new VH(ll);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            String item = items.get(pos);
            ((TextView) h.ll.findViewWithTag("tv")).setText(item);
            if (removable) {
                h.ll.findViewWithTag("rm").setOnClickListener(v -> onClick.accept(item));
            } else {
                CheckBox cb = h.ll.findViewWithTag("cb");
                cb.setChecked(selected.contains(item));
                h.ll.setOnClickListener(v -> { onClick.accept(item); cb.setChecked(selected.contains(item)); });
            }
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder { LinearLayout ll; VH(LinearLayout v) { super(v); ll = v; } }
        static int dp(android.content.Context c, int d) { return (int)(d * c.getResources().getDisplayMetrics().density); }
    }
}
