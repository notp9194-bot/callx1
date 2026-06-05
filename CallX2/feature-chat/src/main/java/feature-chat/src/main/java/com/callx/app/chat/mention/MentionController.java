package com.callx.app.chat.mention;

  import android.text.Editable;
  import android.text.SpannableString;
  import android.text.Spanned;
  import android.text.style.ForegroundColorSpan;
  import android.widget.EditText;
  import androidx.recyclerview.widget.RecyclerView;
  import com.callx.app.models.User;

  /**
   * MentionController — Wires EditText + MentionSuggestAdapter together.
   *
   * - Detects '@' trigger in input
   * - Filters suggestions as user types
   * - Inserts coloured @mention span on selection
   */
  public class MentionController {

      private final EditText editText;
      private final MentionSuggestAdapter adapter;
      private final RecyclerView rvSuggestions;
      private int mentionStart = -1;

      public MentionController(EditText editText, RecyclerView rvSuggestions,
                               MentionSuggestAdapter adapter) {
          this.editText      = editText;
          this.rvSuggestions = rvSuggestions;
          this.adapter       = adapter;
      }

      /** Call from TextWatcher.afterTextChanged */
      public void onTextChanged(Editable s) {
          int cursor = editText.getSelectionStart();
          String text = s.toString();
          int atPos = text.lastIndexOf('@', cursor - 1);
          if (atPos >= 0) {
              String query = text.substring(atPos + 1, cursor);
              if (!query.contains(" ")) {
                  mentionStart = atPos;
                  adapter.filter(query);
                  rvSuggestions.setVisibility(android.view.View.VISIBLE);
                  return;
              }
          }
          mentionStart = -1;
          rvSuggestions.setVisibility(android.view.View.GONE);
      }

      /** Call when user taps a suggestion */
      public void onMentionSelected(User user) {
          if (mentionStart < 0) return;
          Editable editable = editText.getText();
          int cursor = editText.getSelectionStart();
          // Replace @partial with @username
          String mention = "@" + user.name + " ";
          editable.replace(mentionStart, cursor, mention);
          // Colour the mention span
          int end = mentionStart + mention.length() - 1; // exclude trailing space
          editable.setSpan(
              new ForegroundColorSpan(0xFF2196F3),
              mentionStart, end,
              Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
          );
          rvSuggestions.setVisibility(android.view.View.GONE);
          mentionStart = -1;
      }
  }