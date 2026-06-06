package com.callx.app.chat.ui;

import android.content.Context;
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
 * androidx.core 1.13+ compatible: View khud OnCommitContentListener implement karta hai.
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

    public void setGifReceivedListener(GifReceivedListener listener) {
        this.gifListener = listener;
    }

    @Override
    public boolean onCommitContent(InputContentInfoCompat inputContentInfo,
                                   int flags, Bundle opts) {
        if (gifListener != null) {
            gifListener.onGifReceived(inputContentInfo);
        }
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
        final InputConnection ic = super.onCreateInputConnection(editorInfo);
        EditorInfoCompat.setContentMimeTypes(editorInfo,
                new String[]{"image/gif", "image/webp", "image/*"});
        return InputConnectionCompat.createWrapper(ic, editorInfo, this);
    }
}
