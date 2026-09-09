package com.callx.app.chatv2;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.LiveData;

import com.google.firebase.auth.FirebaseAuth;

import com.callx.app.db.entity.MessageEntity;
import com.callx.app.repository.ChatRepository;
import com.callx.app.utils.FirebaseUtils;

import java.util.List;

/**
 * Native C++/OpenGL-rendered chat screen — the "Fastest Chat" entry
 * point. Deliberately a brand-new screen: does not touch ChatActivity,
 * MessageBubbleCanvasView or MessagePagingAdapter (existing canvas chat
 * stays exactly as-is). Reuses the existing shared data layer only
 * (ChatRepository / Room / Firebase) per the isolated-module plan.
 *
 * Intent contract mirrors ChatActivity's for drop-in reuse from any
 * existing "open chat" call site: partnerUid, partnerName.
 *
 * Phase 4: wires the surface view's long-press/swipe gestures to actual
 * interactions — a quick-reaction popup (long-press) and a reply signal
 * (horizontal swipe). Both resolve a screen touch to a messageId via
 * nativeHitTestId and look the MessageEntity up from the same list the
 * renderer was just given (see currentMessages), so no extra Firebase/
 * Room read is needed just to know what was tapped.
 */
public class FastChatActivity extends AppCompatActivity {

    public static final String EXTRA_PARTNER_UID = "partnerUid";
    public static final String EXTRA_PARTNER_NAME = "partnerName";

    private static final String[] QUICK_REACTIONS = {"👍", "❤️", "😂", "😮", "😢", "🙏"};

    private FastChatSurfaceView surfaceView;
    private FastChatGLRenderer renderer;
    private LiveData<List<MessageEntity>> messagesLiveData;

    private String chatId;
    private String myUid;
    private volatile List<MessageEntity> currentMessages;
    private PopupWindow reactionPopup;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String partnerUid = getIntent().getStringExtra(EXTRA_PARTNER_UID);
        String partnerName = getIntent().getStringExtra(EXTRA_PARTNER_NAME);

        myUid = FirebaseAuth.getInstance().getUid();
        chatId = (myUid != null && partnerUid != null)
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
        renderer.setContext(getApplicationContext());
        surfaceView = new FastChatSurfaceView(this, renderer);
        surfaceView.setOnMessageLongPressListener(this::showReactionPopup);
        surfaceView.setOnMessageSwipeListener(this::onMessageSwipeReply);
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
                    currentMessages = messages;
                    renderer.submitMessages(messages);
                }
            });
        }
    }

    // ── Phase 4: message interactions ──────────────────────────────────

    @Nullable
    private MessageEntity findMessage(String messageId) {
        List<MessageEntity> messages = currentMessages;
        if (messages == null || messageId == null) return null;
        for (MessageEntity m : messages) {
            if (messageId.equals(m.id)) return m;
        }
        return null;
    }

    /** Long-press → small emoji row anchored at the touch point (view-local
     *  coords from FastChatSurfaceView, converted to screen coords here). */
    private void showReactionPopup(String messageId, float viewX, float viewY) {
        MessageEntity message = findMessage(messageId);
        if (message == null || myUid == null || chatId == null) return;

        dismissReactionPopup();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(8);
        row.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(32, 32, 36));
        bg.setCornerRadius(dp(24));
        row.setBackground(bg);

        for (String emoji : QUICK_REACTIONS) {
            TextView tv = new TextView(this);
            tv.setText(emoji);
            tv.setTextSize(22f);
            int p = dp(6);
            tv.setPadding(p, p, p, p);
            tv.setOnClickListener(v -> {
                FastChatReactionHelper.toggleReaction(
                        FastChatActivity.this, chatId, message.id,
                        message.reactionsJson, emoji, myUid);
                dismissReactionPopup();
            });
            row.addView(tv);
        }

        PopupWindow popup = new PopupWindow(row,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        reactionPopup = popup;

        int[] anchorLoc = new int[2];
        surfaceView.getLocationOnScreen(anchorLoc);
        int screenX = anchorLoc[0] + (int) viewX - dp(90);
        int screenY = anchorLoc[1] + (int) viewY - dp(70);
        popup.showAtLocation(surfaceView, Gravity.NO_GRAVITY,
                Math.max(dp(8), screenX), Math.max(dp(8), screenY));
    }

    private void dismissReactionPopup() {
        if (reactionPopup != null && reactionPopup.isShowing()) {
            reactionPopup.dismiss();
        }
        reactionPopup = null;
    }

    /** Horizontal swipe on a bubble = "reply to this". This screen has no
     *  composer yet (view-only native renderer, see class doc), so for now
     *  this just surfaces the intent — wiring a real reply draft in is a
     *  self-contained follow-up once FastChatActivity grows an input bar. */
    private void onMessageSwipeReply(String messageId) {
        MessageEntity message = findMessage(messageId);
        if (message == null) return;
        String preview = message.text != null && message.text.length() > 40
                ? message.text.substring(0, 40) + "…" : message.text;
        Toast.makeText(this, "Reply to: " + (preview != null ? preview : message.type),
                Toast.LENGTH_SHORT).show();
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
        dismissReactionPopup();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (renderer != null) {
            surfaceView.queueEvent(renderer::destroy);
        }
    }
}
