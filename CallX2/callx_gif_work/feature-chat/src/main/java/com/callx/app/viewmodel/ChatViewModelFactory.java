package com.callx.app.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.callx.app.conversation.ChatActivity;

/**
 * ChatViewModelFactory — Provides ChatViewModel to Activity.
 *
 * Usage in ChatActivity.onCreate():
 *
 *   ChatViewModel viewModel = new ViewModelProvider(this,
 *       new ChatViewModelFactory(getApplication()))
 *       .get(ChatViewModel.class);
 *
 *   viewModel.init(chatId, currentUid, partnerUid);
 *
 *   viewModel.getPagedMessages().observe(this, pagingAdapter::submitData);
 *   viewModel.getTypingStatus().observe(this, text -> { ... });
 *   viewModel.getPartnerOnline().observe(this, online -> { ... });
 */
public class ChatViewModelFactory implements ViewModelProvider.Factory {

    private final Application application;

    public ChatViewModelFactory(@NonNull Application application) {
        this.application = application;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(ChatViewModel.class)) {
            //noinspection unchecked
            return (T) new ChatViewModel(application);
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
