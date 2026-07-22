package com.callx.app.chat.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.callx.app.utils.LinkPreviewFetcher;

/**
 * ComposeLinkPreviewController — WhatsApp/Telegram-style "type karte hi
 * preview" card jo message input bar ke upar dikhta hai, send se pehle.
 *
 * Reuses the same {@link LinkPreviewFetcher} (cache + in-flight dedup)
 * that already powers sent-message bubble previews, so a URL the user
 * just typed and one already sent share the same cache entry.
 *
 * Behavior:
 *   • URL type hote hi {@value #DEBOUNCE_MS}ms debounce ke baad fetch()
 *     trigger hota hai — har keystroke pe HTTP call nahi jaati.
 *   • Fetch complete hone tak agar text/URL badal gaya, stale result
 *     drop kar diya jata hai (extra check via still-current URL match).
 *   • Cross (dismiss) button dabane par card hide ho jata hai, aur
 *     wahi URL dobara nahi dikhta jab tak text me badlaav na ho.
 *   • Text se URL hat jaye to card khud hide ho jata hai.
 */
public class ComposeLinkPreviewController {

    private static final long DEBOUNCE_MS = 400;

    private final EditText editText;
    private final View barRoot;
    private final ImageView thumb;
    private final TextView domainView;
    private final TextView titleView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingFetch = null;

    private String shownUrl = null;      // URL jiska card currently visible hai
    private String dismissedUrl = null;  // URL jise user ne manually band kiya

    public ComposeLinkPreviewController(EditText editText, View barRoot, ImageView thumb,
                                         TextView domainView, TextView titleView,
                                         ImageButton cancelButton) {
        this.editText = editText;
        this.barRoot = barRoot;
        this.thumb = thumb;
        this.domainView = domainView;
        this.titleView = titleView;

        cancelButton.setOnClickListener(v -> {
            dismissedUrl = shownUrl;
            hide();
        });
    }

    /** Call this from the message EditText's TextWatcher on every change. */
    public void onTextChanged(String text) {
        if (pendingFetch != null) {
            handler.removeCallbacks(pendingFetch);
            pendingFetch = null;
        }

        String url = LinkPreviewFetcher.extractFirstUrl(text);
        if (url == null) {
            dismissedUrl = null;
            hide();
            return;
        }
        if (url.equals(dismissedUrl)) return; // user ne isi URL ko dismiss kiya tha
        if (url.equals(shownUrl)) return;      // already dikha hua hai

        // Cache hit ho to turant dikhao, koi debounce/network wait nahi.
        LinkPreviewFetcher.Result cached = LinkPreviewFetcher.peek(url);
        if (cached != null) {
            bind(url, cached);
            return;
        }

        pendingFetch = () -> LinkPreviewFetcher.fetch(url, new LinkPreviewFetcher.Callback() {
            @Override public void onResult(LinkPreviewFetcher.Result result) {
                if (!isStillCurrent(url)) return; // user tab tak type/delete kar chuka hai
                bind(url, result);
            }
            @Override public void onError(String failedUrl) { /* silently ignore — no card */ }
        });
        handler.postDelayed(pendingFetch, DEBOUNCE_MS);
    }

    /** Clears all state — call on send / clearReply-equivalent reset. */
    public void reset() {
        if (pendingFetch != null) {
            handler.removeCallbacks(pendingFetch);
            pendingFetch = null;
        }
        shownUrl = null;
        dismissedUrl = null;
        hide();
    }

    /** URL jiska preview abhi dikh raha hai, ya null. */
    public String getShownUrl() {
        return shownUrl;
    }

    private boolean isStillCurrent(String url) {
        CharSequence current = editText.getText();
        String currentUrl = current != null
                ? LinkPreviewFetcher.extractFirstUrl(current.toString()) : null;
        return url.equals(currentUrl);
    }

    private void bind(String url, LinkPreviewFetcher.Result result) {
        shownUrl = url;
        domainView.setText(result.domain != null ? result.domain.toUpperCase(java.util.Locale.getDefault()) : "");
        titleView.setText(result.title != null ? result.title : url);

        if (result.imageUrl != null && !result.imageUrl.isEmpty()) {
            thumb.setVisibility(View.VISIBLE);
            Glide.with(editText.getContext())
                    .load(result.imageUrl)
                    .centerCrop()
                    .override(720, 720)
                    .into(thumb);
        } else {
            thumb.setVisibility(View.GONE);
        }

        barRoot.setVisibility(View.VISIBLE);
    }

    private void hide() {
        shownUrl = null;
        barRoot.setVisibility(View.GONE);
    }
}
