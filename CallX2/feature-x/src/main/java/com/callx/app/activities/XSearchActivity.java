package com.callx.app.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.adapters.XTweetAdapter;
import com.callx.app.models.XTweet;
import com.callx.app.models.XUser;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

public class XSearchActivity extends AppCompatActivity {

    private XTweetAdapter tweetAdapter;
    private LinearLayout llUserResults;
    private String myUid;
    private boolean dmMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_search);

        myUid  = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        dmMode = getIntent().getBooleanExtra("dm_mode", false);

        findViewById(R.id.btn_x_search_back).setOnClickListener(v -> finish());

        RecyclerView rvTweets = findViewById(R.id.rv_x_search);
        llUserResults = findViewById(R.id.ll_x_user_results);

        tweetAdapter = new XTweetAdapter(this, new XTweetAdapter.OnTweetActionListener() {
            @Override public void onLike(XTweet t, boolean l) {
                XFirebaseUtils.tweetLikesRef(t.id).child(myUid).setValue(l ? true : null);
                XFirebaseUtils.tweetRef(t.id).child("likeCount")
                    .setValue(l ? t.likeCount + 1 : Math.max(0, t.likeCount - 1));
            }
            @Override public void onRetweet(XTweet t, boolean r) {
                XFirebaseUtils.tweetRetweetsRef(t.id).child(myUid).setValue(r ? true : null);
                XFirebaseUtils.tweetRef(t.id).child("retweetCount")
                    .setValue(r ? t.retweetCount + 1 : Math.max(0, t.retweetCount - 1));
            }
            @Override public void onReply(XTweet t) {
                startActivity(new Intent(XSearchActivity.this, XComposeActivity.class)
                    .putExtra("reply_to_id", t.id).putExtra("reply_to_handle", t.authorHandle));
            }
            @Override public void onQuote(XTweet t) {
                startActivity(new Intent(XSearchActivity.this, XComposeActivity.class)
                    .putExtra("quote_tweet_id", t.id));
            }
            @Override public void onBookmark(XTweet t) {
                boolean bkd = t.isBookmarkedBy(myUid);
                XFirebaseUtils.userBookmarksRef(myUid).child(t.id).setValue(bkd ? null : true);
                Toast.makeText(XSearchActivity.this,
                    bkd ? "Removed" : "Saved to Bookmarks", Toast.LENGTH_SHORT).show();
            }
            @Override public void onShare(XTweet t) {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, t.text + " — via CallX");
                startActivity(Intent.createChooser(i, "Share"));
            }
            @Override public void onMore(XTweet t, View a) {
                PopupMenu m = new PopupMenu(XSearchActivity.this, a);
                m.getMenu().add(0, 1, 0, "Copy link");
                m.setOnMenuItemClickListener(item -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link",
                        "https://callx.app/x/tweet/" + t.id));
                    Toast.makeText(XSearchActivity.this, "Link copied", Toast.LENGTH_SHORT).show();
                    return true;
                });
                m.show();
            }
        });
        rvTweets.setLayoutManager(new LinearLayoutManager(this));
        rvTweets.setAdapter(tweetAdapter);

        EditText et = findViewById(R.id.et_x_search_query);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                String q = s.toString().trim();
                if (q.length() >= 2) {
                    searchUsers(q);
                    if (!dmMode) searchTweets(q);
                } else {
                    if (llUserResults != null) llUserResults.removeAllViews();
                    tweetAdapter.setTweets(new ArrayList<>());
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        et.requestFocus();
    }

    private void searchTweets(String q) {
        XFirebaseUtils.tweetsRef()
            .orderByChild("text").startAt(q).endAt(q + "").limitToFirst(20)
            .get().addOnSuccessListener(snap -> {
                List<XTweet> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    XTweet t = ds.getValue(XTweet.class);
                    if (t != null && !t.isDeleted) { t.id = ds.getKey(); list.add(t); }
                }
                tweetAdapter.setTweets(list);
            });
    }

    private void searchUsers(String q) {
        if (llUserResults == null) return;
        String qLow = q.toLowerCase(java.util.Locale.US);
        XFirebaseUtils.root_x_users()
            .orderByChild("handle").startAt(qLow).endAt(qLow + "").limitToFirst(10)
            .get().addOnSuccessListener(snap -> {
                llUserResults.removeAllViews();
                for (DataSnapshot ds : snap.getChildren()) {
                    XUser u = ds.getValue(XUser.class);
                    if (u == null) continue;
                    u.uid = ds.getKey();
                    View row = getLayoutInflater().inflate(R.layout.item_x_user_row, llUserResults, false);
                    ImageView iv = row.findViewById(R.id.iv_x_user_avatar);
                    TextView tvName   = row.findViewById(R.id.tv_x_user_name);
                    TextView tvHandle = row.findViewById(R.id.tv_x_user_handle);
                    Glide.with(this).load(
                        (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl
                    ).circleCrop()
                        .placeholder(R.drawable.ic_person).into(iv);
                    tvName.setText(u.name);
                    tvHandle.setText("@" + u.handle);
                    String uid = u.uid;
                    row.setOnClickListener(v -> {
                        if (dmMode) {
                            // Start DM with this user
                            String convId = XFirebaseUtils.dmConversationId(myUid, uid);
                            startActivity(new Intent(this, XDMConversationActivity.class)
                                .putExtra("conversation_id", convId)
                                .putExtra("other_uid", uid)
                                .putExtra("other_name", u.name)
                                .putExtra("other_handle", u.handle)
                                .putExtra("other_photo", u.photoUrl)
                                .putExtra("other_thumb", u.thumbUrl != null ? u.thumbUrl : ""));
                        } else {
                            XProfileSheet.showProfile(getSupportFragmentManager(), uid);
                        }
                    });
                    llUserResults.addView(row);
                }
            });
    }
}
