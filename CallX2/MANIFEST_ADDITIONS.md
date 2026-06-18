# AndroidManifest.xml — Status System Additions

## Permissions (add to <manifest> block)
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"/>
<uses-permission android:name="android.permission.USE_EXACT_ALARM"/>
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES"/>
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
```

## Activities (add inside <application> block)
```xml
<activity
    android:name=".activities.NewStatusActivity"
    android:windowSoftInputMode="adjustResize"
    android:exported="false"/>

<activity
    android:name=".activities.StatusViewerActivity"
    android:theme="@style/Theme.AppCompat.NoActionBar"
    android:screenOrientation="portrait"
    android:exported="false"/>

<activity
    android:name=".activities.MyStatusActivity"
    android:exported="false"/>

<activity
    android:name=".activities.StatusHighlightsActivity"
    android:exported="false"/>

<activity
    android:name=".activities.StatusArchiveActivity"
    android:exported="false"/>

<activity
    android:name=".activities.StatusPrivacySettingsActivity"
    android:exported="false"/>

<activity
    android:name=".activities.StatusPrivacyContactPickerActivity"
    android:windowSoftInputMode="adjustResize"
    android:exported="false"/>
```

## Service (add inside <application> block)
```xml
<service
    android:name=".services.StatusBackgroundService"
    android:foregroundServiceType="dataSync"
    android:stopWithTask="false"
    android:exported="false"/>
```

## Receiver (add inside <application> block)
```xml
<receiver
    android:name=".receivers.StatusExpiryReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="com.callx.app.ACTION_STATUS_EXPIRY_REMINDER"/>
        <action android:name="com.callx.app.ACTION_EXTEND_STATUS"/>
    </intent-filter>
</receiver>
```

## Intent filter for deep links (add to MainActivity or LauncherActivity)
```xml
<intent-filter>
    <action android:name="com.callx.app.ACTION_OPEN_STATUS"/>
    <category android:name="android.intent.category.DEFAULT"/>
</intent-filter>
<intent-filter>
    <action android:name="com.callx.app.ACTION_OPEN_MY_STATUS"/>
    <category android:name="android.intent.category.DEFAULT"/>
</intent-filter>
```
