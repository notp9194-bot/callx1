package com.callx.app.conversation.popup;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.chat.databinding.ActivityMiniChatPopupBinding;
import com.callx.app.conversation.ChatActivity;
import com.callx.app.models.Message;

import java.util.List;

/**
 * Small popup chat window, launched instead of full ChatActivity when a
 * message notification is tapped while the user is on some OTHER screen
 * (not already inside that exact chat). Lets both sides keep chatting
 * right there without leaving whatever they were doing — tap the expand
 * button to jump into the full ChatActivity if they need more (media,
 * search, etc).
 *
 * Uses Theme.CallX.Transparent so it overlays on top of the current
 * screen (dimmed background, centered card) instead of replacing it.
 *
 * Launch with:
 *   Intent i = new Intent(context, MiniChatPopupActivity.class);
 *   i.putExtra(EXTRA_PARTNER_UID, uid);
 *   i.putExtra(EXTRA_PARTNER_NAME, name);
 *   i.putExtra(EXTRA_PARTNER_PHOTO, photoUrl); // optional
 *   context.startActivity(i);
 */
public class MiniChatPopupActivity extends AppCompatActivity implements ChatPopupController.Listener {

    public static final String EXTRA_PARTNER_UID   = "partnerUid";
    public static final String EXTRA_PARTNER_NAME  = "partnerName";
    public static final String EXTRA_PARTNER_PHOTO = "partnerPhoto";

    private ActivityMiniChatPopupBinding binding;
    private ChatPopupController controller;
    private MiniChatPopupAdapter adapter;

    private String partnerUid;
    private String partnerName;
    private String partnerPhoto;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMiniChatPopupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        partnerUid   = getIntent().getStringExtra(EXTRA_PARTNER_UID);
        partnerName  = getIntent().getStringExtra(EXTRA_PARTNER_NAME);
        partnerPhoto = getIntent().getStringExtra(EXTRA_PARTNER_PHOTO);

        if (TextUtils.isEmpty(partnerUid)) {
            finish();
            return;
        }

        binding.tvPopupName.setText(!TextUtils.isEmpty(partnerName) ? partnerName : "Chat");
        if (!TextUtils.isEmpty(partnerPhoto)) {
            Glide.with(this).load(partnerPhoto).into(binding.ivPopupAvatar);
        }

        // Tapping the dimmed background outside the card closes the popup —
        // the root layout itself listens since the card consumes its own
        // touches via the CardView.
        binding.getRoot().setOnClickListener(v -> finish());

        controller = new ChatPopupController(partnerUid, this);
        adapter = new MiniChatPopupAdapter(controller.getCurrentUid());

        binding.rvPopupMessages.setLayoutManager(new LinearLayoutManager(this));
        binding.rvPopupMessages.setAdapter(adapter);

        binding.btnPopupSend.setOnClickListener(v -> sendMessage());
        binding.btnPopupClose.setOnClickListener(v -> finish());
        binding.btnPopupExpand.setOnClickListener(v -> openFullChat());

        controller.start();
    }

    private void sendMessage() {
        String text = binding.etPopupMessage.getText() != null
                ? binding.etPopupMessage.getText().toString() : "";
        if (text.trim().isEmpty()) return;
        controller.sendMessage(text);
        binding.etPopupMessage.setText("");
    }

    private void openFullChat() {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("partnerUid", partnerUid);
        i.putExtra("partnerName", partnerName);
        i.putExtra("partnerPhoto", partnerPhoto);
        startActivity(i);
        finish();
    }

    // ── ChatPopupController.Listener ────────────────────────────────────

    @Override
    public void onMessagesLoaded(List<Message> messages) {
        runOnUiThread(() -> {
            adapter.submitList(messages);
            scrollToBottom();
        });
    }

    @Override
    public void onMessageAdded(Message message) {
        runOnUiThread(() -> {
            adapter.addMessage(message);
            scrollToBottom();
        });
    }

    private void scrollToBottom() {
        int count = binding.rvPopupMessages.getAdapter() != null
                ? binding.rvPopupMessages.getAdapter().getItemCount() : 0;
        if (count > 0) binding.rvPopupMessages.scrollToPosition(count - 1);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (controller != null) controller.stop();
    }
}
