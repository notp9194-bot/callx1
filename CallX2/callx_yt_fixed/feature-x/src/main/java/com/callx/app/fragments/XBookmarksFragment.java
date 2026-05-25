package com.callx.app.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.callx.app.activities.*;
import com.callx.app.adapters.XTweetAdapter;
import com.callx.app.models.*;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

public class XBookmarksFragment extends Fragment implements XTweetAdapter.OnTweetActionListener {

    private RecyclerView rv;
    private SwipeRefreshLayout swipe;
    private XTweetAdapter adapter;
    private ValueEventListener bkListener;
    private String myUid;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_x_bookmarks, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        rv    = view.findViewById(R.id.rv_x_bookmarks);
        swipe = view.findViewById(R.id.swipe_x_bookmarks);
        adapter = new XTweetAdapter(requireContext(), this);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);
        swipe.setOnRefreshListener(this::loadBookmarks);
        loadBookmarks();
    }

    private void loadBookmarks() {
        if (swipe != null) swipe.setRefreshing(true);
        bkListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<String> ids = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) ids.add(ds.getKey());
                if (ids.isEmpty()) { adapter.setTweets(new ArrayList<>()); if (swipe != null) swipe.setRefreshing(false); return; }
                List<XTweet> list = new ArrayList<>();
                long[] pending = { ids.size() };
                for (String id : ids) {
                    XFirebaseUtils.tweetRef(id).addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot ts) {
                            XTweet t = ts.getValue(XTweet.class);
                            if (t != null && !t.isDeleted) { t.id = ts.getKey(); list.add(t); }
                            pending[0]--;
                            if (pending[0] <= 0) {
                                list.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                                if (!isAdded()) return;
                                adapter.setTweets(new ArrayList<>(list));
                                if (swipe != null) swipe.setRefreshing(false);
                            }
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) { pending[0]--; }
                    });
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { if (swipe != null) swipe.setRefreshing(false); }
        };
        XFirebaseUtils.userBookmarksRef(myUid).addValueEventListener(bkListener);
    }

    @Override public void onLike(XTweet t, boolean l) {
        XFirebaseUtils.tweetLikesRef(t.id).child(myUid).setValue(l ? true : null);
        XFirebaseUtils.tweetRef(t.id).child("likeCount").setValue(l ? t.likeCount+1 : Math.max(0,t.likeCount-1));
    }
    @Override public void onRetweet(XTweet t, boolean r) {
        XFirebaseUtils.tweetRetweetsRef(t.id).child(myUid).setValue(r ? true : null);
        XFirebaseUtils.tweetRef(t.id).child("retweetCount").setValue(r ? t.retweetCount+1 : Math.max(0,t.retweetCount-1));
    }
    @Override public void onReply(XTweet t) {
        startActivity(new Intent(requireContext(), XComposeActivity.class)
            .putExtra("reply_to_id", t.id).putExtra("reply_to_handle", t.authorHandle));
    }
    @Override public void onQuote(XTweet t) {
        startActivity(new Intent(requireContext(), XComposeActivity.class).putExtra("quote_tweet_id", t.id));
    }
    @Override public void onBookmark(XTweet t) {
        XFirebaseUtils.userBookmarksRef(myUid).child(t.id).removeValue();
        adapter.removeTweet(t.id);
        Toast.makeText(requireContext(), "Removed from Bookmarks", Toast.LENGTH_SHORT).show();
    }
    @Override public void onShare(XTweet t) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, t.text + " — via CallX");
        startActivity(Intent.createChooser(i, "Share"));
    }
    @Override public void onMore(XTweet t, View anchor) {
        PopupMenu m = new PopupMenu(requireContext(), anchor);
        m.getMenu().add(0, 1, 0, "Remove bookmark");
        m.getMenu().add(0, 2, 0, "Copy link");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) { onBookmark(t); }
            else {
                ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link","https://callx.app/x/tweet/"+t.id));
                Toast.makeText(requireContext(),"Link copied",Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        m.show();
    }
    @Override public void onDestroyView() {
        super.onDestroyView();
        if (bkListener != null) XFirebaseUtils.userBookmarksRef(myUid).removeEventListener(bkListener);
    }
}
