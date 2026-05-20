package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.callx.app.adapters.MutualFollowerAdapter;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * MutualFollowersActivity
 * Shows a list of mutual followers between the current user and a target user.
 * Receives UIDs via EXTRA_UIDS and loads each user's profile (name + avatar) from Firebase.
 */
public class MutualFollowersActivity extends AppCompatActivity {

    public static final String EXTRA_UIDS        = "mutual_uids";
    public static final String EXTRA_TARGET_NAME = "target_name";

    private RecyclerView      rvList;
    private TextView          tvTitle, tvEmpty;
    private View              progressBar;
    private ImageButton       btnBack;

    private MutualFollowerAdapter adapter;
    private List<User>            userList = new ArrayList<>();
    private ArrayList<String>     uidList  = new ArrayList<>();

    private int loadedCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mutual_followers);

        tvTitle     = findViewById(R.id.tv_title);
        tvEmpty     = findViewById(R.id.tv_empty);
        progressBar = findViewById(R.id.progress_bar);
        rvList      = findViewById(R.id.rv_mutual_followers);
        btnBack     = findViewById(R.id.btn_back);

        String targetName = getIntent().getStringExtra(EXTRA_TARGET_NAME);
        if (targetName != null && !targetName.isEmpty())
            tvTitle.setText("Mutual Followers with " + targetName);
        else
            tvTitle.setText("Mutual Followers");

        ArrayList<String> extras = getIntent().getStringArrayListExtra(EXTRA_UIDS);
        if (extras != null) uidList.addAll(extras);

        btnBack.setOnClickListener(v -> finish());

        adapter = new MutualFollowerAdapter(this, userList);
        rvList.setLayoutManager(new LinearLayoutManager(this));
        rvList.setAdapter(adapter);

        if (uidList.isEmpty()) {
            showEmpty();
        } else {
            progressBar.setVisibility(View.VISIBLE);
            loadUsers();
        }
    }

    private void loadUsers() {
        loadedCount = 0;
        for (String uid : uidList) {
            FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    User u = snap.getValue(User.class);
                    if (u != null) {
                        u.uid = snap.getKey();
                        userList.add(u);
                        adapter.notifyItemInserted(userList.size() - 1);
                    }
                    checkAllLoaded();
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    checkAllLoaded();
                }
            });
        }
    }

    private void checkAllLoaded() {
        loadedCount++;
        if (loadedCount >= uidList.size()) {
            progressBar.setVisibility(View.GONE);
            if (userList.isEmpty()) showEmpty();
        }
    }

    private void showEmpty() {
        progressBar.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.VISIBLE);
        rvList.setVisibility(View.GONE);
    }
}
