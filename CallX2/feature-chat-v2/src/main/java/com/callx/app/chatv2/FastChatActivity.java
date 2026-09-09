package com.callx.app.chatv2;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.LiveData;

import com.google.firebase.auth.FirebaseAuth;

import com.callx.app.db.entity.MessageEntity;
import com.callx.app.repository.ChatRepository;
import com.callx.app.utils.FirebaseUtils;

/**
 * Native C++/OpenGL-rendered chat screen — the "Fastest Chat" entry
 * point. Deliberately a brand-new screen: does not touch ChatActivity,
 * MessageBubbleCanvasView or MessagePagingAdapter (existing canvas chat
 * stays exactly as-is). Reuses the existing shared data layer only
 * (ChatRepository / Room / Firebase) per the isolated-module plan.
 *
 * Intent contract mirrors ChatActivity's for drop-in reuse from any
 * existing "open chat" call site: partnerUid, partnerName.
 */
public class FastChatActivity extends AppCompatActivity {

    public static final String EXTRA_PARTNER_UID = "partnerUid";
    public static final String EXTRA_PARTNER_NAME = "partnerName";

    private FastChatSurfaceView surfaceView;
    private FastChatGLRenderer renderer;
    private LiveData<java.util.List<MessageEntity>> messagesLiveData;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String partnerUid = getIntent().getStringExtra(EXTRA_PARTNER_UID);
        String partnerName = getIntent().getStringExtra(EXTRA_PARTNER_NAME);

        String myUid = FirebaseAuth.getInstance().getUid();
        String chatId = (myUid != null && partnerUid != null)
                ? FirebaseUtils.getChatId(myUid, partnerUid) : null;

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Toolbar toolbar = new Toolbar(this);
        toolbar.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        toolbar.setTitle((partnerName != null ? partnerName : "Chat") + " ⚡");
        toolbar.setSubtitle("Fastest Chat — native renderer");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        renderer = new FastChatGLRenderer();
        renderer.setMyUid(myUid);
        surfaceView = new FastChatSurfaceView(this, renderer);
        FrameLayout.LayoutParams surfaceParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        surfaceParams.topMargin = dp(56);
        root.addView(surfaceView, surfaceParams);
        root.addView(toolbar);

        if (chatId == null) {
            TextView error = new TextView(this);
            error.setText("Chat not available");
            error.setGravity(android.view.Gravity.CENTER);
            root.addView(error, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        setContentView(root);

        if (chatId != null) {
            messagesLiveData = ChatRepository.getInstance(getApplicationContext()).getMessages(chatId);
            messagesLiveData.observe(this, messages -> {
                if (messages != null) {
                    renderer.submitMessages(messages);
                }
            });
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surfaceView != null) surfaceView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (surfaceView != null) surfaceView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (renderer != null) {
            surfaceView.queueEvent(renderer::destroy);
        }
    }
}
