package com.callx.app.linkeddevices;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents one companion device linked to a primary CallX account
 * (WhatsApp-style "Linked Devices"). Stored in Firebase RTDB at:
 *   users/{primaryUid}/linkedDevices/{deviceUid}
 *
 * {deviceUid} is the Firebase Anonymous-Auth uid the companion device
 * signed in with — it is what the security rules key off of to grant
 * that device read/write access to the primary account's data.
 */
public class LinkedDeviceModel {

    public String deviceUid;      // Firebase anonymous uid of the companion device
    public String deviceName;     // e.g. "Redmi Note 12 Pro"
    public String platform;       // "Android"
    public String appVersion;     // versionName at link time
    public long   linkedAt;       // server timestamp, ms
    public long   lastActiveAt;   // server timestamp, ms — updated by companion via presence pings
    public boolean isCurrentDevice; // set locally, not stored in Firebase

    public LinkedDeviceModel() {}

    public LinkedDeviceModel(String deviceUid, String deviceName, String platform,
                              String appVersion, long linkedAt, long lastActiveAt) {
        this.deviceUid = deviceUid;
        this.deviceName = deviceName;
        this.platform = platform;
        this.appVersion = appVersion;
        this.linkedAt = linkedAt;
        this.lastActiveAt = lastActiveAt;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("deviceName", deviceName);
        map.put("platform", platform);
        map.put("appVersion", appVersion);
        map.put("linkedAt", linkedAt);
        map.put("lastActiveAt", lastActiveAt);
        return map;
    }
}
