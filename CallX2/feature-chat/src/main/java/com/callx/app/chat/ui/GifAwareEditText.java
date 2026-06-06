package com.callx.app.chat.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

/**
 * GIF-aware EditText — Google Keyboard (Gboard) se GIF/sticker support karta hai.
 * InputConnectionCompat.OnCommitContentListener implement karta hai taaki
 * keyboard ka commitContent() callback handle ho sake.
 */
public class GifAwareEditText extends AppCompatEditText {

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

    @Override
    public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
        final InputConnection ic = super.onCreateInputConnection(editorInfo);

        // Keyboard ko batao ki hum GIF / image accept karte hain
        EditorInfoCompat.setContentMimeTypes(editorInfo,
                new String[]{"image/gif", "image/webp", "image/*"});

        return InputConnectionCompat.createWrapper(this, ic, editorInfo,
                (contentInfo, flags, opts) -> {
                    if (gifListener != null) {
                        gifListener.onGifReceived(contentInfo);
                    }
                    return true;
                });
    }
}
