package com.callx.app.chat.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GIF-aware EditText — Google Keyboard (Gboard) se GIF/sticker support karta hai.
 * androidx.core 1.13+ compatible: View khud OnCommitContentListener implement karta hai.
 *
 * Auto-continuing list support: agar user "1. kuch text" likh kar Enter dabata hai,
 * to agli line automatically "2. " se start hoti hai (Notes/Keep jaisa behavior).
 * Bullet lines ("- ", "* ", "• ") bhi isi tarah continue hoti hain.
 * Khaali numbered/bullet line pe Enter dabane se list khud khatam ho jaati hai.
 */
public class GifAwareEditText extends AppCompatEditText
        implements InputConnectionCompat.OnCommitContentListener {

    public interface GifReceivedListener {
        void onGifReceived(InputContentInfoCompat contentInfo);
    }

    /**
     * WhatsApp-style "Send as text or .txt file?" prompt for large pastes.
     * Fired instead of performing the paste immediately when the clipboard
     * holds more than PASTE_AS_FILE_THRESHOLD_CHARS characters — the host
     * (ChatActivity) decides what to show and calls insertAsText.run() if
     * the user picks "Send as Text", or handles it as a file attachment
     * itself and simply doesn't call it.
     */
    public interface PasteAsFileListener {
        void onLargePaste(String pastedText, Runnable insertAsText);
    }

    /**
     * Single frame-coalesced text callback for the chat composer.
     *
     * The chat screen used to attach its own TextWatcher while this view
     * attached one watcher for auto-lists and another for auto-capitalization.
     * That made every keystroke walk three watcher callbacks. Keeping the
     * host callback here lets the EditText dispatch all input work from one
     * watcher and at most once per rendered frame.
     */
    public interface TextChangeListener {
        void onTextChanged(CharSequence text, int start, int before, int count);
    }

    private GifReceivedListener gifListener;
    private PasteAsFileListener pasteAsFileListener;
    private TextChangeListener textChangeListener;
    private boolean textChangeDispatchPosted;
    private int pendingTextChangeStart = -1;
    private int pendingTextChangeBefore;
    private int pendingTextChangeCount;

    // WhatsApp/Telegram send this kind of paste as a document instead of a
    // wall of text in the bubble — this is the length past which we ask.
    private static final int PASTE_AS_FILE_THRESHOLD_CHARS = 500;

    // Matches: optional leading indent, a number, '.' or ')', then whitespace, then rest of line
    private static final Pattern NUMBERED_LINE =
            Pattern.compile("^(\\s*)(\\d+)([.)])(\\s+)(.*)$");
    // Matches: optional leading indent, a bullet char, then whitespace, then rest of line
    private static final Pattern BULLET_LINE =
            Pattern.compile("^(\\s*)([-*\u2022])(\\s+)(.*)$");

    private boolean isAutoListUpdating = false;
    private boolean isAutoCapitalizing = false;

    public GifAwareEditText(Context context) {
        super(context);
        setupTextChangeDispatcher();
        setupFormattingToolbar();
    }

    public GifAwareEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        setupTextChangeDispatcher();
        setupFormattingToolbar();
    }

    public GifAwareEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setupTextChangeDispatcher();
        setupFormattingToolbar();
    }

    public void setGifReceivedListener(GifReceivedListener listener) {
        this.gifListener = listener;
    }

    public void setPasteAsFileListener(PasteAsFileListener listener) {
        this.pasteAsFileListener = listener;
    }

    public void setTextChangeListener(TextChangeListener listener) {
        removeCallbacks(textChangeDispatchRunnable);
        textChangeDispatchPosted = false;
        textChangeListener = listener;
    }

    /**
     * Intercepts Paste (both the context-menu item and the floating
     * selection-toolbar item share these same IDs) before the text lands in
     * the field. A large clipboard payload triggers the host's "Send as
     * text or .txt file?" prompt instead of dumping straight into the
     * compose box; anything under the threshold pastes normally as before.
     */
    @Override
    public boolean onTextContextMenuItem(int id) {
        if ((id == android.R.id.paste || id == android.R.id.pasteAsPlainText)
                && pasteAsFileListener != null) {
            String clip = readClipboardText();
            if (clip != null && clip.length() > PASTE_AS_FILE_THRESHOLD_CHARS) {
                final int pasteId = id;
                pasteAsFileListener.onLargePaste(clip, () -> super.onTextContextMenuItem(pasteId));
                return true; // handled — normal paste stays blocked unless insertAsText runs
            }
        }
        return super.onTextContextMenuItem(id);
    }

    private String readClipboardText() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return null;
        android.content.ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence text = clip.getItemAt(0).coerceToText(getContext());
        return text != null ? text.toString() : null;
    }

    private final Runnable textChangeDispatchRunnable = () -> {
        textChangeDispatchPosted = false;
        TextChangeListener listener = textChangeListener;
        if (listener == null) return;
        listener.onTextChanged(getText(), pendingTextChangeStart,
                pendingTextChangeBefore, pendingTextChangeCount);
    };

    private void scheduleTextChangeDispatch(int start, int before, int count) {
        pendingTextChangeStart = start;
        pendingTextChangeBefore = before;
        pendingTextChangeCount = count;
        if (textChangeListener != null && !textChangeDispatchPosted) {
            textChangeDispatchPosted = true;
            postOnAnimation(textChangeDispatchRunnable);
        }
    }

    /**
     * One TextWatcher owns the two small editor behaviours and the host
     * callback. Auto-list/auto-capitalization remain immediate, while the
     * chat screen's heavier UI/presence work is dispatched once per frame.
     */
    private void setupTextChangeDispatcher() {
        addTextChangedListener(new TextWatcher() {
            private int changeStart = -1;
            private int changeBefore;
            private int changeCount;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Edits made by auto-list/auto-capitalization re-enter the
                // watcher. They are part of the same user change and must not
                // replace the original cursor/change metadata.
                if (isAutoListUpdating || isAutoCapitalizing) return;
                changeStart = start;
                changeBefore = before;
                changeCount = count;
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (isAutoListUpdating || isAutoCapitalizing) return;

                applyAutoListContinuation(editable);
                applyAutoCapitalization(editable, changeStart, changeCount);
                scheduleTextChangeDispatch(changeStart, changeBefore, changeCount);
            }
        });
    }

    /**
     * Naye sentence/line ki pehli letter ko auto-capitalize karta hai —
     * text ki shuruaat me, kisi "\n" ke baad, ya ". "/"! "/"? " ke baad.
     * Sirf single-character typing par trigger hota hai (paste/autocomplete
     * jaisi bulk insertions ko chhod diya jata hai).
     */
    private void applyAutoListContinuation(Editable editable) {
        int cursorPos = getSelectionStart();
        if (cursorPos <= 0 || cursorPos > editable.length()) return;
        if (editable.charAt(cursorPos - 1) != '\n') return;

        int newlineIndex = cursorPos - 1;
        int prevLineStart = newlineIndex;
        while (prevLineStart > 0 && editable.charAt(prevLineStart - 1) != '\n') {
            prevLineStart--;
        }
        String prevLine = editable.subSequence(prevLineStart, newlineIndex).toString();
        if (prevLine.isEmpty()) return;

        Matcher numMatch = NUMBERED_LINE.matcher(prevLine);
        Matcher bulletMatch = BULLET_LINE.matcher(prevLine);

        if (numMatch.matches()) {
            String indent = numMatch.group(1);
            String content = numMatch.group(5);
            if (content == null || content.trim().isEmpty()) {
                removeMarkerFromPrevLine(editable, prevLineStart, newlineIndex);
                return;
            }
            int nextNumber;
            try {
                nextNumber = Integer.parseInt(numMatch.group(2)) + 1;
            } catch (NumberFormatException e) {
                return;
            }
            insertMarker(editable, cursorPos,
                    indent + nextNumber + numMatch.group(3) + " ");
        } else if (bulletMatch.matches()) {
            String content = bulletMatch.group(4);
            if (content == null || content.trim().isEmpty()) {
                removeMarkerFromPrevLine(editable, prevLineStart, newlineIndex);
                return;
            }
            insertMarker(editable, cursorPos,
                    bulletMatch.group(1) + bulletMatch.group(2) + " ");
        }
    }

    private void applyAutoCapitalization(Editable editable, int changeStart, int changeCount) {
        if (changeCount != 1) return;

        int pos = changeStart;
        if (pos < 0 || pos >= editable.length()) return;
        char typed = editable.charAt(pos);
        if (!Character.isLowerCase(typed)) return;

        int i = pos - 1;
        while (i >= 0 && (editable.charAt(i) == ' ' || editable.charAt(i) == '\t')) {
            i--;
        }

        boolean shouldCapitalize;
        if (i < 0) {
            shouldCapitalize = true;
        } else {
            char prevNonSpace = editable.charAt(i);
            shouldCapitalize = prevNonSpace == '\n'
                    || prevNonSpace == '.'
                    || prevNonSpace == '!'
                    || prevNonSpace == '?';
        }

        if (shouldCapitalize) {
            isAutoCapitalizing = true;
            editable.replace(pos, pos + 1, String.valueOf(Character.toUpperCase(typed)));
            isAutoCapitalizing = false;
        }
    }

    private void insertMarker(Editable editable, int cursorPos, String marker) {
        isAutoListUpdating = true;
        editable.insert(cursorPos, marker);
        setSelection(cursorPos + marker.length());
        isAutoListUpdating = false;
    }

    private void removeMarkerFromPrevLine(Editable editable, int prevLineStart, int newlineIndex) {
        isAutoListUpdating = true;
        editable.delete(prevLineStart, newlineIndex);
        setSelection(prevLineStart);
        isAutoListUpdating = false;
    }

    // ── Long-press selection → Bold/Italic/Strikethrough toolbar ───────────
    // WhatsApp-style: selecting text and long-pressing shows the usual Cut/
    // Copy/Paste bar PLUS three extra actions that wrap the selection in
    // literal *bold*/_italic_/~strike~ markers (not live-rendered spans —
    // exactly like WhatsApp's own compose box). MarkdownFormatter parses
    // those markers back out on the receiving/rendering side so the actual
    // message bubble shows real bold/italic/struck-through text.
    private static final int ID_FORMAT_BOLD = android.view.View.generateViewId();
    private static final int ID_FORMAT_ITALIC = android.view.View.generateViewId();
    private static final int ID_FORMAT_STRIKE = android.view.View.generateViewId();

    private void setupFormattingToolbar() {
        setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(0, ID_FORMAT_BOLD, 0, "B");
                menu.add(0, ID_FORMAT_ITALIC, 1, "I");
                menu.add(0, ID_FORMAT_STRIKE, 2, "S");
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false; // nothing to update — same three items every time
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int id = item.getItemId();
                if (id == ID_FORMAT_BOLD) {
                    toggleMarkerAroundSelection('*');
                    mode.finish();
                    return true;
                } else if (id == ID_FORMAT_ITALIC) {
                    toggleMarkerAroundSelection('_');
                    mode.finish();
                    return true;
                } else if (id == ID_FORMAT_STRIKE) {
                    toggleMarkerAroundSelection('~');
                    mode.finish();
                    return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) { }
        });
    }

    /**
     * Wraps the current selection in {@code marker} on both sides (e.g.
     * "hello" -> "*hello*" for bold). If the selection is already wrapped
     * in that exact marker, un-wraps it instead — same toggle behaviour as
     * tapping Bold again on already-bold text in WhatsApp.
     */
    private void toggleMarkerAroundSelection(char marker) {
        Editable editable = getText();
        if (editable == null) return;

        int start = getSelectionStart();
        int end = getSelectionEnd();
        if (start < 0 || end < 0 || start == end) return;
        if (start > end) { int t = start; start = end; end = t; }

        String selected = editable.subSequence(start, end).toString();
        String m = String.valueOf(marker);

        if (selected.length() >= 2 && selected.startsWith(m) && selected.endsWith(m)) {
            String inner = selected.substring(1, selected.length() - 1);
            editable.replace(start, end, inner);
            setSelection(start, start + inner.length());
        } else {
            String wrapped = m + selected + m;
            editable.replace(start, end, wrapped);
            setSelection(start, start + wrapped.length());
        }
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
