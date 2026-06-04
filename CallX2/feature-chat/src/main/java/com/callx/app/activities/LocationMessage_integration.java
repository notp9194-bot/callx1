// ═══════════════════════════════════════════════════════════════════
// LOCATION MESSAGE — Complete Integration Guide
// ═══════════════════════════════════════════════════════════════════

// ── PART 1: Message.java — add location fields ───────────────────────
// (in core/src/main/java/com/callx/app/models/Message.java)

public double latitude  = 0.0;   // GPS latitude
public double longitude = 0.0;   // GPS longitude
public String address   = null;  // Human-readable address
// thumbnailUrl field already exists — reuse for static map thumb URL

// ── PART 2: MessageEntity.java — add location fields ────────────────
// (in core/src/main/java/com/callx/app/db/entity/MessageEntity.java)

public double latitude  = 0.0;
public double longitude = 0.0;
public String address   = null;
// thumbnailUrl already present — reused for map thumb

// ── PART 3: ChatActivity.java ─────────────────────────────────────────

// Step A: Add constant
private static final int REQ_LOCATION = 503;

// Step B: In setupAttachMenu() — add location option to bottom_sheet_attach.xml
// (already has ic_attach items — add one for location)

// Step C: Launch LocationPickerActivity from attach menu
void onLocationAttachClicked() {
    Intent i = new Intent(this, LocationPickerActivity.class);
    startActivityForResult(i, REQ_LOCATION);
}

// Step D: Handle result in onActivityResult()
if (requestCode == REQ_LOCATION && resultCode == RESULT_OK && data != null) {
    double lat     = data.getDoubleExtra(LocationPickerActivity.RESULT_LAT, 0);
    double lng     = data.getDoubleExtra(LocationPickerActivity.RESULT_LNG, 0);
    String address = data.getStringExtra(LocationPickerActivity.RESULT_ADDRESS);
    String thumb   = data.getStringExtra(LocationPickerActivity.RESULT_THUMB_URL);

    sendLocationMessage(lat, lng, address, thumb);
}

// Step E: sendLocationMessage() helper
private void sendLocationMessage(double lat, double lng, String address, String thumbUrl) {
    Message msg     = buildOutgoing();
    msg.type        = "location";
    msg.latitude    = lat;
    msg.longitude   = lng;
    msg.address     = address != null ? address : "";
    msg.text        = "📍 " + msg.address;   // chat list preview
    msg.thumbnailUrl = thumbUrl;

    String preview  = "📍 Location";

    if (viewModel != null) viewModel.sendMessage(msg, preview);
    else pushMessage(msg, preview);
}

// ── PART 4: MessagePagingAdapter.java — render location bubble ────────

// In getItemViewType():
case "location": return TYPE_LOCATION;  // add new type constant = 6

// In onCreateViewHolder():
case TYPE_LOCATION:
    View locView = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_message_location, parent, false);
    return new VH(locView);

// In onBindViewHolder() — add location binding:
private void bindLocation(VH h, Message msg) {
    // Load static map thumbnail
    if (msg.thumbnailUrl != null && !msg.thumbnailUrl.isEmpty()) {
        Glide.with(h.itemView.getContext())
                .load(msg.thumbnailUrl)
                .placeholder(R.drawable.ic_pin)
                .into(h.ivMapThumb);
    }

    // Address text
    if (h.tvLocationAddress != null) {
        h.tvLocationAddress.setText(
                msg.address != null ? msg.address
                        : String.format("%.4f, %.4f", msg.latitude, msg.longitude));
    }

    // Timestamp
    if (h.tvTime != null && msg.timestamp != null) {
        h.tvTime.setText(new SimpleDateFormat("hh:mm a", Locale.getDefault())
                .format(new java.util.Date(msg.timestamp)));
    }

    // Bubble gravity: sent = right, received = left
    LinearLayout.LayoutParams params =
            (LinearLayout.LayoutParams) h.llBubble.getLayoutParams();
    if (msg.senderId != null && msg.senderId.equals(currentUid)) {
        params.gravity = android.view.Gravity.END;
        h.llBubble.setBackgroundResource(R.drawable.bubble_sent);
    } else {
        params.gravity = android.view.Gravity.START;
        h.llBubble.setBackgroundResource(R.drawable.bubble_received);
    }
    h.llBubble.setLayoutParams(params);

    // Tap bubble → open Google Maps
    h.llBubble.setOnClickListener(v -> openInMaps(h.itemView.getContext(), msg));

    // "Open in Maps" text tap
    if (h.tvOpenMaps != null) {
        h.tvOpenMaps.setOnClickListener(v -> openInMaps(h.itemView.getContext(), msg));
    }
}

// Step F: openInMaps() helper (in adapter or as a utility)
private void openInMaps(android.content.Context ctx, Message msg) {
    // Try Google Maps first, fallback to browser
    Uri geoUri = Uri.parse(String.format(Locale.US,
            "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
            msg.latitude, msg.longitude,
            msg.latitude, msg.longitude,
            Uri.encode(msg.address != null ? msg.address : "Location")));

    Intent intent = new Intent(Intent.ACTION_VIEW, geoUri);
    intent.setPackage("com.google.android.apps.maps"); // prefer Google Maps

    if (intent.resolveActivity(ctx.getPackageManager()) != null) {
        ctx.startActivity(intent);
    } else {
        // Fallback: open in browser (OpenStreetMap)
        String url = String.format(Locale.US,
                "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f&zoom=15",
                msg.latitude, msg.longitude);
        ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }
}

// ── PART 5: AndroidManifest.xml ──────────────────────────────────────
/*
<!-- Permissions (add to manifest) -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>

<!-- Activity -->
<activity
    android:name="com.callx.app.activities.LocationPickerActivity"
    android:theme="@style/AppTheme"
    android:exported="false"/>
*/

// ── PART 6: build.gradle — add Google Maps dependency ─────────────────
/*
// In feature-chat/build.gradle:
implementation 'com.google.android.gms:play-services-maps:18.2.0'
implementation 'com.google.android.gms:play-services-location:21.2.0'

// In AndroidManifest.xml (inside <application>):
<meta-data
    android:name="com.google.android.geo.API_KEY"
    android:value="@string/maps_api_key"/>

// In res/values/strings.xml:
<string name="maps_api_key">YOUR_GOOGLE_MAPS_API_KEY</string>
*/

// ── PART 7: bottom_sheet_attach.xml — Add Location button ─────────────
/*
Add this item to your existing attach bottom sheet grid:

<LinearLayout ... android:id="@+id/btn_location">
    <ImageView android:src="@drawable/ic_pin" android:tint="@color/brand_primary"/>
    <TextView android:text="Location"/>
</LinearLayout>
*/

// ── PART 8: Firebase location data structure ──────────────────────────
/*
chats/{chatId}/messages/{msgId}:
{
  "type":         "location",
  "latitude":     28.613939,
  "longitude":    77.209023,
  "address":      "Connaught Place, New Delhi, India",
  "thumbnailUrl": "https://staticmap.openstreetmap.de/...",
  "text":         "📍 Connaught Place, New Delhi, India",
  "timestamp":    1717430400000,
  "senderId":     "uid123",
  "status":       "sent"
}
*/
