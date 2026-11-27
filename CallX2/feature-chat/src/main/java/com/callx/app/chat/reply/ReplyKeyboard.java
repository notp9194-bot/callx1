package com.callx.app.chat.reply;

import android.content.Context;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * v905: opens the keyboard after a swipe-to-reply WITHOUT colliding with the reply bar's layout.
 *
 * <p>Showing the reply bar grows the input capsule (one layout pass); the keyboard then resizes the
 * window (a second, much heavier pass). Doing both in the same frame is what makes the release
 * stutter, so the keyboard request is held back two frames, and skipped entirely when the keyboard
 * is already up (the IPC would be a no-op anyway).</p>
 */
public final class ReplyKeyboard {

    private ReplyKeyboard() {}

    public static void showAfterReplyBar(@NonNull final EditText input) {
        input.postOnAnimation(() -> input.postOnAnimation(() -> {
            if (!input.isAttachedToWindow()) return;
            final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(input);
            if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) return;
            final InputMethodManager imm = (InputMethodManager)
                    input.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }));
    }
}
