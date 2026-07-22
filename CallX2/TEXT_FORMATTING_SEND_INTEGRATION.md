# Text Formatting Send Flow Integration — v171

## Quick Integration Checklist

For messages with advanced formatting to display correctly, you MUST serialize the EditText content with `TextSpanSerializer` before sending.

---

## Location to Update

**File:** Where you send text messages (likely `ConversationFragment.java`, `ChatActivity.java`, or `ChatMediaController.java`)

**Method:** The method that extracts EditText text and sends it to Firebase

---

## Example: ConversationFragment Send Method

### BEFORE (v170) — Loses formatting:

```java
private void sendTextMessage(String chatId) {
    String messageText = binding.etMessageInput.getText().toString();  // ❌ Loses spans!
    
    // Send to Firebase
    Map<String, Object> msg = new HashMap<>();
    msg.put("type", "text");
    msg.put("text", messageText);
    msg.put("senderId", currentUid);
    msg.put("timestamp", ServerValue.TIMESTAMP);
    
    chatRef.child(chatId).push().setValue(msg);
    
    binding.etMessageInput.setText("");  // Clear input
}
```

### AFTER (v171) — Preserves formatting:

```java
private void sendTextMessage(String chatId) {
    CharSequence editedText = binding.etMessageInput.getText();
    
    // v171: Serialize spans to HTML, preserving advanced formatting
    String messageText = com.callx.app.utils.TextSpanSerializer.prepareForSend(editedText);
    
    // Send to Firebase (no change)
    Map<String, Object> msg = new HashMap<>();
    msg.put("type", "text");
    msg.put("text", messageText);  // ✅ Spans preserved as HTML
    msg.put("senderId", currentUid);
    msg.put("timestamp", ServerValue.TIMESTAMP);
    
    chatRef.child(chatId).push().setValue(msg);
    
    binding.etMessageInput.setText("");  // Clear input
}
```

---

## What TextSpanSerializer.prepareForSend() Does

| Input | Output | Notes |
|-------|--------|-------|
| Plain text: `"Hello"` | `"Hello"` | Passthrough (no spans) |
| Formatted: `[Formatted text with color + size spans]` | `"<font color=\"#FF0000\"><font size=\"18sp\">Hello</font></font>"` | HTML-serialized, preserves all spans |
| HTML-like text: `"I <3 you"` | `"I <3 you"` | Safe — doesn't treat `<>` as markup (only pure Spanned gets serialized) |

---

## One-Line Fix

If you're already have a send method, just wrap the text extraction:

```java
// Before:
String text = binding.etMessageInput.getText().toString();

// After:
String text = com.callx.app.utils.TextSpanSerializer.prepareForSend(
    binding.etMessageInput.getText());
```

---

## How Users Will See It

### Scenario: User writes formatted message

1. User opens chat, types "Hello everyone"
2. Selects "everyone", clicks color picker, chooses red
3. Selects "everyone", clicks size, chooses 18sp
4. Taps send
5. **Before (v170):** Message shows as "Hello everyone" (no red, no size)
6. **After (v171):** Message shows as "Hello **everyone**" (red, larger)

---

## Integration in Different Files

### In ConversationFragment / ChatActivity (Typical)

```java
// Import at top
import com.callx.app.utils.TextSpanSerializer;

// In send method
private void sendMessage() {
    String messageText = TextSpanSerializer.prepareForSend(
        binding.etMessageInput.getText());
    sendToFirebase(messageText);
}
```

### In ChatMediaController (If Text Send Happens There)

```java
public void sendTextMessage(String chatId, CharSequence text) {
    String serialized = TextSpanSerializer.prepareForSend(text);
    // Send serialized to database
}
```

### In Custom Input Controller

```java
public void onSendClicked() {
    CharSequence input = inputEditText.getText();
    String prepared = TextSpanSerializer.prepareForSend(input);
    messageController.send(prepared);
}
```

---

## Testing Your Integration

### Step 1: Apply Formatting
1. Type "TEST MESSAGE"
2. Select "TEST", tap color → pick RED
3. Select "MESSAGE", tap size → pick 24sp
4. Tap send

### Step 2: Verify Display
- Your message should appear with RED "TEST" and larger "MESSAGE"
- In group/received view, formatting should persist

### Step 3: Reload App
- Relaunch app
- Open the same chat
- The formatted message should still show RED and large size (not reverted to plain)

### Step 4: Edge Cases
- Send message with literal `<` or `>` (e.g., "5 < 10") → should display correctly
- Apply formatting to newline-heavy text → should preserve line breaks + formatting
- Combine MarkdownFormatter markdown with applied formatting → both should work

---

## FAQ

**Q: What if I don't update my send method?**  
A: Formatted messages will send as plain text, losing colors/sizes. Display will show "blue blob" until this fix is applied.

**Q: Will old plain-text messages break?**  
A: No. Plain text messages that don't have spans pass through unchanged.

**Q: Does this require database changes?**  
A: No. The text field is still a `String` — it just contains HTML markup when formatted.

**Q: What about group chats?**  
A: Works the same — HTML string syncs via Firebase to all members, deserializes back to Spanned on display.

**Q: Can I apply formatting in Markdown AND AdvancedRichTextController?**  
A: Yes. MarkdownFormatter processes plain-text markdown, and Spanned spans handle explicit formatting. Both coexist.

---

## Troubleshooting

| Problem | Cause | Solution |
|---------|-------|----------|
| Formatted text sends but appears plain | `prepareForSend()` not called | Add TextSpanSerializer wrapper in send method |
| Message shows as blank/"blue blob" | Spans lost during send | Apply TextSpanSerializer.prepareForSend() to EditText.getText() |
| HTML tags appear literally (`&lt;b&gt;`) | Deserialization failed in adapter | This shouldn't happen; check for exceptions in logcat |
| Formatting persists in EditText after clear | EditText span leftover | Clear EditText with `setText("")` (existing code does this) |
| Colors look different when received | Span/color mismatch | Verify AbsoluteSizeSpan uses `dip=true` for DIP conversion |

---

## Files to Modify

1. **ConversationFragment.java** — wrap EditText.getText() in send method
2. **ChatActivity.java** — if text send happens here instead
3. **ChatMediaController.java** — if media captions with formatting are sent
4. **Any custom input controller** — same pattern

No other changes needed. Canvas view + adapter + serializer handle the rest.

---

## Summary

**One change:** Wrap `EditText.getText()` with `TextSpanSerializer.prepareForSend()` before sending.

**One file added:** `TextSpanSerializer.java` (already in codebase)

**Two files updated:** `MessageBubbleCanvasView.java` + `MessagePagingAdapter.java` (already done)

That's it. Formatted text will now display with colors, sizes, fonts, and styles preserved.
