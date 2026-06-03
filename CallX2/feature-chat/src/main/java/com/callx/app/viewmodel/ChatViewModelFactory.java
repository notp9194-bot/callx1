package com.callx.app.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

/**
 * ChatViewModelFactory — passes constructor args to ChatViewModel.
 *
 * Usage in ChatActivity.onCreate():
 *
 *   ChatViewModelFactory factory = new ChatViewModelFactory(
 *       getApplication(), chatId, partnerUid, partnerName);
 *   viewModel = new ViewModelProvider(this, factory).get(ChatViewModel.class);
 */
public class ChatViewModelFactory implements ViewModelProvider.Factory {

    private final Application app;
    private final String      chatId;
    private final String      partnerUid;
    private final String      partnerName;

    public ChatViewModelFactory(Application app,
                                String chatId,
                                String partnerUid,
                                String partnerName) {
        this.app        = app;
        this.chatId     = chatId;
        this.partnerUid = partnerUid;
        this.partnerName= partnerName;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(ChatViewModel.class)) {
            return (T) new ChatViewModel(app, chatId, partnerUid, partnerName);
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
