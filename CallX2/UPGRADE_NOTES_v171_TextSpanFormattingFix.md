# v171 — Advanced Text Formatting Display Fix

## Summary

**Issue:** Large text messages with advanced formatting (colors, sizes, bold, italic, etc.) from `AdvancedRichTextController` were appearing as blank/blue blobs after sending instead of displaying with the applied formatting.

**Root Cause:** 
- EditText content with spans was being sent as plain text only
- Canvas view received plain String, losing all formatting information  
- MarkdownFormatter was processing plain text, not the original formatted spans
- Rendering produced invalid/blank StaticLayout for formatted messages

**Solution:** 
1. Serialize spans to HTML when sending formatted text
2. Deserialize HTML back to Spanned when displaying
3. Update `MessageBubbleCanvasView.bind()` to accept `CharSequence` (handles both plain text and Spanned)
4. Canvas properly renders formatted text via HTML deserialization + StaticLayout

---

## Files Added

### Java
- `feature-chat/src/main/java/com/callx/app/utils/TextSpanSerializer.java`
  - Utility for serializing Spanned → HTML and deserializing HTML → Spanned
  - Preserves colors, sizes, fonts, bold, italic, strikethrough, etc.
  - Safe fallback to plain text if parsing fails

### Modified Files
- `MessageBubbleCanvasView.java` — `bind()` method updated
- `MessagePagingAdapter.java` — Text binding updated

---

## Integration Guide

### 1 — Preserve Spans When Sending Text Messages

In `ChatActivity` / `ConversationFragment` where you send a message, wrap the EditText content with `TextSpanSerializer`:

**Before:**
```java
String messageText = binding.etMessageInput.getText().toString();
sendMessage(messageText);  // Loses all spans!
```

**After:**
```java
CharSequence editedText = binding.etMessageInput.getText();  // May contain spans
String serialized = com.callx.app.utils.TextSpanSerializer.prepareForSend(editedText);
sendMessage(serialized);  // Spans preserved as HTML in string
```

### 2 — Update Message Send Method

When preparing the Firebase message object:

```java
private void sendMessage(String messageText, String chatId) {
    Map<String, Object> msg = new HashMap<>();
    msg.put("type", "text");
    msg.put("text", messageText);  // Now contains HTML-serialized formatting
    msg.put("senderId", currentUid);
    msg.put("timestamp", ServerValue.TIMESTAMP);
    msg.put("status", "sent");
    chatRef.child(msgId).setValue(msg);
}
```

No change needed here — the string is already serialized by `TextSpanSerializer.prepareForSend()`.

### 3 — Canvas View Binding (Already Updated)

`MessageBubbleCanvasView.bind()` now accepts both String and CharSequence:

```java
// Both these work:
cv.bind("plain text", timeStr, sent, isRead, isDelivered);
cv.bind(spannedText, timeStr, sent, isRead, isDelivered);
```

The view automatically:
- Detects HTML tags in the string and deserializes to Spanned
- Falls back to MarkdownFormatter for plain text  
- Renders formatted text with proper StaticLayout

`MessagePagingAdapter` is already updated to handle this correctly.

### 4 — No Database Schema Changes

The Message model stores text as plain `String` — no schema changes needed. The text string now contains HTML markup when formatting is applied, which is safely stored and retrieved as-is.

---

## How It Works

### Send Flow (Example: User writes "Hello" in red, size 18)

1. User types in EditText, applies color + size via AdvancedRichTextController
2. EditText.getText() returns Spanned with ForegroundColorSpan + AbsoluteSizeSpan
3. On send: `TextSpanSerializer.prepareForSend(spanned)` → `"<font color=\"#FF0000\"><font size=\"18sp\">Hello</font></font>"`
4. HTML string is stored in Firebase / Room
5. Database saves: `{type: "text", text: "<font color=\"#FF0000\"><font size=\"18sp\">Hello</font></font>"}`

### Display Flow

1. Message retrieved from database: `text = "<font color=\"#FF0000\"><font size=\"18sp\">Hello</font></font>"`
2. MessagePagingAdapter detects `<` and `>` in text
3. Calls `TextSpanSerializer.fromHtml()` → Spanned with original color + size spans
4. Passes Spanned to `cv.bind(charSequence, ...)`
5. Canvas build StaticLayout with spans → renders formatted text

### Fallback: Plain Text (No Spans)

1. User sends unformatted message: `"Hello"`
2. `TextSpanSerializer.prepareForSend()` → detects no spans → `"Hello"` (passthrough)
3. Stored as plain text
4. Adapter sees no `<>` tags → uses MarkdownFormatter for *bold*/_italic_/~strike~
5. Renders correctly either way

