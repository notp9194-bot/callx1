# CallX2 — Production Chat Integration Guide

## New Files Added

| File | Location | Purpose |
|------|----------|---------|
| `ChatViewModel.java` | `feature-chat/.../viewmodel/` | MVVM ViewModel — lifecycle-safe, no leaks |
| `ChatViewModelFactory.java` | `feature-chat/.../viewmodel/` | Factory to create ChatViewModel |
| `E2EEncryptionManager.java` | `core/.../utils/` | End-to-End Encryption (ECDH + AES-GCM) |
| `SyncWorker.java` | `core/.../workers/` | WorkManager — offline media upload retry |

---

## Step 1: Add dependencies to `build.gradle` (app level)

```gradle
// WorkManager (for SyncWorker)
implementation "androidx.work:work-runtime:2.9.0"

// ViewModel + LiveData
implementation "androidx.lifecycle:lifecycle-viewmodel:2.7.0"
implementation "androidx.lifecycle:lifecycle-livedata:2.7.0"

// Paging 3 (already used, confirm version)
implementation "androidx.paging:paging-runtime:3.2.1"
```

---

## Step 2: Move files to correct packages

```
CallX2/
├── feature-chat/src/main/java/com/callx/app/
│   ├── viewmodel/
│   │   ├── ChatViewModel.java          ← NEW
│   │   └── ChatViewModelFactory.java   ← NEW
│   └── activities/
│       └── ChatActivity.java           ← MODIFY (see Step 3)
│
└── core/src/main/java/com/callx/app/
    ├── utils/
    │   └── E2EEncryptionManager.java   ← NEW
    └── workers/
        └── SyncWorker.java             ← NEW
```

---

## Step 3: Modify ChatActivity.java

### 3a. Add ViewModel field (replace existing Firebase fields at top)

```java
// REMOVE THESE (now in ViewModel):
// private DatabaseReference typingRef;
// private ValueEventListener typingListener;
// private ValueEventListener onlineListener;
// private ValueEventListener blockListener;
// private ValueEventListener pinnedListener;

// ADD THIS:
private ChatViewModel viewModel;
```

### 3b. Replace onCreate() setup

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    binding = ActivityChatBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());

    readIntentExtras(); // Keep existing method

    // NEW: Create ViewModel (survives rotation)
    viewModel = new ViewModelProvider(this,
            new ChatViewModelFactory(getApplication()))
            .get(ChatViewModel.class);

    viewModel.init(chatId, currentUid, partnerUid);

    setupToolbar();
    applyScreenTheme();
    setupPickers();
    recorder = new VoiceRecorder();

    // NEW: Observe LiveData instead of setting up Firebase directly
    observeViewModel();

    setupPagingRecyclerView();
    setupInputBar();
    setupSwipeToReply();
    setupFabBackToLatest();
    setupNetworkMonitor();
    restoreDraft();
}
```

### 3c. Add observeViewModel() method

```java
private void observeViewModel() {
    // Messages — paged from Room DB
    viewModel.getPagedMessages().observe(this, pagingAdapter::submitData);

    // Typing indicator
    viewModel.getTypingStatus().observe(this, typing -> {
        if (typing != null && !typing.isEmpty()) {
            binding.tvTyping.setVisibility(View.VISIBLE);
            binding.tvTyping.setText(typing);
            binding.tvStatus.setVisibility(View.GONE);
        } else {
            binding.tvTyping.setVisibility(View.GONE);
            binding.tvStatus.setVisibility(View.VISIBLE);
        }
    });

    // Online status
    viewModel.getPartnerOnline().observe(this, online -> {
        if (Boolean.TRUE.equals(online)) {
            binding.tvStatus.setText("online");
            binding.tvStatus.setVisibility(View.VISIBLE);
        }
    });

    viewModel.getPartnerLastSeen().observe(this, lastSeen -> {
        if (lastSeen != null && !lastSeen.isEmpty()) {
            binding.tvStatus.setText(lastSeen);
        }
    });

    // Blocked state
    viewModel.getIsBlocked().observe(this, blocked -> {
        isBlocked = Boolean.TRUE.equals(blocked);
        updateInputBarState();
    });

    // Mute
    viewModel.getIsMuted().observe(this, muted -> isMuted = Boolean.TRUE.equals(muted));

    // Pinned message
    viewModel.getPinnedMsgId().observe(this, id -> pinnedMsgId = id);
    viewModel.getPinnedMsgText().observe(this, text -> {
        pinnedMsgText = text;
        updatePinnedBar();
    });

    // Error events
    viewModel.getErrorEvent().observe(this, err -> {
        if (err != null && !err.isEmpty()) {
            Snackbar.make(binding.getRoot(), err, Snackbar.LENGTH_LONG).show();
        }
    });

    // Network
    viewModel.getNetworkStatus().observe(this, online -> {
        updateOfflineBanner(!Boolean.TRUE.equals(online));
        updateSendButtonState(Boolean.TRUE.equals(online));
    });
}
```

### 3d. Replace sendMessage / pushMessage

```java
private void pushMessage(Message msg, String preview) {
    // NEW: Delegate to ViewModel (handles encryption + Firebase + Room)
    viewModel.sendMessage(msg, preview);
    clearReply();
}
```

### 3e. Replace typing TextWatcher

```java
// In setupInputBar():
binding.etMessage.addTextChangedListener(new TextWatcher() {
    @Override public void afterTextChanged(Editable s) {
        if (s.length() > 0) viewModel.onUserTyping();
        else viewModel.onUserStoppedTyping();
        toggleSendVoiceButton(s.length() > 0);
    }
    @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
    @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
});
```

### 3f. Simplify onPause / onDestroy

```java
@Override
protected void onPause() {
    super.onPause();
    // Save draft via ViewModel
    if (binding != null && binding.etMessage != null) {
        viewModel.saveDraft(binding.etMessage.getText().toString());
    }
    // ViewModel handles typing status cleanup
}

