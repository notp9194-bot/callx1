# CallX — Call System Fixes v4

Fixes applied:
1. CallForegroundService: onTaskRemoved() → PushNotify.notifyMissedCall() added
2. GroupCallForegroundService: onTaskRemoved() → isCaller kills call for all + PushNotify.notifyMissedGroupCall()
3. CallActivity: activeCallConnected flag set on connect/reset on destroy
4. activity_call.xml: minimize button added (PiP for video, moveTaskToBack for audio)
