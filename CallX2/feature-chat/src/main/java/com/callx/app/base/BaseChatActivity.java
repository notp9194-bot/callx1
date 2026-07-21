package com.callx.app.base;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.models.Message;

/**
 * BaseChatActivity - Refactored v16: Common logic for ChatActivity and GroupChatActivity.
 */
public abstract class BaseChatActivity extends AppCompatActivity {

    protected ActivityChatBinding binding;
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        
        setupBaseToolbar();
        setupBaseListeners();
    }

    private void setupBaseToolbar() {
        binding.btnBack.setOnClickListener(v -> handleBackPress());
    }

    private void setupBaseListeners() {
        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                handleSendButtonState(!s.toString().trim().isEmpty());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    protected void handleSendButtonState(boolean enabled) {
        if (binding.btnSend != null) {
            binding.btnSend.setEnabled(enabled);
            binding.btnSend.setAlpha(enabled ? 1.0f : 0.5f);
        }
    }

    protected void handleKeyboardVisibility() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            View view = getCurrentFocus();
            if (view == null) view = new View(this);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    protected void handleBackPress() {
        if (onBackPressedInternal()) {
            return;
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        handleBackPress();
    }

    /**
     * @return true if back press was handled by child class
     */
    protected abstract boolean onBackPressedInternal();

    protected abstract void setupToolbar();
    protected abstract String getChatId();
    protected abstract ChatType getChatType();
    
    protected void scrollToBottom() {
        if (binding.rvMessages != null && binding.rvMessages.getAdapter() != null) {
            int count = binding.rvMessages.getAdapter().getItemCount();
            if (count > 0) {
                binding.rvMessages.scrollToPosition(count - 1);
            }
        }
    }

    protected void showPermissionRationale(String permission) {
        // Implementation based on common patterns found in CallX2
        com.google.android.material.snackbar.Snackbar.make(binding.getRoot(), 
            "Permission required: " + permission, 
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
    }

    protected void loadChatWallpaper(String chatId) {
        // Shared logic for wallpaper loading
    }
}
