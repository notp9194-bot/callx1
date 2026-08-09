package com.callx.app.comments;

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
 * GIF-aware EditText for the reel comment box — lets the user open Google
 * Keyboard's (Gboard) built-in GIF/sticker tray and send a GIF straight
 * from there, exactly like GifAwareEditText already does for chat. No
 * separate GIF picker/API is added here on purpose: this only wires up
 * Android's standard commitContent InputConnection extension so whichever
 * GIF search the user's own keyboard provides can deliver content into
 * this field.
 *
 * Kept as its own small class (instead of depending on feature-chat's
 * GifAwareEditText) so feature-reels doesn't need a new module dependency
 * — the commitContent plumbing is only ~15 lines.
 */
public class GifAwareCommentEditText extends AppCompatEditText
        implements InputConnectionCompat.OnCommitContentListener {

    public interface GifReceivedListener {
        void onGifReceived(InputContentInfoCompat contentInfo);
    }

    private GifReceivedListener gifListener;

    public GifAwareCommentEditText(Context context) {
        super(context);
    }

    public GifAwareCommentEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GifAwareCommentEditText(Context context, AttributeSet attrs, int defStyleAttr) {
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
        // Tells Gboard this field accepts inline GIF content, which is
        // what makes the keyboard show its GIF search tab for this field.
        EditorInfoCompat.setContentMimeTypes(editorInfo,
                new String[]{"image/gif", "image/webp", "image/*"});
        return InputConnectionCompat.createWrapper(ic, editorInfo, this);
    }
}
