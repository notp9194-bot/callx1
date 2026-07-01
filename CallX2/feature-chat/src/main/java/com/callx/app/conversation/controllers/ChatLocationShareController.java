package com.callx.app.conversation.controllers;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.callx.app.chat.LocationSharePickerActivity;
import com.callx.app.chat.R;
import com.callx.app.models.Message;

import java.util.Locale;

/**
 * Handles:
 *   1. Launching LocationSharePickerActivity
 *   2. Receiving the result and building + pushing the location message
 *   3. Binding a "location" bubble in MessageAdapter / MessagePagingAdapter
 *
 * Wiring (in ChatActivity / GroupChatActivity):
 *
 *   private ChatLocationShareController locationShareController;
 *
 *   // in onCreate
 *   locationShareController = new ChatLocationShareController(
 *       this, this::buildOutgoing, this::pushMessage);
 *
 *   // in showAttachSheet opt_location click:
 *   locationShareController.launch();
 *
 *   // in onActivityResult:
 *   if (locationShareController.handleResult(requestCode, resultCode, data)) return;
 */
public class ChatLocationShareController {

    public static final int REQUEST_CODE = 3002;

    // Optional: your Google Maps Static API key.
    // Set this once at app startup via ChatLocationShareController.setMapsApiKey(key)
    // If not set, a placeholder pin icon is shown instead of a real map thumbnail.
    @Nullable
    private static String sMapsApiKey = null;

    public static void setMapsApiKey(@Nullable String key) { sMapsApiKey = key; }

    public interface BuildOutgoing { Message build(); }
    public interface PushMessage   { void push(Message m, String preview); }

    private final Activity      activity;
    private final BuildOutgoing buildOutgoing;
    private final PushMessage   pushMessage;

    public ChatLocationShareController(Activity activity,
                                       BuildOutgoing buildOutgoing,
                                       PushMessage pushMessage) {
        this.activity      = activity;
        this.buildOutgoing = buildOutgoing;
        this.pushMessage   = pushMessage;
    }

    // ── Launch ────────────────────────────────────────────────────────────────

    public void launch() {
        Intent i = new Intent(activity, LocationSharePickerActivity.class);
        activity.startActivityForResult(i, REQUEST_CODE);
    }

    // ── Result ────────────────────────────────────────────────────────────────

    /** @return true if this controller consumed the result. */
    public boolean handleResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_CODE) return false;
        if (resultCode != Activity.RESULT_OK || data == null) return true;

        double lat     = data.getDoubleExtra(LocationSharePickerActivity.EXTRA_LAT, 0);
        double lng     = data.getDoubleExtra(LocationSharePickerActivity.EXTRA_LNG, 0);
        String address = data.getStringExtra(LocationSharePickerActivity.EXTRA_ADDRESS);

        if (lat == 0 && lng == 0) return true;

        Message m         = buildOutgoing.build();
        m.type            = "location";
        m.locationLat     = lat;
        m.locationLng     = lng;
        m.locationAddress = address;
        m.text            = address != null && !address.isEmpty()
                ? "📍 " + address
                : String.format(Locale.getDefault(), "📍 %.5f, %.5f", lat, lng);

        pushMessage.push(m, m.text);
        return true;
    }

    // ── Bubble binding ────────────────────────────────────────────────────────

    /**
     * Bind a location bubble.
     *
     * @param bubbleRoot  root view inflated from item_msg_location.xml
     * @param message     the location message
     */
    public static void bindBubble(View bubbleRoot, Message message) {
        ImageView ivMap       = bubbleRoot.findViewById(R.id.ivMapThumb);
        TextView  tvAddress   = bubbleRoot.findViewById(R.id.tvLocationAddress);
        TextView  btnOpenMaps = bubbleRoot.findViewById(R.id.btnOpenMaps);

        double lat = message.locationLat  != null ? message.locationLat  : 0;
        double lng = message.locationLng  != null ? message.locationLng  : 0;

        // Address text
        if (message.locationAddress != null && !message.locationAddress.isEmpty()) {
            tvAddress.setText(message.locationAddress);
        } else {
            tvAddress.setText(String.format(Locale.getDefault(), "%.5f, %.5f", lat, lng));
        }

        // Map thumbnail — use Google Maps Static API if key available, else placeholder
        if (sMapsApiKey != null && !sMapsApiKey.isEmpty() && lat != 0) {
            String thumbUrl = String.format(Locale.US,
                    "https://maps.googleapis.com/maps/api/staticmap"
                    + "?center=%.6f,%.6f&zoom=15&size=400x200&markers=%.6f,%.6f&key=%s",
                    lat, lng, lat, lng, sMapsApiKey);
            Glide.with(bubbleRoot.getContext())
                    .load(thumbUrl)
                    .placeholder(R.drawable.ic_location_pin)
                    .into(ivMap);
        } else {
            ivMap.setImageResource(R.drawable.ic_location_pin);
        }

        // "Open in Maps" → Google Maps (or any installed maps app)
        btnOpenMaps.setOnClickListener(v -> {
            String geoUri = String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f", lat, lng, lat, lng);
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(geoUri));
            mapIntent.setPackage("com.google.android.apps.maps");
            if (mapIntent.resolveActivity(v.getContext().getPackageManager()) != null) {
                v.getContext().startActivity(mapIntent);
            } else {
                // Fallback: any maps app
                Intent fallback = new Intent(Intent.ACTION_VIEW,
                        Uri.parse(String.format(Locale.US,
                                "https://maps.google.com/?q=%.6f,%.6f", lat, lng)));
                v.getContext().startActivity(fallback);
            }
        });
    }
}