@Override
protected void onDestroy() {
    super.onDestroy();
    // ViewModel.onCleared() handles all Firebase listener detachment automatically
    // No manual cleanup needed here!
    if (replyController != null) replyController.release();
    if (connMgr != null && netCallback != null) {
        try { connMgr.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
    }
}
```

### 3g. Update NetworkMonitor to use ViewModel

```java
netCallback = new ConnectivityManager.NetworkCallback() {
    @Override public void onAvailable(Network n) {
        runOnUiThread(() -> {
            viewModel.setNetworkOnline(true);
            // Schedule retry for any pending offline media uploads
            SyncWorker.schedule(getApplicationContext());
        });
    }
    @Override public void onLost(Network n) {
        runOnUiThread(() -> viewModel.setNetworkOnline(false));
    }
};
```

---

## Step 4: Register WorkManager in Application class

```java
// In CallxApp.java → onCreate():
import androidx.work.Configuration;
import androidx.work.WorkManager;

@Override
public void onCreate() {
    super.onCreate();
    // ...existing init...

    // Initialize WorkManager
    WorkManager.initialize(this, new Configuration.Builder().build());

    // Retry any pending media uploads from previous session
    SyncWorker.schedule(this);
}
```

---

## Step 5: Add MessageDao query for SyncWorker

Add this to `MessageDao.java`:

```java
/**
 * Returns messages with a local file path but no CDN URL yet.
 * These are offline sends that need to be uploaded and pushed.
 */
@Query("SELECT * FROM messages WHERE mediaLocalPath IS NOT NULL AND (mediaUrl IS NULL OR mediaUrl = '')")
@WorkerThread
List<MessageEntity> getPendingMediaUploads();

/**
 * Mark a message as deleted (soft delete — keeps in local DB).
 */
@Query("UPDATE messages SET deleted = 1 WHERE id = :msgId")
void markDeleted(String msgId);

/**
 * Get unread messages from a specific sender.
 */
@Query("SELECT * FROM messages WHERE chatId = :chatId AND senderId = :senderUid AND status != 'read'")
List<MessageEntity> getUnreadMessages(String chatId, String senderUid);
```

---

## Step 6: Add Firebase Security Rules for E2E keys

Add to your Firebase Database Rules:

```json
{
  "rules": {
    "e2e_keys": {
      "$uid": {
        ".read": "auth != null",
        ".write": "auth != null && auth.uid === $uid",
        "publicKey": {
          ".read": "auth != null",
          ".write": "auth != null && auth.uid === $uid"
        }
      }
    }
  }
}
```

---

## Security Notes

- **Private keys** never leave the device (stored in SharedPreferences)
- **Public keys** are uploaded to Firebase (safe — only used for key agreement)
- **Shared secret** derived via ECDH — same result for both parties independently
- **AES-256-GCM** provides both confidentiality AND authenticity
- **Unique IV** per message — no IV reuse even if same key used
- Messages with prefix `enc:` are encrypted; without it → plain (backward compat)

---

## E2E Encryption Flow Diagram

```
Alice's Device          Firebase           Bob's Device
      │                     │                   │
      │── upload pubKey_A ──►│                   │
      │                     │◄── upload pubKey_B ─│
      │                     │                   │
      │◄── fetch pubKey_B ──│                   │
      │── ECDH(privKey_A, pubKey_B) = sharedSecret
      │── SHA256(sharedSecret) = AES_key
      │── AES-GCM-encrypt(msg, AES_key) = "enc:..."
      │── push "enc:..." ──►│── deliver ────────►│
      │                     │                   │
      │                     │   ECDH(privKey_B, pubKey_A) = same sharedSecret
      │                     │   SHA256(sharedSecret) = same AES_key
      │                     │   AES-GCM-decrypt("enc:...", AES_key) = msg
```
