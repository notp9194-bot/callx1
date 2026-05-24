package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.XTweet;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * XThreadComposerActivity — Compose a Twitter-style thread.
 * Each entry = one tweet. Drag to reorder. Tap "+" to add more.
 * Tap "Post all" to publish the full thread atomically.
 */
public class XThreadComposerActivity extends AppCompatActivity {

    private static final int MAX_CHARS = 280;

    private RecyclerView rvThread;
    private ThreadEntryAdapter adapter;
    private ProgressBar pbThread;
    private String myUid, myName, myHandle, myPhotoUrl, myThumbUrl;
    private boolean myVerified;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_thread_composer);

        myUid   = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        pbThread = findViewById(R.id.pb_thread);
        rvThread = findViewById(R.id.rv_thread_entries);

        View btnClose = findViewById(R.id.btn_thread_close);
        View btnAdd   = findViewById(R.id.btn_thread_add);
        View btnPost  = findViewById(R.id.btn_thread_post);

        if (btnClose != null) btnClose.setOnClickListener(v -> finish());
        if (btnAdd   != null) btnAdd.setOnClickListener(v -> adapter.addEntry(""));
        if (btnPost  != null) btnPost.setOnClickListener(v -> publishThread());

        adapter = new ThreadEntryAdapter();
        rvThread.setLayoutManager(new LinearLayoutManager(this));
        rvThread.setAdapter(adapter);

        // Drag-to-reorder
        ItemTouchHelper ith = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override public boolean onMove(@androidx.annotation.NonNull RecyclerView rv,
                    @androidx.annotation.NonNull RecyclerView.ViewHolder from,
                    @androidx.annotation.NonNull RecyclerView.ViewHolder to) {
                adapter.swap(from.getAdapterPosition(), to.getAdapterPosition());
                return true;
            }
            @Override public void onSwiped(@androidx.annotation.NonNull RecyclerView.ViewHolder vh, int dir) {}
        });
        ith.attachToRecyclerView(rvThread);

        // Add first entry
        adapter.addEntry("");
        loadMyProfile();
    }

    private void loadMyProfile() {
        if (myUid.isEmpty()) return;
        XFirebaseUtils.xUserRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                com.callx.app.models.XUser u = snap.getValue(com.callx.app.models.XUser.class);
                if (u == null) return;
                myName    = u.name;
                myHandle  = u.handle;
                myPhotoUrl= u.photoUrl;
                myThumbUrl= u.thumbUrl;
                myVerified= u.verified || u.blueVerified;
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void publishThread() {
        List<String> texts = adapter.getTexts();
        // Filter empties
        List<String> filtered = new ArrayList<>();
        for (String t : texts) { String s = t.trim(); if (!s.isEmpty()) filtered.add(s); }
        if (filtered.isEmpty()) {
            Toast.makeText(this, "Write something first", Toast.LENGTH_SHORT).show(); return;
        }
        for (String s : filtered) {
            if (s.length() > MAX_CHARS) {
                Toast.makeText(this, "One post is too long (" + s.length() + " chars)", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (pbThread != null) pbThread.setVisibility(View.VISIBLE);

        String threadRootId = XFirebaseUtils.tweetsRef().push().getKey();
        if (threadRootId == null) return;

        Map<String, Object> updates = new HashMap<>();
        String prevId = null;
        long baseTs = System.currentTimeMillis();

        for (int i = 0; i < filtered.size(); i++) {
            String text = filtered.get(i);
            String tweetId = i == 0 ? threadRootId
                : XFirebaseUtils.tweetsRef().push().getKey();
            if (tweetId == null) continue;

            XTweet tweet = new XTweet();
            tweet.id             = tweetId;
            tweet.authorUid      = myUid;
            tweet.authorName     = myName     != null ? myName     : "User";
            tweet.authorHandle   = myHandle   != null ? myHandle   : myUid;
            tweet.authorPhotoUrl = myPhotoUrl != null ? myPhotoUrl : "";
            tweet.authorThumbUrl = myThumbUrl != null ? myThumbUrl : "";
            tweet.authorVerified = myVerified;
            tweet.text           = text;
            tweet.timestamp      = baseTs + i * 1000L;
            tweet.audience       = "public";
            tweet.isThread       = true;
            tweet.threadId       = threadRootId;
            tweet.threadIndex    = i;
            tweet.isThreadEnd    = (i == filtered.size() - 1);
            if (prevId != null) {
                tweet.replyToTweetId = prevId;
                tweet.replyToHandle  = myHandle;
            }
            // Hashtags
            tweet.hashtags = extractHashtags(text);

            updates.put("/x/tweets/" + tweetId, tweet);
            updates.put("/x/user_tweets/" + myUid + "/" + tweetId, tweet);
            if (i == 0) {
                updates.put("/x/global_feed/" + tweetId, tweet);
                updates.put("/x/user_feeds/" + myUid + "/" + tweetId, tweet);
            }
            if (prevId != null) {
                updates.put("/x/tweet_replies/" + prevId + "/" + tweetId, true);
                updates.put("/x/user_replies/" + myUid + "/" + tweetId, true);
            }
            for (String tag : tweet.hashtags) {
                updates.put("/x/hashtag_feeds/" + tag + "/" + tweetId, true);
            }
            updates.put("/x/users/" + myUid + "/tweetCount",
                com.google.firebase.database.ServerValue.increment(1));
            prevId = tweetId;
        }

        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference().updateChildren(updates)
            .addOnSuccessListener(v -> {
                if (pbThread != null) pbThread.setVisibility(View.GONE);
                // Fan-out root to followers
                fanOut(threadRootId, filtered.get(0));
                Toast.makeText(this, "Thread posted!", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            })
            .addOnFailureListener(e -> {
                if (pbThread != null) pbThread.setVisibility(View.GONE);
                Toast.makeText(this, "Failed, try again", Toast.LENGTH_SHORT).show();
            });
    }

    private void fanOut(String tweetId, String text) {
        XFirebaseUtils.tweetRef(tweetId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                XTweet t = snap.getValue(XTweet.class);
                if (t == null) return;
                t.id = snap.getKey();
                XFirebaseUtils.userFollowersRef(myUid).limitToFirst(500).get()
                    .addOnSuccessListener(fSnap -> {
                        Map<String, Object> upd = new HashMap<>();
                        for (DataSnapshot ds : fSnap.getChildren())
                            upd.put("/x/user_feeds/" + ds.getKey() + "/" + tweetId, t);
                        if (!upd.isEmpty())
                            com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference().updateChildren(upd);
                    });
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private List<String> extractHashtags(String text) {
        List<String> tags = new ArrayList<>();
        if (text == null) return tags;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#(\\w+)").matcher(text);
        while (m.find()) tags.add(m.group(1).toLowerCase(Locale.US));
        return tags;
    }

    // ── Thread entry adapter ──────────────────────────────────────────────────

    class ThreadEntryAdapter extends RecyclerView.Adapter<ThreadEntryAdapter.EntryVH> {
        private final List<String> texts = new ArrayList<>();

        void addEntry(String text) {
            texts.add(text);
            notifyItemInserted(texts.size() - 1);
            rvThread.scrollToPosition(texts.size() - 1);
        }

        void swap(int from, int to) {
            Collections.swap(texts, from, to);
            notifyItemMoved(from, to);
        }

        List<String> getTexts() { return new ArrayList<>(texts); }

        @androidx.annotation.NonNull @Override
        public EntryVH onCreateViewHolder(@androidx.annotation.NonNull ViewGroup p, int t) {
            return new EntryVH(LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_x_thread_entry, p, false));
        }

        @Override public void onBindViewHolder(@androidx.annotation.NonNull EntryVH h, int pos) {
            h.bind(pos);
        }

        @Override public int getItemCount() { return texts.size(); }

        class EntryVH extends RecyclerView.ViewHolder {
            EditText etText;
            TextView tvCount;
            View btnRemove;

            EntryVH(View v) {
                super(v);
                etText   = v.findViewById(R.id.et_x_thread_entry);
                tvCount  = v.findViewById(R.id.tv_x_thread_char_count);
                btnRemove= v.findViewById(R.id.btn_x_thread_remove);
            }

            void bind(int pos) {
                if (etText != null) {
                    etText.setText(texts.get(pos));
                    etText.addTextChangedListener(new TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                        @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                            int p = getAdapterPosition();
                            if (p >= 0 && p < texts.size()) texts.set(p, s.toString());
                            if (tvCount != null) tvCount.setText((MAX_CHARS - s.length()) + "");
                        }
                        @Override public void afterTextChanged(Editable s) {}
                    });
                }
                int remaining = MAX_CHARS - texts.get(pos).length();
                if (tvCount != null) tvCount.setText(String.valueOf(remaining));
                if (btnRemove != null) {
                    btnRemove.setVisibility(texts.size() > 1 ? View.VISIBLE : View.GONE);
                    btnRemove.setOnClickListener(v -> {
                        int p = getAdapterPosition();
                        if (p >= 0 && p < texts.size()) {
                            texts.remove(p);
                            notifyItemRemoved(p);
                            notifyItemRangeChanged(p, texts.size());
                        }
                    });
                }
            }
        }
    }
}
