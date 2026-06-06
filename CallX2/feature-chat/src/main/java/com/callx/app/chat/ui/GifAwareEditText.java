package com.callx.app.chat.ui;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

/**
 * GIF-aware EditText — Google Keyboard (Gboard) se GIF/sticker support karta hai.
 * androidx.core 1.13+ compatible version.
 * createWrapper(View, IC, EditorInfo, Listener) deprecated ho gaya —
 * ab View khud OnCommitContentListener implement karta hai.
 */
public class GifAwareEditText extends AppCompatEditText
        implements InputConnectionCompat.OnCommitContentListener {

    public interface GifReceivedListener {
        void onGifReceived(InputContentInfoCompat contentInfo);
    }

    private GifReceivedListener gifListener;

    public GifAwareEditText(Context context) {
        super(context);
    }

    public GifAwareEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GifAwareEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** ChatActivity / GroupChatActivity se yeh listener set karo */
    public void setGifReceivedListener(GifReceivedListener listener) {
        this.gifListener = listener;
    }

    // ─── InputConnectionCompat.OnCommitContentListener ───────────────────
    @Override
    public boolean onCommitContent(InputContentInfoCompat inputContentInfo,
                                   int flags, Bundle opts) {
        if (gifListener != null) {
            gifListener.onGifReceived(inputContentInfo);
        }
        return true;
    }

    // ─── InputConnection ─────────────────────────────────────────────────
    @Override
    public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
        final InputConnection ic = super.onCreateInputConnection(editorInfo);

        // Keyboard ko batao ki hum GIF / image accept karte hain
        EditorInfoCompat.setContentMimeTypes(editorInfo,
                new String[]{"image/gif", "image/webp", "image/*"});

        // androidx.core 1.13+ — createWrapper(InputConnection, EditorInfo, View)
        // View must implement OnCommitContentListener (yeh class implement karti hai)
        return InputConnectionCompat.createWrapper(ic, editorInfo, this);
    }
}