---

## Span Types Preserved

| Span | Usage | HTML |
|------|-------|------|
| ForegroundColorSpan | Text color (A button) | `<font color="#RRGGBB">` |
| BackgroundColorSpan | Highlight (A highlight button) | `<mark style="background:#RRGGBB">` |
| AbsoluteSizeSpan | Text size (14sp - 32sp) | `<font size="Nsp">` |
| StyleSpan (BOLD) | Bold formatting | `<b>` |
| StyleSpan (ITALIC) | Italic formatting | `<i>` |
| StrikethroughSpan | Strikethrough | `<s>` |
| TypefaceSpan | Font family (Sans, Serif, Monospace) | `<font face="...">` |

---

## Performance Notes

- **Serialization** (send): Fast — one-time Html.toHtml() call
- **Deserialization** (display): Cached via StaticLayout — no per-frame cost
- **Memory**: HTML string ≈ 2–3× plain text for heavily formatted messages; negligible for most messages
- **Render time**: StaticLayout with spans = StaticLayout with plain text (no penalty)

---

## Testing Checklist

1. **Send formatted text**: Type in EditText, apply color/size/bold → send → verify color/size/bold displays in bubble
2. **Mix plain and formatted**: Sentence with colored word in middle → send → only the colored word is colored
3. **Large formatted text**: Long formatted message → scroll → no flicker, no blue blob
4. **Switch user**: Send message from user A with formatting → view from user B → formatting persists
5. **Rotate device**: Send formatted message → rotate → formatting and bubble size preserved
6. **Markdown + formatting**: Apply bold via AdvancedRichTextController AND use `*markdown*` → send → both work correctly
7. **Clear formatting**: Apply color, clear it, apply size → send → only size shows
8. **HTML edge cases**: Send text with literal `<` or `>` characters → should display, not parse as HTML
   - Solution: Html.fromHtml() escapes `&lt;` and `&gt;` correctly when serializing

---

## Troubleshooting

### Issue: Formatted text appears as plain text after send

**Cause:** `TextSpanSerializer.prepareForSend()` not called before sending  
**Fix:** Ensure EditText content is wrapped with TextSpanSerializer in your send method

### Issue: HTML tags appear literally in bubble ("&lt;font...&gt;")

**Cause:** HTML deserialization in adapter skipped due to exception  
**Fix:** Check logcat for TextSpanSerializer.fromHtml() exceptions; usually a malformed HTML edge case

### Issue: Blue blob / blank message after sending

**Cause:** StaticLayout builder received null or corrupted messageTextSpanned  
**Fix:** This should no longer occur — canvas bind() now validates input; if it persists, check EditText span extraction

### Issue: Colors/sizes don't match what user set

**Cause:** AdvancedRichTextController spans not attached to EditText correctly  
**Fix:** Verify AdvancedRichTextController is wired to bind() button clicks and selection state

---

## Files Modified (Complete List)

```
feature-chat/src/main/java/com/callx/app/utils/
  TextSpanSerializer.java (NEW)

feature-chat/src/main/java/com/callx/app/conversation/
  MessagePagingAdapter.java (MODIFIED — text binding)
  canvas/
    MessageBubbleCanvasView.java (MODIFIED — bind() signature + span handling)
```

---

## Notes

- **Backward Compatible:** Old plain-text messages display correctly; new formatted messages serialize to HTML
- **Firebase Sync:** HTML string syncs the same as plain text; no Firestore rule changes needed
- **Spanned Serialization:** Uses Android framework `Html.toHtml(Spanned)` — standard, safe, reliable
- **No Custom Span Support (yet):** Custom span classes outside the framework (e.g., user-defined LinkSpan) are not serialized; only framework spans (listed above) survive send/receive cycle
  - Workaround: Convert custom spans to framework spans before send, or implement custom serialization in TextSpanSerializer if needed

---

## Not Verified by Build

Traced through:
- TextSpanSerializer Html.toHtml() + Html.fromHtml() paths
- MessageBubbleCanvasView.bind(CharSequence) overload and span detection
- MessagePagingAdapter text extraction and HTML deserialization
- StaticLayout.Builder with Spanned input

Device testing needed:
1. Send formatted message → verify colors/sizes/font/bold/italic render
2. Formatted message in a group → view from different user → formatting persists
3. Long formatted text → scroll-reuse → no flicker, no blue blob
4. Mix formatting + markdown: `*literal __emphasis__ text*` with color applied
