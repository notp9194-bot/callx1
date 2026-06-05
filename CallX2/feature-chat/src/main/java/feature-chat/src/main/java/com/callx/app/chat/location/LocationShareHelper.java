package com.callx.app.chat.location;

  import android.content.Context;
  import android.location.Location;
  import com.google.android.gms.location.FusedLocationProviderClient;
  import com.google.android.gms.location.LocationServices;
  import com.google.android.gms.location.Priority;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.FirebaseDatabase;
  import java.util.HashMap;
  import java.util.Map;
  import java.util.UUID;

  /**
   * LocationShareHelper — Send current location as a chat message.
   *
   * Message type = "location"
   * Fields: locationLat, locationLng, locationName (optional)
   * Renders as a static Google Maps thumbnail in MessageAdapter.
   *
   * Permissions required: ACCESS_FINE_LOCATION
   */
  public class LocationShareHelper {

      public interface LocationCallback {
          void onLocationReady(double lat, double lng);
          void onError(String reason);
      }

      private final Context context;
      private final FusedLocationProviderClient client;

      public LocationShareHelper(Context context) {
          this.context = context;
          this.client  = LocationServices.getFusedLocationProviderClient(context);
      }

      @android.annotation.SuppressLint("MissingPermission")
      public void getCurrentLocation(LocationCallback cb) {
          client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(loc -> {
                    if (loc != null) cb.onLocationReady(loc.getLatitude(), loc.getLongitude());
                    else cb.onError("Location unavailable");
                })
                .addOnFailureListener(e -> cb.onError(e.getMessage()));
      }

      /** Send a location message to chatId */
      public static void sendLocation(String chatId, double lat, double lng, String locationName) {
          String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
          String msgId = UUID.randomUUID().toString().replace("-", "");
          Map<String, Object> msg = new HashMap<>();
          msg.put("id", msgId);
          msg.put("senderId", myUid);
          msg.put("type", "location");
          msg.put("locationLat", lat);
          msg.put("locationLng", lng);
          msg.put("locationName", locationName != null ? locationName : "");
          msg.put("timestamp", System.currentTimeMillis());
          msg.put("status", "sent");
          FirebaseDatabase.getInstance()
              .getReference("chats").child(chatId).child("messages").child(msgId)
              .setValue(msg);
      }

      /** Returns Google Static Maps URL for thumbnail display */
      public static String getStaticMapUrl(double lat, double lng, int w, int h) {
          return "https://maps.googleapis.com/maps/api/staticmap?center="
              + lat + "," + lng
              + "&zoom=15&size=" + w + "x" + h
              + "&markers=color:red%7C" + lat + "," + lng;
      }
  }